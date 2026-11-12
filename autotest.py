#!/usr/bin/env python3
"""Start every Minecraft version / loader for real and check it does not crash.

    python autotest.py                 # everything
    python autotest.py -v 1.20.1 -l fabric
    python autotest.py --menu          # only the main menu (ipla); default for ipla, see TEST below

The game is built from the sources with -Pautotest=1, which compiles the AutoTest hook of the mod: it waits for the main
menu, opens the world "TestWorld" (distant-thunders: makes it thunder, summons bolts, waits 10 s) and quits by itself.
A run passes when the log says AUTOTEST PASSED and no crash report was written. Needs a display: windows do open.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

import buildlib as b

SCRATCH = Path(os.environ.get("AUTOTEST_DIR", "/tmp/claude-1000/autotest"))
# kept next to the work dirs, not in /tmp: making it takes two minutes and /tmp is emptied
TEMPLATE = Path(os.environ.get("AUTOTEST_TEMPLATE", b.WORK / "autotest-TestWorldFlat"))      # a flat world: nothing grows, nothing spawns, the sky is all you see
RUN_TIMEOUT = 420
SMOKE = ["1.18.2", "1.20.1", "1.21.1", "1.21.8", "26.1.2", "26.3"]   # boundary versions of the API eras, run first
SKIP_FILE = b.ROOT / "autotest-skip.json"                            # {"26.1/neoforge": "why it cannot run"}
CACHE_FILE = SCRATCH / f"cache-{b.ROOT.name}.json"                   # label -> source hash of the last passed run
PARALLEL_SCREENS = 0
SIZE = os.environ.get("AUTOTEST_SIZE", "1280x720")                   # window / virtual screen size, e.g. 480x270
# hooks of autotest-grid.py: ON_PLAN(todo, skipped, cached labels), ON_START(label, display), ON_FINISH(label, ok, why)
ON_PLAN = ON_START = ON_FINISH = ON_STATUS = ON_PHASE = None   # ON_PHASE(label, "build" | "game")
STOP = threading.Event()                                             # set by stop_all(): nothing new starts, running games die
PROCS: set = set()
BUILDERS: set = set()                                                # the gradle builds among them: they must not be killed hard
BUILD_SLOTS = threading.Semaphore(2)                                 # how many gradle builds run at once (--builds)
GAME_SLOTS: queue.Queue = queue.Queue()                              # one entry per game that may run at once (--jobs): its display
_local = threading.local()                                           # the display of the game this thread is running
PAUSE = threading.Event()                                            # set: the running games finish, nothing new starts
DISPLAYS: dict[int, str] = {}                                        # slot -> DISPLAY of a virtual X server (--xvfb)
_slot_lock = threading.Lock()


TEST_META: dict = {}
SUITE: list = []                    # the names of the tests when several run in one start of the game (tests/a.yml,tests/b.yml)
SUITE_RESULTS: dict = {}            # label -> {test: (ok, why)}, also in results-tests.json
SOURCES_HASH = ""                  # set by main(): the state of the sources the direct runs are valid for


def run_dir_name(loader: str) -> str:
    """A test with `mods: dh` runs the Distant Horizons setup of the fabric project (run-dh/ with its own mods folder)."""
    # Forge (1.18 - 1.20.1) cannot load Distant Horizons in the dev environment (its names are the obfuscated ones of Forge, as a mods/ jar
    # and as a dependency), so there the test runs the normal client and only checks that the mod does not break without it
    return "run-dh" if TEST_META.get("mods") == "dh" and loader != "forge" else "run"


def prepare_run_dir(workdir: Path, loader: str, with_world: bool) -> Path:
    run = workdir / loader / run_dir_name(loader)
    if (run / "crash-reports").exists():
        shutil.rmtree(run / "crash-reports")
    run.mkdir(parents=True, exist_ok=True)
    width, height = SIZE.split("x")
    (run / "options.txt").write_text(f"overrideWidth:{width}\noverrideHeight:{height}\n" + "pauseOnLostFocus:false\nonboardAccessibility:false\ntutorialStep:none\njoinedFirstServer:true\n"
                                           # nothing here needs sound, far chunks, a narrator or a high frame rate
                                           "guiScale:1\nnarrator:0\nsoundCategory_master:0.0\nrenderDistance:2\nsimulationDistance:5\nmaxFps:60\nenableVsync:false\nparticles:2\nentityShadows:false\n" + (f"preferredGraphicsBackend:\"{os.environ['AUTOTEST_BACKEND']}\"\n" if os.environ.get("AUTOTEST_BACKEND") else ""))
    (run / "autotest.yml").write_text(TEST_TEXT)
    if loader == "neoforge":
        # NeoForge's dev client wraps the GPU in a validation layer, whose textures Distant Horizons cannot cast (ClassCastException in its
        # light texture mixin), and it opens a screen with the warnings of the mods: neither is wanted in a test
        config = run / "config" / "neoforge-client.toml"
        config.parent.mkdir(parents=True, exist_ok=True)
        kept = [l for l in (config.read_text().splitlines() if config.exists() else [])
                if not l.startswith(("enableB3DValidationLayer", "showLoadWarnings"))]
        config.write_text("\n".join(kept + ["enableB3DValidationLayer = false", "showLoadWarnings = false"]) + "\n")
    saves = run / "saves"
    if saves.is_symlink():
        saves.unlink()
    elif saves.exists():
        shutil.rmtree(saves)
    if with_world:
        saves.mkdir()
        shutil.copytree(TEMPLATE, saves / "TestWorld")
    return run


def wait_if_paused() -> None:
    while PAUSE.is_set() and not STOP.is_set():
        time.sleep(0.3)


def stop_gradle(proc, grace: float = 40.0) -> None:
    """A build that is killed hard leaves Loom's cache "disowned": the next run downloads everything again (and when the connection
    is poor, it fails again, and so on). So gradle is asked to stop and given time to close its caches; only then it is killed."""
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except OSError:
        return
    deadline = time.time() + grace
    while time.time() < deadline and proc.poll() is None:
        time.sleep(0.5)
    if proc.poll() is None:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except OSError:
            pass


def stop_all() -> None:
    """Instantly: no new run starts and every running game is killed; gradle builds are stopped gently (in the background)."""
    STOP.set()
    PAUSE.clear()
    with _slot_lock:
        procs = list(PROCS)
        builders = [p for p in procs if p in BUILDERS]
    for proc in procs:
        if proc in builders:
            threading.Thread(target=stop_gradle, args=(proc,), daemon=True).start()
            continue
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except OSError:
            pass
    # the game itself runs in a gradle daemon / child that left the process group: find it by its work dir
    subprocess.run(["pkill", "-9", "-f", f"autotest.owner={os.getpid()}"], check=False)


GPU = False                         # --gpu: the games draw on the graphics card (VirtualGL: vglrun) and show up on the virtual screen
EXTRA_MODS = os.environ.get("AUTOTEST_EXTRA_MODS", "")      # other mods put into the games: "sodium:<version>,iris:<version>" (Modrinth)
FAST_MODS = False                   # --fast-mods: ModernFix in the test runs where a version of it exists


def run_attempt(index: int, loader: str, with_world: bool, offline: bool = True, fast: bool | None = None) -> tuple[bool, str]:
    fast_mods = FAST_MODS if fast is None else fast
    workdir = b.sync_workdir(index)
    run = prepare_run_dir(workdir, loader, with_world)
    log = SCRATCH / f"{b.read_props()['archives_name']}-{b.MINECRAFT_VERSIONS[index]}-{loader}.log"
    jdk = f"/usr/lib/jvm/java-{b.java_version(index)}-temurin"
    task = "runWithDistantHorizons" if run_dir_name(loader) == "run-dh" else "runClient"
    gradle = ["sh", "gradlew", "--console=plain",
              # gradle only builds here, the game is a process of its own: 3 GB are plenty (the project default is 8 GB per
              # gradle) and an idle daemon goes away after a minute instead of holding its memory for hours
              "-Dorg.gradle.jvmargs=-Xmx3g", "-Dorg.gradle.daemon.idletimeout=60000"] + (["--offline"] if offline else []) + [
        f":{loader}:dumpRun", f"-Pversion_index={index}", f"-PMC_VER={index}", f"-Porg.gradle.java.home={jdk}", "-Pautotest=1"
    ] + (["-Pfastmods=1"] if fast_mods else []) + ([f"-Pextramods={EXTRA_MODS}"] if EXTRA_MODS else [])

    # gradle builds the mod and writes the command of the run task once per state of the sources; every run of the same
    # sources after that starts the game directly, without gradle
    state = workdir / loader / "build"
    info_file, stamp_file = state / f"run-{task}.json", state / f"run-{task}.stamp"
    stamp = f"{SOURCES_HASH}|{fast_mods}|{index}|{EXTRA_MODS}"
    fresh = bool(SOURCES_HASH) and info_file.exists() and stamp_file.exists() and stamp_file.read_text() == stamp
    # build.py builds in the same directory without the test hook: classes made after the stamp are not those of the test
    if fresh:
        made = [f.stat().st_mtime for f in list((workdir / "common" / "build").glob("libs/*.jar")) + list((workdir / "common" / "build" / "classes").rglob("AutoTest.class"))]
        if made and max(made) > stamp_file.stat().st_mtime + 1:
            fresh = False
    # the Distant Horizons setup keeps its own mods (installed by gradle for the version): they have to be those of this version
    mods_mark = state / ".." / "run-dh" / "mods" / ".checked-for"
    if task == "runWithDistantHorizons" and not (mods_mark.exists() and mods_mark.read_text() == b.MINECRAFT_VERSIONS[index]):
        fresh = False
    log.write_text("")
    label = f"{b.MINECRAFT_VERSIONS[index]}/{loader}"
    if not fresh:
        if ON_PHASE:
            ON_PHASE(label, "build")
        stamp_file.unlink(missing_ok=True)
        # gradle builds under a limit of their own: the builds of the next versions go on while the games of the ones before them run
        while not BUILD_SLOTS.acquire(timeout=1):
            if STOP.is_set():
                return False, "stopped"
        try:
            with log.open("a") as out:
                builder = subprocess.Popen(gradle, cwd=workdir, stdout=out, stderr=subprocess.STDOUT, start_new_session=True, env=dict(os.environ))
                with _slot_lock:
                    PROCS.add(builder)
                    BUILDERS.add(builder)
                while builder.poll() is None:
                    if STOP.wait(2):
                        stop_gradle(builder)
                        return False, "stopped"
                with _slot_lock:
                    PROCS.discard(builder)
                    BUILDERS.discard(builder)
        finally:
            BUILD_SLOTS.release()
        if builder.returncode != 0 or not info_file.exists():
            return False, f"no result, gradle exit {builder.returncode}"
        stamp_file.write_text(stamp)
        if task == "runWithDistantHorizons":
            # the mark says "the mods of this version are in": not when the download of Distant Horizons failed (proxy down, no build)
            has_dh = any("distanthorizons" in j.name.lower() for j in mods_mark.parent.glob("*.jar"))
            if has_dh:
                mods_mark.parent.mkdir(parents=True, exist_ok=True)
                mods_mark.write_text(b.MINECRAFT_VERSIONS[index])
            else:
                print(f"  note: no Distant Horizons for {b.MINECRAFT_VERSIONS[index]} in the setup (no build or the download failed), the strike is not drawn", flush=True)
    info = json.loads(info_file.read_text())
    # a game slot is a place for one game (and its virtual screen): the limit of games that run at once
    display = None
    while True:
        try:
            display = GAME_SLOTS.get(timeout=1)
            break
        except queue.Empty:
            if STOP.is_set():
                return False, "stopped"
    try:
        return _play(index, loader, info, run, log, display, label)
    finally:
        GAME_SLOTS.put(display)


def _play(index: int, loader: str, info: dict, run: Path, log: Path, display, label: str) -> tuple[bool, str]:
    _local.display = display
    if ON_PHASE:
        ON_PHASE(label, "game", display)
    cmd = [info["commandLine"][0], f"-Dautotest.owner={os.getpid()}"] + info["commandLine"][1:]
    if os.environ.get("AUTOTEST_BACKEND"):                                  # --graphicsBackend opengl | vulkan (26.3+)
        cmd += ["--graphicsBackend", os.environ["AUTOTEST_BACKEND"]]
    env = game_env()
    env.update(info.get("extraEnvironment") or {})      # what Loom puts into the environment of the run (Forge, NeoForge)
    if GPU and shutil.which("vglrun"):
        # VirtualGL: OpenGL calls of the game go to the graphics card through EGL (no X server of the card is needed) and the
        # finished picture is copied to the virtual screen
        cmd = ["vglrun", "-d", "egl"] + cmd

    for old in (run / "screenshots").glob("*.png") if (run / "screenshots").exists() else []:
        old.unlink()             # the pictures of the run before: only those of this run are looked at
    with log.open("a") as out:
        proc = subprocess.Popen(cmd, cwd=run, stdout=out, stderr=subprocess.STDOUT, start_new_session=True, env=env)
        with _slot_lock:
            PROCS.add(proc)
        # the result is in the log; some versions then hang while closing (narrator library), so the game gets
        # 40 seconds to quit by itself and is killed after that
        deadline = time.time() + RUN_TIMEOUT
        done_at = None
        no_hook = None
        while proc.poll() is None:
            if STOP.wait(2):
                break
            if done_at is None:
                text = log.read_text(errors="replace")
                if re.search(r"AUTOTEST (PASSED|FAILED)", text):
                    done_at = time.time()
                elif "AUTOTEST registered" not in text and "textures/atlas" in text:
                    # the game is at its menu and the hook never spoke: it is not in these classes, waiting for the timeout says nothing
                    no_hook = no_hook or time.time()
                    if time.time() - no_hook > 60:
                        os.killpg(proc.pid, signal.SIGKILL)
                        return False, "the test hook did not start (classes built without -Pautotest?)"
            if (done_at and time.time() - done_at > 40) or time.time() > deadline or STOP.is_set():
                os.killpg(proc.pid, signal.SIGKILL)
                if not done_at:
                    return False, "timeout"
                break

    with _slot_lock:
        PROCS.discard(proc)
    if STOP.is_set():
        return False, "stopped"

    text = log.read_text(errors="replace")
    # the pictures of this run, oldest first, are the "AUTOTEST screenshot <name>" lines of the log in their order: the newest versions
    # of the game name the files by the time, and a test of a suite has its name in front (<test>__<name>)
    shots = sorted((run / "screenshots").glob("*.png"), key=lambda f: f.stat().st_mtime) if (run / "screenshots").exists() else []
    names = re.findall(r"AUTOTEST screenshot (\S+)", text)
    (SCRATCH / "shots").mkdir(exist_ok=True)
    for i, shot in enumerate(shots):
        name = names[i] + ".png" if len(names) == len(shots) else shot.name
        shutil.copy2(shot, SCRATCH / "shots" / f"{b.MINECRAFT_VERSIONS[index]}-{loader}-{name}")
    if SUITE:
        # what every test of the suite did; a test the game never came to (a crash, the time ran out) did not run
        done = dict(re.findall(r"AUTOTEST TEST (\S+) (PASSED|FAILED[^\n]*)", text))
        begun = re.findall(r"AUTOTEST TEST (\S+) BEGIN", text)
        tests = {}
        for name in SUITE:
            if name in done:
                tests[name] = (done[name] == "PASSED", "ok" if done[name] == "PASSED" else done[name][7:].strip()[:300])
            elif name in begun:
                tests[name] = (False, "the game ended in this test (crash or the time ran out)")
            else:
                tests[name] = (False, "not run: the game ended before it")
        with _slot_lock:
            SUITE_RESULTS[label] = tests
            (SCRATCH / "results-tests.json").write_text(json.dumps(SUITE_RESULTS, indent=1))
    crashes = list((run / "crash-reports").glob("*.txt")) if (run / "crash-reports").exists() else []
    # vanilla's own watchdog after "AUTOTEST PASSED": the game closed late (a narrator library is missing), not a crash of the mod
    crashes = [c for c in crashes if "Client shutdown from post-main" not in c.read_text(errors="ignore")[:600]]
    if crashes:
        return False, f"crash report {crashes[0].name}"
    if "AUTOTEST PASSED" in text:
        problem = check_results(text, b.MINECRAFT_VERSIONS[index], loader)
        return (False, "check failed: " + problem) if problem else (True, "ok")
    if "AUTOTEST FAILED" in text:
        return False, [l for l in text.splitlines() if "AUTOTEST FAILED" in l][0]
    return False, f"no result, gradle exit {proc.returncode}"


NETWORK = ("Connection reset", "SocketException", "timed out", "UnknownHostException", "daemon has been stopped", "daemon disappeared", "DownloadException", "Failed to download", "Premature EOF", "Connection refused", "отказано", "Could not GET", "Could not HEAD")


def run_one(index: int, loader: str, with_world: bool) -> tuple[bool, str]:
    """The proxy drops now and then: a run that died on a download is repeated, a crash of the game never is."""
    offline = True      # nothing is downloaded when everything is cached: gradle does not ask the flaky proxy anything
    fast = FAST_MODS
    for attempt in range(1, 5):
        wait_if_paused()
        if STOP.is_set():
            return False, "stopped"
        ok, why = run_attempt(index, loader, with_world, offline, fast)
        if ok or STOP.is_set():
            return ok, why
        log = SCRATCH / f"{b.read_props()['archives_name']}-{b.MINECRAFT_VERSIONS[index]}-{loader}.log"
        text = log.read_text(errors="replace")
        if offline and ("offline mode" in text or "No cached version" in text):
            offline = False     # something is not cached yet: go online for this version
            continue
        if fast and why.startswith("no result") and "modLocalRuntime" in text:
            fast = False        # the start-up mods could not be fetched (the proxy is down): the test is worth more than they are
            continue
        # a crash of the game is final; a hang or a download that died is tried again (once more for hangs)
        if why.startswith("crash"):
            return ok, why
        if why == "timeout" and attempt >= 2:
            return ok, why
        if why.startswith("no result") and not any(marker in text for marker in NETWORK):
            return ok, why
        time.sleep(15 * attempt)          # the proxy comes back after a while
    return ok, why



TEST_FILE = b.ROOT / "autotest.yml"
TEST_TEXT = ""                      # the test file that goes next to every game


def load_test(path) -> dict:
    """autotest.yml: `key: value` lines at the top (name, world, timeout) and `- step: value` lines (see AutoTest.java)."""
    global TEST_TEXT, RUN_TIMEOUT
    SUITE.clear()
    SUITE_RESULTS.clear()
    RUN_TIMEOUT = 420
    if path and "," in str(path):
        # a suite: the steps of every file one after another, each begun by a "- test: <name>" step. One start of the game for all of them.
        lines, total = ["name: suite", "world: true", "steps:"], 240
        for one in [p.strip() for p in str(path).split(",") if p.strip()]:
            file = Path(one)
            text = file.read_text()
            SUITE.append(file.stem)
            lines.append(f"  - test: {file.stem}")
            lines += [l for l in text.splitlines() if l.strip().startswith("- ")]
            limit = re.search(r"^timeout:\s*(\d+)", text, re.M)
            total += int(limit.group(1)) if limit else 150
        TEST_TEXT = "\n".join(lines) + "\n"
        RUN_TIMEOUT = total
        TEST_META.clear()
        TEST_META.update({"name": "suite", "world": True})
        return dict(TEST_META)
    file = Path(path) if path else TEST_FILE
    if not file.exists() and not path:
        found = sorted((b.ROOT / "tests").glob("*.yml"))        # no autotest.yml: the first test of tests/
        file = found[0] if found else file
    if not file.exists():
        return {"name": "main menu", "world": False}
    TEST_TEXT = file.read_text()
    meta = {"name": file.stem, "world": True}
    for line in TEST_TEXT.splitlines():
        line = line.split("#", 1)[0].rstrip()
        if line and not line.startswith(("-", " ")) and ":" in line and not line.startswith("steps"):
            key, _, value = line.partition(":")
            meta[key.strip()] = value.strip()
    meta["world"] = str(meta["world"]).lower() not in ("false", "no", "0")
    TEST_META.clear()
    TEST_META.update(meta)
    if "timeout" in meta:
        RUN_TIMEOUT = int(meta["timeout"])
    return meta


def check_results(text: str, version: str, loader: str) -> str | None:
    """What the test file asks for besides "the game did not crash":

        max-mod-tick-ms: 30             the mod's own work in one tick (profile steps) stays under this
        compare-bright: after before 150   the screenshot "after" has at least 150 new bright pixels compared with "before"

    Returns what is wrong, or None. A screenshot that is not there (the newest versions name them by time) skips its check."""
    meta = TEST_META
    if "max-mod-tick-ms" in meta:
        limit = float(meta["max-mod-tick-ms"])
        for match in re.finditer(r"AUTOTEST PROFILE (.*?): .*?mod tick avg=[\d.]+ ms worst=([\d.]+) ms", text):
            if float(match.group(2)) > limit:
                return f"the mod used {match.group(2)} ms in one tick during '{match.group(1)}' (limit {limit:g})"
    # the bolts are drawn by Distant Horizons: a run without it (no build for the version, the download failed) has none to see
    skipping_bolts = "could not install distanthorizons" in text or "has no build for Minecraft" in text and "distanthorizons" in text \
        or ("Distant Horizons" not in text and "DH will render" not in text and "(LodRenderer)" not in text)
    if "compare-bright" in meta and not skipping_bolts:
        try:
            after, before, minimum = meta["compare-bright"].split()
            from PIL import Image

            def load(name):
                path = SCRATCH / "shots" / f"{version}-{loader}-{name}.png"
                return Image.open(path).convert("RGB") if path.exists() else None

            picture, base = load(after), load(before)
            if picture is not None and base is not None and picture.size == base.size:
                # what is new and bright: a bolt is pale and bright where the picture before had sky (the moon and the clouds are in both)
                new = 0
                p1, p0 = picture.load(), base.load()
                width, height = picture.size
                for x in range(0, width):
                    for y in range(0, int(height * 0.9)):
                        if sum(p1[x, y]) > 420 and sum(p0[x, y]) < 330:
                            new += 1
                if new < int(minimum):
                    return f"no bolt on the screenshot: {new} new bright pixels (needs {minimum})"
        except Exception as error:                              # a broken check must not fail a good run
            print(f"compare-bright could not run: {error}", flush=True)
    return None


def game_env() -> dict:
    env = dict(os.environ)
    display = getattr(_local, "display", None)
    # software OpenGL (llvmpipe) uses every core of the machine per game: with several games at once they only get in each
    # other's way, so each game gets its share
    env["LP_NUM_THREADS"] = str(max(2, (os.cpu_count() or 8) // max(PARALLEL_SCREENS or 1, 1)))
    if display:
        env["DISPLAY"] = display
        # GLFW 3.4 picks Wayland when a compositor is around, whatever DISPLAY says: the game would open on the desktop
        for name in ("WAYLAND_DISPLAY", "WAYLAND_SOCKET"):
            env.pop(name, None)
        env["XDG_SESSION_TYPE"] = "x11"
        env["GDK_BACKEND"] = "x11"
        env["SDL_VIDEODRIVER"] = "x11"
    return env


def start_xvfb(count: int) -> list:
    """A virtual screen per parallel game: windows do not fight over focus and nothing flickers on the desktop."""
    procs = []
    number = 99
    for n in range(count):
        number += 1
        while os.path.exists(f"/tmp/.X11-unix/X{number}") or os.path.exists(f"/tmp/.X{number}-lock"):
            number += 1
        procs.append(subprocess.Popen(["Xvfb", f":{number}", "-screen", "0", SIZE + "x24"],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        DISPLAYS[n] = f":{number}"
    global PARALLEL_SCREENS
    PARALLEL_SCREENS = count
    deadline = time.time() + 5
    while time.time() < deadline and not all(os.path.exists(f"/tmp/.X11-unix/X{d[1:]}") for d in DISPLAYS.values()):
        time.sleep(0.05)
    return procs


def source_hash(with_test: bool = True) -> str:
    """Everything that changes the game: sources, resources and build files (not build output, logs, work dirs)."""
    digest = hashlib.sha256()
    if with_test:
        digest.update(TEST_TEXT.encode())      # another test is another result
    skip = {".git", ".idea", ".gradle", ".build-work", "build", "buildAllJars", "build-logs", "run", "__pycache__", "legacy"}
    # walk without going into the folders that are skipped (.build-work alone has hundreds of thousands of files)
    for folder, dirs, files in os.walk(b.ROOT):
        dirs[:] = sorted(d for d in dirs if d not in skip)
        for name in sorted(files):
            path = Path(folder) / name
            if path.suffix in (".log", ".png", ".md") or name in ("credentials.properties", "build.properties") or "autotest" in name:
                continue
            digest.update(str(path.relative_to(b.ROOT)).encode())
            digest.update(path.read_bytes())
    return digest.hexdigest()


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except Exception:
        return {}


def make_template(index: int = 2, loader: str = "fabric") -> None:
    """A world for all versions: a dedicated server of the oldest version generates it, newer clients upgrade it."""
    if (TEMPLATE / "level.dat").exists():
        return
    SCRATCH.mkdir(parents=True, exist_ok=True)
    workdir = b.sync_workdir(index)
    run = workdir / loader / "run"
    run.mkdir(parents=True, exist_ok=True)
    (run / "eula.txt").write_text("eula=true\n")
    (run / "server.properties").write_text("online-mode=false\nlevel-name=world\nlevel-type=flat\ngenerate-structures=false\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\ngamemode=creative\nforce-gamemode=true\nallow-flight=true\nview-distance=4\nspawn-protection=0\n")
    if (run / "world").exists():
        shutil.rmtree(run / "world")
    jdk = f"/usr/lib/jvm/java-{b.java_version(index)}-temurin"
    cmd = ["sh", "gradlew", "--console=plain", f":{loader}:runServer", f"-Pversion_index={index}", f"-PMC_VER={index}",
           f"-Porg.gradle.java.home={jdk}", "--args=nogui"]
    log = (SCRATCH / "template-server.log").open("w")
    proc = subprocess.Popen(cmd, cwd=workdir, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True,
                            start_new_session=True)
    deadline = time.time() + 600
    while time.time() < deadline:
        if 'Done (' in (SCRATCH / "template-server.log").read_text(errors="replace"):
            break
        time.sleep(2)
    time.sleep(3)
    try:
        proc.stdin.write("stop\n")
        proc.stdin.flush()
        proc.wait(timeout=90)
    except Exception:
        os.killpg(proc.pid, signal.SIGTERM)
        time.sleep(10)
    if not (run / "world" / "level.dat").exists():
        sys.exit("the template world was not generated, see " + str(SCRATCH / "template-server.log"))
    shutil.copytree(run / "world", TEMPLATE)


def run_group(index: int, loaders: list, with_world: bool) -> list:
    """The loaders of one minecraft version share a work dir, so they run one after another."""
    results = []
    for loader in loaders:
        wait_if_paused()
        if STOP.is_set():
            break
        start = time.time()
        label = f"{b.MINECRAFT_VERSIONS[index]}/{loader}"
        if ON_START:
            ON_START(label, None)           # the screen comes with the game slot, when the game starts
        ok, why = run_one(index, loader, with_world)
        if ON_FINISH:
            ON_FINISH(label, ok, why)
        results.append((index, loader, ok, why, time.time() - start))
    return results


def run_jobs(jobs: list, with_world: bool, parallel: int, results: dict, hashes: dict, cache: dict) -> None:
    groups: dict[int, list] = {}
    for job in jobs:
        groups.setdefault(job.index, []).append(job.loader)
    labels = {(j.index, j.loader): j.label for j in jobs}
    # more workers than games: while some versions play, the next ones are built (the two limits are separate)
    with concurrent.futures.ThreadPoolExecutor(max_workers=parallel + BUILD_LIMIT[0]) as pool:
        futures = [pool.submit(run_group, index, loaders, with_world) for index, loaders in groups.items()]
        for future in concurrent.futures.as_completed(futures):
            for index, loader, ok, why, seconds in future.result():
                label = labels[(index, loader)]
                results[label] = (ok, why)
                if ok:
                    cache[label] = hashes["now"]
                else:
                    cache.pop(label, None)
                print(f"{'PASS' if ok else 'FAIL'}  {label:<22} {why}  ({seconds:.0f}s)", flush=True)
                (SCRATCH / "results.json").write_text(json.dumps(results, indent=1))
                CACHE_FILE.write_text(json.dumps(cache, indent=1))


BUILD_LIMIT = [2]


def stop_everything() -> int:
    """Kills the test games (-Dautotest.owner), the other runners (autotest.py, autotest-grid.py) and the virtual screens they made.
    Reads /proc itself: `pkill -f` with such a pattern also finds the shell that runs it."""
    me, found = os.getpid(), {"game": [], "runner": [], "screen": []}
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit() or int(entry.name) == me:
            continue
        try:
            words = (entry / "cmdline").read_bytes().split(b"\0")
        except OSError:
            continue
        words = [w.decode(errors="replace") for w in words if w]
        if not words:
            continue
        name = Path(words[0]).name
        if any(w.startswith("-Dautotest.owner=") for w in words):
            found["game"].append(int(entry.name))
        elif name.startswith("python") and any(Path(w).name in ("autotest.py", "autotest-grid.py") for w in words[1:3]):
            found["runner"].append(int(entry.name))
        elif name == "Xvfb" and len(words) > 1 and words[1][1:].isdigit() and int(words[1][1:]) >= 99:
            found["screen"].append(int(entry.name))
    for kind, sig in (("runner", signal.SIGTERM), ("game", signal.SIGKILL), ("screen", signal.SIGTERM)):
        for pid in found[kind]:
            try:
                os.kill(pid, sig)
            except OSError:
                pass
    print("stopped: " + ", ".join(f"{len(v)} {k}s" for k, v in found.items()))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    b.add_common_args(parser)
    parser.add_argument("test", nargs="?", help="the test file (default: autotest.yml next to this script)")
    parser.add_argument("--menu", action="store_true", help="the main menu is enough, no world")
    parser.add_argument("--xvfb", action="store_true", help="one virtual screen per parallel game (needs Xvfb)")
    parser.add_argument("--builds", type=int, default=2, help="gradle builds at once (the games at once are -j)")
    parser.add_argument("--gpu", action="store_true", help="draw the games on the graphics card (needs VirtualGL: pacman -S virtualgl)")
    parser.add_argument("--fast-mods", action="store_true", help="put ModernFix into the games (starts sooner, where a version of it exists)")
    parser.add_argument("--force", action="store_true", help="run versions that already passed with these sources, too")
    parser.add_argument("--newest-first", action="store_true", help="the newest Minecraft version first, the oldest last")
    parser.add_argument("--stop", action="store_true", help="kill every test game, runner and virtual screen that is still there, and leave")
    args = parser.parse_args(argv)
    if args.stop:
        return stop_everything()
    global FAST_MODS, GPU, BUILD_SLOTS
    FAST_MODS = args.fast_mods
    GPU = args.gpu
    if args.gpu and not shutil.which('vglrun'):
        print('--gpu: vglrun was not found (pacman -S virtualgl), the games draw on the processor', flush=True)
    STOP.clear()
    PAUSE.clear()
    BUILD_LIMIT[0] = max(1, args.builds)
    BUILD_SLOTS = threading.Semaphore(BUILD_LIMIT[0])
    jobs = b.select_jobs(args, lambda loader: [])
    test = load_test(args.test)
    with_world = test["world"] and not args.menu
    parallel = max(1, args.jobs)

    SCRATCH.mkdir(parents=True, exist_ok=True)
    xvfbs = []
    if args.xvfb:
        if not shutil.which("Xvfb"):
            sys.exit("Xvfb is not installed (pacman -S xorg-server-xvfb)")
        xvfbs = start_xvfb(parallel)
    # the slots of the games that run at once: each holds the virtual screen of its game (none without --xvfb)
    global PARALLEL_SCREENS
    PARALLEL_SCREENS = parallel
    while not GAME_SLOTS.empty():
        GAME_SLOTS.get_nowait()
    for i in range(parallel):
        GAME_SLOTS.put(DISPLAYS.get(i) if args.xvfb else None)

    # combinations that cannot run at all, and the ones that already passed with exactly these sources
    skip = load_json(SKIP_FILE)
    cache = load_json(CACHE_FILE)
    hashes = {"now": source_hash()}
    global SOURCES_HASH
    SOURCES_HASH = source_hash(with_test=False)
    todo, skipped, cached = [], [], []
    for job in jobs:
        if job.label in skip:
            skipped.append(job)
        elif not args.force and cache.get(job.label) == hashes["now"]:
            cached.append(job)
        else:
            todo.append(job)
    for job in skipped:
        print(f"SKIP  {job.label:<22} {skip[job.label]}", flush=True)
    for job in cached:
        print(f"PASS  {job.label:<22} unchanged since the last passed run", flush=True)

    if ON_PLAN:
        ON_PLAN([j.label for j in todo], [j.label for j in skipped], [j.label for j in cached])
    if todo and with_world:
        if ON_STATUS and not (TEMPLATE / "level.dat").exists():
            ON_STATUS("preparing the flat test world (first run only, a few minutes)")
        make_template()
        if ON_STATUS:
            ON_STATUS("running")

    results = {job.label: (True, "cached") for job in cached}
    # the boundary versions are first in the queue, but nothing waits for them: all the versions are one queue, so that the builds
    # (--builds) and the games (-j) are always as many as the limits allow
    todo.sort(key=lambda j: b.MINECRAFT_VERSIONS[j.index] not in SMOKE)
    if args.newest_first:
        todo.sort(key=lambda j: -j.index)
    if todo:
        run_jobs(todo, with_world, parallel, results, hashes, cache)

    for proc in xvfbs:
        proc.terminate()
    failed = [k for k, (ok, _) in results.items() if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed" + (f", {len(skipped)} skipped" if skipped else ""))
    for k in failed:
        print("  FAILED", k, results[k][1])
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
