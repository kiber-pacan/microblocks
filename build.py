#!/usr/bin/env python3
"""Build every supported minecraft version / loader and collect jars in buildAllJars/.

    python build.py                       # everything
    python build.py -v 1.20.1,1.21.1      # selected versions
    python build.py -l fabric -j 4        # only fabric, 4 parallel builds
    python build.py --plain               # no TUI (CI / logs)
"""
import argparse
import sys

import buildlib as b


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    b.add_common_args(parser)
    parser.add_argument("--rebuild", action="store_true", help="build the jars again even when the sources did not change")
    args = parser.parse_args()
    b.REBUILD = args.rebuild

    if args.clean and b.WORK.exists():
        import shutil
        shutil.rmtree(b.WORK)

    props = b.read_props()
    jobs = b.select_jobs(args, lambda loader: [])
    print(f"{props['archives_name']} {props['mod_version']}: building {len(jobs)} jar(s) with {args.jobs} worker(s)")
    return b.execute(jobs, props, args.jobs, args.plain)


if __name__ == "__main__":
    sys.exit(main())
