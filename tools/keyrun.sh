#!/bin/bash
# keyrun.sh <version> <loader> <test> <keycode> : runs one test on a virtual screen and presses the key once a second while it runs
# (a real key event of the X server, e.g. 115 = End, 27 = R). The result is the last lines of the runner.
cd "$(dirname "$0")/.."
out=$(mktemp)
(python3 autotest.py tests/$3.yml --plain --xvfb --gpu -j 1 --builds 1 --force --fast-mods -v $1 -l $2 > $out 2>&1; echo finished >> $out) &
for i in $(seq 1 120); do D=$(pgrep -a Xvfb | grep -o " :[0-9]*" | tr -d ' ' | tail -1); [ -n "$D" ] && break; sleep 1; done
sleep 2
while ! grep -q finished $out 2>/dev/null; do python3 tools/presskey.py $D $4 1 2>/dev/null || sleep 1; done
grep -E "PASS|FAIL|passed" $out; rm -f $out
