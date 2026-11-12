#!/usr/bin/env python3
"""Puts the jars of akilib (../akilib/buildAllJars) into libs/akilib, where the build of this mod reads them.

    python tools/akilib_sync.py                 # copy what is built
    python tools/akilib_sync.py --build         # build akilib first (all versions)
    python tools/akilib_sync.py --build -v 26.3,1.21.11 -l fabric
"""
import argparse
import filecmp
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AKILIB = ROOT.parent / "akilib"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("-v", "--versions")
    parser.add_argument("-l", "--loaders")
    parser.add_argument("-j", "--jobs", default="3")
    args = parser.parse_args()
    if args.build:
        cmd = [sys.executable, "build.py", "--plain", "-j", args.jobs] + (["-v", args.versions] if args.versions else []) + (["-l", args.loaders] if args.loaders else [])
        if subprocess.run(cmd, cwd=AKILIB).returncode != 0:
            print("the build of akilib failed, nothing was copied")
            return 1
    target = ROOT / "libs" / "akilib"
    target.mkdir(parents=True, exist_ok=True)
    jars = sorted((AKILIB / "buildAllJars").glob("akilib-mc*.jar"))
    changed = 0
    for jar in jars:
        to = target / jar.name
        if not to.exists() or not filecmp.cmp(jar, to, shallow=False):
            shutil.copy2(jar, to)
            changed += 1
    print(f"{len(jars)} jars of akilib, {changed} new or changed -> {target}")
    return 0 if jars else 1


if __name__ == "__main__":
    sys.exit(main())
