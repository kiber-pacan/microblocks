#!/usr/bin/env python3
"""Presses a key on an X display again and again (for the tests on a virtual screen): presskey.py :99 End 60"""
import sys, time
from Xlib import display, X, XK
from Xlib.ext import xtest
d = display.Display(sys.argv[1])
code = int(sys.argv[2]) if sys.argv[2].isdigit() else d.keysym_to_keycode(XK.string_to_keysym(sys.argv[2]))      # a number is the keycode itself (End is 115: by name the keypad one is found first)
end = time.time() + float(sys.argv[3])
while time.time() < end:
    xtest.fake_input(d, X.KeyPress, code); d.sync(); time.sleep(0.15)
    xtest.fake_input(d, X.KeyRelease, code); d.sync(); time.sleep(0.85)
