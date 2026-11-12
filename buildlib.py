"""Shared logic for build.py and build-publish.py.

Every (minecraft version, loader) pair is built in its own copy of the project
(``.build-work/<index>``).  Gradle runs in the project directory and shares
``build/`` and ``build.properties`` between versions, so running several versions
in parallel inside one directory silently mixes classes of different versions.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WORK = ROOT / ".build-work"
DIST = ROOT / "buildAllJars"
LOGS = ROOT / "build-logs"

MINECRAFT_VERSIONS = [
    "1.16.5",   # 0  (not supported by the build setup)
    "1.17.1",   # 1  (not supported by the build setup)
    "1.18.2",   # 2
    "1.19.2",   # 3
    "1.19.4",   # 4
    "1.20.1",   # 5
    "1.20.4",   # 6  (fabric only)
    "1.21",     # 7
    "1.21.1",   # 8
    "1.21.3",   # 9
    "1.21.4",   # 10
    "1.21.5",   # 11
    "1.21.6",   # 12
    "1.21.7",   # 13
    "1.21.8",   # 14
    "1.21.9",   # 15
    "1.21.10",  # 16
    "1.21.11",  # 17
    "26.1",     # 18
    "26.1.1",   # 19
    "26.1.2",   # 20
    "26.2",     # 21
    "26.3",     # 22
]
MIN_INDEX = 2
ALL_LOADERS = ["fabric", "neoforge", "forge"]

RSYNC_EXCLUDES = [
    ".git", ".idea", ".gradle", ".build-work", "build", "buildAllJars", "build-logs",
    "run", "run-dh", "run-voxy", "run-sodium", ".architectury-transformer", "*.log", "hs_err_pid*", "__pycache__",
]


def java_version(index: int) -> int:
    if index < 7:
        return 17
    if index < 17:
        return 21
    return 25


def loader_for_index(index: int, loader: str) -> bool:
    """Is `loader` buildable for this minecraft version?"""
    if loader == "fabric":
        return True
    if index == 6:          # 1.20.4: no forge/neoforge support in the project
        return False
    if loader == "forge":
        return index < 7
    if loader == "neoforge":
        return index >= 7
    return False


def read_props() -> dict[str, str]:
    props: dict[str, str] = {}
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


@dataclass
class Job:
    index: int
    loader: str
    tasks: list[str]
    state: str = "queued"       # queued | building | publishing | done | error
    error: str = ""
    jar: Path | None = None

    @property
    def mc(self) -> str:
        return MINECRAFT_VERSIONS[self.index]

    @property
    def label(self) -> str:
        return f"{self.mc}/{self.loader}"


# ---------------------------------------------------------------- arguments

def add_common_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("-v", "--versions", help="comma separated minecraft versions (default: all), e.g. 1.20.1,1.21.1")
    parser.add_argument("-l", "--loaders", help="comma separated loaders (default: fabric,neoforge,forge)")
    parser.add_argument("-j", "--jobs", type=int, default=2, help="parallel gradle builds (default: 2, each can use up to 8G)")
    parser.add_argument("--labels", help="exactly these runs, e.g. 1.20.1/fabric,1.21.1/neoforge (instead of -v x -l)")
    parser.add_argument("--plain", action="store_true", help="no TUI, plain log output")
    parser.add_argument("--clean", action="store_true", help="remove .build-work before building")


def select_jobs(args: argparse.Namespace, extra_tasks) -> list[Job]:
    versions = MINECRAFT_VERSIONS[MIN_INDEX:]
    if args.versions:
        wanted = [v.strip() for v in args.versions.split(",") if v.strip()]
        unknown = [v for v in wanted if v not in MINECRAFT_VERSIONS[MIN_INDEX:]]
        if unknown:
            sys.exit(f"unknown/unsupported minecraft versions: {', '.join(unknown)}")
        versions = wanted

    loaders = ALL_LOADERS
    if args.loaders:
        loaders = [l.strip() for l in args.loaders.split(",") if l.strip()]
        unknown = [l for l in loaders if l not in ALL_LOADERS]
        if unknown:
            sys.exit(f"unknown loaders: {', '.join(unknown)}")

    jobs: list[Job] = []
    for mc in versions:
        index = MINECRAFT_VERSIONS.index(mc)
        for loader in loaders:
            if loader_for_index(index, loader):
                jobs.append(Job(index, loader, [f":{loader}:build", *extra_tasks(loader)]))
    if getattr(args, "labels", None):
        wanted_labels = {l.strip() for l in args.labels.split(",") if l.strip()}
        jobs = [j for j in jobs if j.label in wanted_labels]
    if not jobs:
        sys.exit("nothing to build for this selection")
    return jobs


# ------------------------------------------------------------------- work

def sync_workdir(index: int) -> Path:
    target = WORK / str(index)
    target.mkdir(parents=True, exist_ok=True)
    if shutil.which("rsync"):
        cmd = ["rsync", "-a", "--delete"]
        for ex in RSYNC_EXCLUDES:
            cmd += ["--exclude", ex]
        # excluded paths are not deleted by --delete, so .gradle/ and build/ of the work dir survive between runs
        cmd += [f"{ROOT}/", f"{target}/"]
        subprocess.run(cmd, check=True)
    else:
        for item in ROOT.iterdir():
            if item.name in (".git", ".idea", ".gradle", ".build-work", "build", "buildAllJars", "build-logs", "__pycache__"):
                continue
            dst = target / item.name
            if item.is_dir():
                shutil.copytree(item, dst, dirs_exist_ok=True,
                                ignore=shutil.ignore_patterns("build", "run", ".gradle", "*.log"))
            else:
                shutil.copy2(item, dst)
    return target


_dist_lock = threading.Lock()
REBUILD = False             # build.py --rebuild: build jars even when nothing changed since the last build
_sources_cache: list = []


def sources_hash() -> str:
    """The state of everything a jar is made of (sources, resources, build files), the same for all jobs of one run."""
    if _sources_cache:
        return _sources_cache[0]
    import hashlib
    digest = hashlib.sha256()
    skip = {".git", ".idea", ".gradle", ".build-work", "build", "buildAllJars", "build-logs", "run", "run-dh", "run-voxy", "run-sodium",
            "__pycache__", "legacy", "tests"}
    for folder, dirs, files in os.walk(ROOT):
        dirs[:] = sorted(d for d in dirs if d not in skip)
        for name in sorted(files):
            path = Path(folder) / name
            if path.suffix in (".log", ".png", ".md", ".yml", ".json", ".py") or name in ("credentials.properties", "build.properties"):
                continue
            digest.update(str(path.relative_to(ROOT)).encode())
            digest.update(path.read_bytes())
    _sources_cache.append(digest.hexdigest())
    return _sources_cache[0]


def run_job(job: Job, props: dict[str, str], notify) -> None:
    name = props["archives_name"]
    version = props["mod_version"]
    LOGS.mkdir(exist_ok=True)
    log_path = LOGS / f"{name}-mc{job.mc}-{job.loader}.log"

    try:
        workdir = sync_workdir(job.index)
    except Exception as exc:  # noqa: BLE001
        job.state, job.error = "error", f"sync failed: {exc}"
        notify(job)
        return

    # a jar that was built from exactly these sources (and version) is not built again; publishing always builds
    publishing = any(":modrinth" in t or ":publishCurseForge" in t for t in job.tasks)
    stamp_file = workdir / job.loader / "build" / "libs" / ".jar-stamp"
    stamp = f"{sources_hash()}|{version}|{job.index}"
    jar_now = workdir / job.loader / "build" / "libs" / f"{name}-mc{job.mc}-{job.loader}-{version}.jar"
    if not REBUILD and not publishing and jar_now.exists() and stamp_file.exists() and stamp_file.read_text() == stamp:
        with _dist_lock:
            DIST.mkdir(exist_ok=True)
            shutil.copy2(jar_now, DIST / jar_now.name)
        job.jar = DIST / jar_now.name
        job.state = "done"
        job.error = ""
        notify(job)
        return

    jdk = Path(f"/usr/lib/jvm/java-{java_version(job.index)}-temurin")
    # NB: do not override JAVA_HOME - gradle itself must run on the newest JDK (plugins need 25),
    # the per-version JDK is picked through org.gradle.java.home below / settings.gradle.
    env = os.environ.copy()

    cmd = [
        "sh", "gradlew", "--console=plain", *job.tasks,
        f"-Pversion_index={job.index}",
        f"-PMC_VER={job.index}",
        f"-Porg.gradle.java.home={jdk}",
    ]

    NETWORK_ERRORS = ("отказано", "Connection refused", "Connection reset", "timed out",
                      "Could not GET", "Could not HEAD", "Premature end", "Premature EOF", "Unexpected end of", "DownloadException", "Failed to download", "UnknownHostException")
    attempts = 3
    returncode = 1
    for attempt in range(1, attempts + 1):
        job.state = "building"
        notify(job)

        output: list[str] = []
        with log_path.open("w" if attempt == 1 else "a") as log:
            log.write(f"$ {' '.join(cmd)}  (attempt {attempt}/{attempts})\n\n")
            log.flush()
            proc = subprocess.Popen(cmd, cwd=workdir, env=env, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, bufsize=1)
            assert proc.stdout is not None
            for line in proc.stdout:
                log.write(line)
                output.append(line)
                # Publishing tasks run in the same gradle invocation as the build
                if job.state == "building" and line.startswith("> Task") and (
                        ":modrinth" in line or ":publishCurseForge" in line):
                    job.state = "publishing"
                    notify(job)
            proc.wait()
        returncode = proc.returncode

        if returncode == 0:
            break
        # Never retry once something was already published - it would upload a duplicate.
        if job.state == "publishing" or not any(e in "".join(output) for e in NETWORK_ERRORS):
            break
        time.sleep(5 * attempt)

    if returncode != 0:
        job.state = "error"
        job.error = f"gradle exit {returncode}, see {log_path.relative_to(ROOT)}"
        notify(job)
        return

    jar = workdir / job.loader / "build" / "libs" / f"{name}-mc{job.mc}-{job.loader}-{version}.jar"
    if not jar.exists():
        job.state = "error"
        job.error = f"jar not found: {jar.relative_to(workdir)}"
        notify(job)
        return

    if not publishing:
        stamp_file.write_text(stamp)
    with _dist_lock:
        DIST.mkdir(exist_ok=True)
        shutil.copy2(jar, DIST / jar.name)
    job.jar = DIST / jar.name
    job.state = "done"
    notify(job)


def execute(jobs: list[Job], props: dict[str, str], workers: int, plain: bool) -> int:
    """Run all jobs, return a process exit code."""
    if not plain and sys.stdout.isatty():
        try:
            return _execute_tui(jobs, props, workers)
        except ImportError:
            print("textual is not installed (pip install textual), falling back to --plain", file=sys.stderr)
    return _execute_plain(jobs, props, workers)


def _run_group(group, props, notify):
    for job in group:
        run_job(job, props, notify)


def _pool(jobs, props, workers, notify):
    """One work dir per minecraft version: loaders of the same version run one after another
    (they share the work dir, build.properties and gradle project cache), versions run in parallel."""
    groups: dict[int, list[Job]] = {}
    for job in jobs:
        groups.setdefault(job.index, []).append(job)

    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = [pool.submit(_run_group, group, props, notify) for group in groups.values()]
        for f in as_completed(futures):
            f.result()


def _stop_daemons() -> None:
    for workdir in WORK.glob("*"):
        if (workdir / "gradlew").exists():
            subprocess.run(["sh", "gradlew", "--stop"], cwd=workdir,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            break


def _summary(jobs: list[Job]) -> int:
    failed = [j for j in jobs if j.state != "done"]
    print(f"\n{len(jobs) - len(failed)}/{len(jobs)} succeeded")
    for j in failed:
        print(f"  FAILED {j.label}: {j.error}")
    return 1 if failed else 0


def _execute_plain(jobs, props, workers) -> int:
    started = time.time()
    lock = threading.Lock()

    def notify(job: Job) -> None:
        with lock:
            extra = f" - {job.error}" if job.error else ""
            print(f"[{time.time() - started:6.0f}s] {job.label:<22} {job.state}{extra}", flush=True)

    _pool(jobs, props, workers, notify)
    _stop_daemons()
    return _summary(jobs)


def _execute_tui(jobs, props, workers) -> int:
    from rich.text import Text
    from textual import work
    from textual.app import App, ComposeResult
    from textual.coordinate import Coordinate
    from textual.widgets import DataTable, Footer

    styles = {
        "queued": "white on grey30", "building": "black on yellow", "publishing": "black on cyan",
        "done": "black on green", "error": "white on red",
    }
    versions = sorted({j.index for j in jobs})
    loaders = [l for l in ALL_LOADERS if any(j.loader == l for j in jobs)]

    class BuildApp(App):
        BINDINGS = [("q", "quit", "quit")]

        def compose(self) -> ComposeResult:
            yield DataTable()
            yield Footer()

        def on_mount(self) -> None:
            self.table = self.query_one(DataTable)
            self.table.add_column("loader")
            for i in versions:
                self.table.add_column(MINECRAFT_VERSIONS[i])
            for loader in loaders:
                cells = []
                for i in versions:
                    ok = any(j.index == i and j.loader == loader for j in jobs)
                    cells.append(Text("queued", style=styles["queued"]) if ok else Text("-", style="grey50"))
                self.table.add_row(loader, *cells)
            self.run_all()

        def paint(self, job: Job) -> None:
            self.table.update_cell_at(
                Coordinate(loaders.index(job.loader), versions.index(job.index) + 1),
                Text(job.state, style=styles[job.state]), update_width=True)

        @work(thread=True)
        def run_all(self) -> None:
            _pool(jobs, props, workers, lambda job: self.call_from_thread(self.paint, job))
            _stop_daemons()
            self.call_from_thread(self.exit, _summary_code(jobs))

    code = BuildApp().run()
    return _summary(jobs) if code is None else code


def _summary_code(jobs: list[Job]) -> int:
    return 1 if any(j.state != "done" for j in jobs) else 0
