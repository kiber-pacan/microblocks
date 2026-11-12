#!/usr/bin/env python3
"""Build and publish every supported minecraft version / loader to Modrinth and/or CurseForge.

Tokens are read from credentials.properties (modrinthToken, curseToken).
Publishing is public and cannot be undone from here, so the script asks for confirmation
(use --yes to skip it).

    python build-publish.py --dry-run                 # show what would be published
    python build-publish.py                           # modrinth + curseforge, asks first
    python build-publish.py --only modrinth -v 1.21.1 # one version, one site
    python build-publish.py --show-version            # which version is set now
    python build-publish.py --set-version 1.6.0       # set it (gradle.properties) and publish with it, no changelog
"""
import argparse
import sys

import buildlib as b


def read_credentials() -> dict[str, str]:
    path = b.ROOT / "credentials.properties"
    creds: dict[str, str] = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, _, value = line.partition("=")
                creds[key.strip()] = value.strip()
    return creds


def set_version(version: str) -> None:
    """The version of every jar and every upload: mod_version in gradle.properties."""
    import re
    version = version.strip()
    if not re.fullmatch(r"\d+(\.\d+){1,3}([-+][0-9A-Za-z.]+)?", version):
        sys.exit(f"not a version: {version}")
    path = b.ROOT / "gradle.properties"
    text = path.read_text()
    new, count = re.subn(r"(?m)^(\s*mod_version\s*=\s*).*$", lambda m: m.group(1) + version, text)
    if not count:
        sys.exit("mod_version is not in gradle.properties")
    path.write_text(new)
    print(f"mod version set to {version}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    b.add_common_args(parser)
    parser.add_argument("--only", choices=["modrinth", "curseforge"], help="publish to a single site")
    parser.add_argument("--dry-run", action="store_true", help="print the plan, build and publish nothing")
    parser.add_argument("-y", "--yes", action="store_true", help="do not ask for confirmation")
    parser.add_argument("--show-version", action="store_true", help="print the current mod version and exit")
    parser.add_argument("--set-version-only", metavar="X.Y.Z", help="set the mod version in gradle.properties and exit (nothing is built)")
    parser.add_argument("--set-version", metavar="X.Y.Z", help="set the mod version (gradle.properties) before building, so every jar and upload has it")
    args = parser.parse_args()

    if args.show_version:
        print(b.read_props()["mod_version"])
        return 0
    if args.set_version_only:
        set_version(args.set_version_only)
        return 0
    if args.set_version:
        set_version(args.set_version)

    creds = read_credentials()
    sites = [args.only] if args.only else ["modrinth", "curseforge"]
    key = {"modrinth": "modrinthToken", "curseforge": "curseToken"}
    for site in list(sites):
        if not creds.get(key[site]):
            print(f"WARNING: no {key[site]} in credentials.properties, skipping {site}", file=sys.stderr)
            sites.remove(site)
    if not sites:
        sys.exit("no publishing targets available")

    def extra(loader: str) -> list[str]:
        tasks = []
        if "modrinth" in sites:
            tasks.append(f":{loader}:modrinth")
        if "curseforge" in sites:
            tasks.append(f":{loader}:publishCurseForge")
        return tasks

    props = b.read_props()
    jobs = b.select_jobs(args, extra)

    # what the tests could not start (autotest-skip.json) is not published either: nobody can run it
    skip_file = b.ROOT / "autotest-skip.json"
    if skip_file.exists():
        import json
        skipped = json.loads(skip_file.read_text())
        for job in [j for j in jobs if j.label in skipped]:
            print(f"  skipping {job.label}: {skipped[job.label]}")
        jobs = [j for j in jobs if j.label not in skipped]
        if not jobs:
            sys.exit("nothing to publish after the skip list")

    print(f"{props['archives_name']} {props['mod_version']} -> {', '.join(sites)}")
    for job in jobs:
        print(f"  {job.label:<22} gradle {' '.join(job.tasks)}")

    if args.dry_run:
        return 0

    if not args.yes:
        answer = input(f"\nPublish {len(jobs)} file(s) publicly to {' and '.join(sites)}? [y/N] ")
        if answer.strip().lower() not in ("y", "yes"):
            print("aborted")
            return 1

    if args.clean and b.WORK.exists():
        import shutil
        shutil.rmtree(b.WORK)

    return b.execute(jobs, props, args.jobs, args.plain)


if __name__ == "__main__":
    sys.exit(main())
