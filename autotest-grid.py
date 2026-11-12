#!/usr/bin/env python3
"""All test runs in one window: every game renders on its own virtual screen and shows up as a tile.

    python autotest-grid.py                       # pick the versions in the list, Start, Stop
    python autotest-grid.py -j 6 --menu           # ipla: only the main menu / settings screen

A tile is grey while the version waits, shows the live picture of the game while it runs and turns green (passed) or red
(failed, with the reason) with the version written over it. Everything is in the bar at the bottom; the Versions button opens a list on top of the window (click, Ctrl+click, Shift+click).
Start runs the chosen ones, Stop kills every running game instantly. The options are the ones of autotest.py.
Needs Xvfb (pacman -S xorg-server-xvfb): the games do not open a single window on your desktop. Close the window to stop.
"""
import os
import shutil
import subprocess
import time
import sys
import threading
import tkinter as tk
from io import BytesIO

from PIL import Image, ImageDraw, ImageFont, ImageTk

import autotest

TILE_W, TILE_H = 480, 270            # the virtual screen of one game: 1920/4 x 1080/4
GREY, GREEN, RED = (60, 60, 60), (30, 140, 60), (170, 40, 40)
BACKGROUND = (24, 24, 24)
GREEN_LINE, RED_LINE = (60, 210, 90), (235, 70, 70)
UI_BG, UI_FIELD, UI_FG, UI_SELECT = "#181818", "#232323", "#d8d8d8", "#3a6ea5"


class Tile:
    def __init__(self, label: str):
        self.label = label
        self.state = "queued"        # queued | running | pass | fail | skipped | cached
        self.display = None
        self.why = ""
        self.image = None            # last frame of the game (PIL)
        self.started = 0.0
        self.finished = 0.0
        self.game_started = 0.0      # when gradle was done and the game itself started
        self.built = False           # a gradle build was part of this run
        self.stage = "waiting"       # what the game does now, read from its log
        self.shot = None             # the screenshot the test took (PIL), shown instead of the live frame when the run is over


def fmt(seconds: float) -> str:
    seconds = max(int(seconds), 0)
    return f"{seconds // 60}:{seconds % 60:02d}"


def font(size: int):
    for name in ("DejaVuSans-Bold.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


class LogViewer(tk.Frame):
    """One run in the whole window: its log, its last screenshot or the picture of the game (all refreshed), Esc closes it."""

    def __init__(self, root, grid, bg, button):
        super().__init__(root, bg=bg)
        self.root, self.grid, self.label, self.mode = root, grid, None, "log"
        top = tk.Frame(self, bg=bg)
        top.pack(fill="x", padx=12, pady=(10, 0))
        self.title = tk.Label(top, anchor="w", bg=bg, fg=UI_FG, font=("DejaVu Sans", 11, "bold"))
        self.title.pack(side="left")
        tk.Button(top, text="Close  (Esc)", command=self.close, **button).pack(side="right")
        self.buttons = {}
        for mode, text in (("game", "Game"), ("shot", "Screenshot"), ("log", "Log")):
            self.buttons[mode] = tk.Button(top, text=text, command=lambda m=mode: self.show(m), **button)
            self.buttons[mode].pack(side="right", padx=4)
        self.info = tk.Label(self, anchor="w", justify="left", bg=bg, fg="#8a8a8a", font=("DejaVu Sans Mono", 9))
        self.info.pack(fill="x", padx=12, pady=(2, 6))
        self.body = tk.Frame(self, bg=bg)
        self.body.pack(fill="both", expand=True, padx=12, pady=(0, 12))
        bar = tk.Scrollbar(self.body, bg="#2d2d2d", troughcolor=bg, relief="flat", bd=0, width=10)
        self.text = tk.Text(self.body, bg=UI_FIELD, fg="#c8c8c8", font=("DejaVu Sans Mono", 9), wrap="none", relief="flat", bd=0,
                            highlightthickness=0, state="disabled", cursor="arrow", yscrollcommand=bar.set)
        bar.config(command=self.text.yview)
        self.bar = bar
        # read only: keys and the mouse cannot change it (selecting and copying still work)
        self.text.bind("<Key>", lambda e: "break" if not (e.state & 4) else None)
        self.picture = tk.Label(self.body, bg="#101010")
        self.photo = None
        self.picture.bind("<Button-1>", lambda e: self.close())

    def open(self, label):
        self.label = label
        self.title.config(text=label)
        self.place(x=0, y=0, relwidth=1, relheight=1)
        self.lift()
        self.show("log")
        self.refresh()

    def show(self, mode):
        self.mode = mode
        for name, widget in self.buttons.items():
            widget.config(relief="sunken" if name == mode else "flat")
        if mode == "log":
            self.picture.pack_forget()
            self.bar.pack(side="right", fill="y")
            self.text.pack(side="left", fill="both", expand=True)
        else:
            self.bar.pack_forget()
            self.text.pack_forget()
            self.picture.pack(fill="both", expand=True)
        self.draw_now()

    def close(self):
        self.label = None
        self.place_forget()

    def is_open(self):
        return self.label is not None

    def set_text(self, content):
        at_end = self.text.yview()[1] >= 0.999
        self.text.config(state="normal")
        try:
            self.text.delete("1.0", "end")
            self.text.insert("end", content)
            if at_end:
                self.text.see("end")
        finally:
            self.text.config(state="disabled")

    def draw_now(self):
        """The screenshot or the picture of the game, as big as the window lets it be (the proportions stay)."""
        if self.mode == "log" or self.label is None:
            return
        tile = self.grid.tiles.get(self.label)
        image = None if tile is None else (tile.shot if self.mode == "shot" else tile.image)
        width, height = max(self.picture.winfo_width(), 200), max(self.picture.winfo_height(), 120)
        if image is None:
            self.picture.config(image="", text="nothing yet" if self.mode == "game" else "no screenshot yet", fg="#777", font=("DejaVu Sans", 16))
            self.photo = None
            return
        scale = min(width / image.width, height / image.height)
        shown = image.resize((max(1, int(image.width * scale)), max(1, int(image.height * scale))), Image.LANCZOS if scale < 1 else Image.NEAREST)
        self.photo = ImageTk.PhotoImage(shown)
        self.picture.config(image=self.photo, text="")

    def refresh(self):
        label = self.label
        if label is None:
            return
        try:
            tile = self.grid.tiles.get(label)
            path = self.grid.log_path(label)
            size, content = 0, "(no log yet: the run has not started)"
            if path.exists():
                with path.open("rb") as f:
                    f.seek(0, 2)
                    size = f.tell()
                    f.seek(max(size - 15000, 0))
                    content = f.read().decode(errors="replace") or "(the log is empty so far)"
            info = f"state: {tile.state if tile else '?'}   stage: {tile.stage if tile else '?'}   {size // 1024} KB   {path}"
            if tile and tile.started:
                end = tile.finished or time.time()
                build = fmt((tile.game_started or end) - tile.started) if tile.built else "-"
                game = fmt(end - tile.game_started) if tile.game_started else "-"
                info += f"\nbuild: {build}   game: {game}   result: {tile.why or '-'}"
            self.info.config(text=info)
            if self.mode == "log":
                self.set_text(content)
            else:
                self.draw_now()
        except Exception:                                # a debug view must not fail silently
            import traceback
            self.set_text("the view failed:\n" + traceback.format_exc())
        self.root.after(250 if self.mode != "log" else 1000, self.refresh)


class Grid:
    def __init__(self, root: tk.Tk, canvas: tk.Canvas, statusbar: tk.Label):
        self.root = root
        self.tiles: dict[str, Tile] = {}
        self.order: list[str] = []
        self.lock = threading.Lock()
        self.canvas, self.statusbar = canvas, statusbar
        self.photo = None
        self.layout = []
        self.streams = {}            # display -> the latest frame of the game on it
        self.procs = []              # the ffmpeg of every stream
        self.last_slow = 0.0
        self.status = "ready"
        root.after(1000, self.tick)

    def reset(self):
        with self.lock:
            self.tiles.clear()
            self.order.clear()

    # -- hooks called by autotest.py (worker threads)
    def on_plan(self, todo, skipped, cached):
        with self.lock:
            order = []
            for labels, state in ((todo, "queued"), (skipped, "skipped"), (cached, "cached")):
                for label in labels:
                    tile = self.tiles.get(label) or Tile(label)
                    if tile.state in ("queued", "skipped", "cached"):       # a tile that already runs keeps its state
                        tile.state = state
                    self.tiles[label] = tile
                    order.append(label)
            self.order = order

    def on_start(self, label, display):
        with self.lock:
            tile = self.tiles.setdefault(label, Tile(label))
            if label not in self.order:
                self.order.append(label)
            tile.state, tile.display, tile.started = "running", display, time.time()

    def on_phase(self, label, phase, display=None):
        with self.lock:
            tile = self.tiles.get(label)
            if tile is None:
                return
            if phase == "build":
                tile.built = True
            else:
                tile.game_started = time.time()
                tile.display = display

    def on_finish(self, label, ok, why):
        with self.lock:
            tile = self.tiles[label]
            tile.state, tile.why = ("pass" if ok else "fail"), why
            tile.finished = time.time()
            tile.shot = self.find_shot(label, tile.started)

    @staticmethod
    def live_shot(tile):
        """A screenshot the running game took (its run folder, screenshots/): shown in the tile at once."""
        version, loader = tile.label.split("/")
        try:
            index = autotest.b.MINECRAFT_VERSIONS.index(version)
        except ValueError:
            return None
        base = autotest.b.WORK / str(index) / loader
        files = [f for d in ("run", "run-dh") for f in (base / d / "screenshots").glob("*.png") if f.stat().st_mtime >= tile.started - 1]
        if not files:
            return None
        newest = max(files, key=lambda f: f.stat().st_mtime)
        if getattr(tile, "shot_file", None) == (newest, newest.stat().st_mtime):
            return tile.shot
        try:
            image = Image.open(newest).convert("RGB")
            tile.shot_file = (newest, newest.stat().st_mtime)
            return image
        except Exception:
            return None

    @staticmethod
    def find_shot(label, since):
        """The newest screenshot the test of this run took (autotest.py copies them to shots/<version>-<loader>-<name>.png)."""
        version, loader = label.split("/")
        files = [f for f in (autotest.SCRATCH / "shots").glob(f"{version}-{loader}-*.png") if f.stat().st_mtime >= since - 2]
        if not files:
            return None
        try:
            return Image.open(max(files, key=lambda f: f.stat().st_mtime)).convert("RGB")
        except Exception:
            return None

    # -- frames: one screenshot of each running virtual screen per second, taken off the UI thread
    def read_stage(self, label):
        """The step the game is at, from the tail of its log: gradle first, then the game, then the test."""
        version, loader = label.split("/")
        log = autotest.SCRATCH / f"{autotest.b.read_props()['archives_name']}-{version}-{loader}.log"
        try:
            with log.open("rb") as f:
                f.seek(0, 2)
                f.seek(max(f.tell() - 20000, 0))
                text = f.read().decode(errors="replace")
        except OSError:
            return "starting gradle"
        for marker, stage in (("AUTOTEST PASSED", "finishing"), ("AUTOTEST opening", "opening the world"), ("AUTOTEST command", "test running"),
                              ("AUTOTEST registered", "game is up"), ("Created: ", "loading textures"), ("Loading for game", "loading the game"),
                              ("Sound engine started", "loading the game"), ("Setting user", "starting the game"), (":runClient", "starting the game"),
                              ("Downloading", "downloading"), ("Configure project", "gradle is configuring")):
            if marker in text:
                return stage
        return "starting gradle"

    # -- the live picture: one ffmpeg per virtual screen streams raw frames (about 20 a second); a thread keeps the latest
    def start_stream(self, display):
        if display in self.streams:
            return
        self.streams[display] = None
        threading.Thread(target=self.read_stream, args=(display,), daemon=True).start()

    def read_stream(self, display):
        size = autotest.SIZE
        width, height = (int(v) for v in size.split("x"))
        cmd = ["ffmpeg", "-loglevel", "quiet", "-f", "x11grab", "-video_size", size, "-framerate", "20", "-i", display,
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=width * height * 3 * 2)
        except OSError:
            return
        self.procs.append(proc)
        frame_size = width * height * 3
        while True:
            data = proc.stdout.read(frame_size)
            if len(data) < frame_size:
                break
            frame = Image.frombytes("RGB", (width, height), data)
            # an empty (black) screen is the game already closed: keep the last real picture
            if any(high > 8 for _, high in frame.getextrema()):
                self.streams[display] = frame

    def grab_frames(self):
        with self.lock:
            running = [(t, t.display) for t in self.tiles.values() if t.state == "running" and t.display]
        for tile, display in running:
            self.start_stream(display)
            frame = self.streams.get(display)
            if frame is not None:
                tile.image = frame
        now = time.time()
        if now - self.last_slow > 0.7:          # the log and the screenshots are looked at less often
            self.last_slow = now
            for tile, _ in running:
                tile.stage = self.read_stage(tile.label)
                shot = self.live_shot(tile)
                if shot is not None:
                    tile.shot = shot

    def tile_at(self, px, py):
        for x, y, w, h, label in self.layout:
            if x <= px < x + w and y <= py < y + h:
                return label
        return None

    def log_path(self, label):
        version, loader = label.split("/")
        return autotest.SCRATCH / f"{autotest.b.read_props()['archives_name']}-{version}-{loader}.log"

    def tick(self):
        self.grab_frames()
        self.draw()
        self.root.after(70, self.tick)

    def draw(self):
        canvas = self.render(max(self.canvas.winfo_width(), 200), max(self.canvas.winfo_height(), 200))
        with self.lock:
            finished = sum(1 for t in self.tiles.values() if t.state in ("pass", "fail", "cached"))
            total = len(self.tiles)
        self.statusbar.config(text=f"{self.status}" + (f"     {finished} / {total} finished" if total else ""))
        self.photo = ImageTk.PhotoImage(canvas)
        self.canvas.delete("all")
        self.canvas.create_image(0, 0, image=self.photo, anchor="nw")

    def render(self, width, height):
        with self.lock:
            labels = list(self.order)
            tiles = {l: self.tiles[l] for l in labels}
        count = max(len(labels), 1)
        # the number of columns that makes the 16:9 tiles biggest; the same gap between all tiles and around the grid
        gap, bar = 8, 0

        def tile_for(c):
            r = (count + c - 1) // c
            return min((width - gap * (c + 1)) // c, int((height - bar - gap * (r + 1)) // r * 16 / 9))
        cols = max(range(1, count + 1), key=tile_for)
        rows = (count + cols - 1) // cols
        tile_w = max(tile_for(cols), 40)
        tile_h = tile_w * 9 // 16
        x0 = (width - (cols * tile_w + (cols - 1) * gap)) // 2
        y0 = (height - bar - (rows * tile_h + (rows - 1) * gap)) // 2
        canvas = Image.new("RGB", (width, height), BACKGROUND)
        draw = ImageDraw.Draw(canvas)
        small = font(max(tile_w // 22, 8))
        big_size = max(tile_w // 9, 10)
        longest = max((draw.textlength(l, font=font(big_size)) for l in labels), default=0)
        if longest > tile_w * 0.9:                      # the longest name ("1.21.10/neoforge") must fit into the tile
            big_size = max(int(big_size * tile_w * 0.9 / longest), 8)
        big = font(big_size)
        self.layout = []
        done = 0
        for i, label in enumerate(labels):
            tile = tiles[label]
            x, y = x0 + (i % cols) * (tile_w + gap) - 2, y0 + (i // cols) * (tile_h + gap) - 2
            box = Image.new("RGB", (tile_w, tile_h), GREY)
            picture = tile.shot if tile.shot is not None else tile.image
            if picture is not None and tile.state in ("running", "pass", "fail"):
                box = picture.resize((tile_w, tile_h))
            border = {"running": (70, 140, 230), "pass": GREEN_LINE, "fail": RED_LINE}.get(tile.state)
            if tile.state == "running" and tile.image is None:
                ImageDraw.Draw(box).text((tile_w // 2, tile_h // 2), tile.stage, fill=(150, 150, 150), font=small, anchor="mm")
            if tile.state == "cached":
                border = GREEN_LINE
                box = Image.new("RGB", (tile_w, tile_h), (34, 38, 34))
            elif tile.state == "skipped":
                box = Image.new("RGB", (tile_w, tile_h), (44, 44, 44))
            if tile.state in ("pass", "fail", "cached"):
                done += 1
            canvas.paste(box, (x + 2, y + 2))
            self.layout.append((x + 2, y + 2, tile_w, tile_h, label))
            overlay = ImageDraw.Draw(canvas)
            head = tile_w // 11
            result = {"pass": "PASSED", "fail": tile.why[:36], "cached": "unchanged", "skipped": "skipped", "queued": "waiting", "running": ""}[tile.state]
            # the time of the run: counting while it runs (build and start of the game together), fixed when it is over
            if tile.state == "running":
                seconds = int(time.time() - tile.started)
                result = f"{seconds // 60}:{seconds % 60:02d}"
            elif tile.state in ("pass", "fail") and tile.finished:
                seconds = int(tile.finished - tile.started)
                result += f"  {seconds // 60}:{seconds % 60:02d}"
            overlay.rectangle((x + 2, y + 2, x + 1 + tile_w, y + 1 + head), fill=(0, 0, 0))
            overlay.text((x + 8, y + 2 + head // 2), label, fill="white", font=small, anchor="lm")
            if result:
                color = {"pass": (110, 220, 130), "fail": (240, 110, 110), "running": (120, 170, 240)}.get(tile.state, (160, 160, 160))
                overlay.text((x + tile_w - 4, y + 2 + head // 2), result, fill=color, font=small, anchor="rm")
            # build and start of the game, each with its own time (the build is skipped when the sources did not change)
            if tile.state in ("running", "pass", "fail") and tile.started:
                now = tile.finished or time.time()
                if tile.game_started:
                    build = f"build {fmt(tile.game_started - tile.started)}" if tile.built else "build -"
                    detail = f"{build}   game {fmt(now - tile.game_started)}"
                else:
                    detail = f"build {fmt(now - tile.started)}"
                bottom = y + 2 + tile_h - head
                overlay.rectangle((x + 2, bottom, x + 1 + tile_w, y + 1 + tile_h), fill=(0, 0, 0))
                overlay.text((x + 8, bottom + head // 2), detail, fill=(190, 190, 190), font=small, anchor="lm")
            if border:
                overlay.rectangle((x + 2, y + 2, x + 1 + tile_w, y + 1 + tile_h), outline=border, width=max(3, tile_w // 80))
        if not labels:
            draw.text((width // 2, height // 2), "Choose versions below and press Start", fill=(120, 120, 120), font=font(22), anchor="mm")
        return canvas


def main() -> int:
    if not shutil.which("Xvfb"):
        print("Xvfb is not installed:  sudo pacman -S xorg-server-xvfb")
        return 1
    if not shutil.which("import"):
        print("ImageMagick is needed for the pictures:  sudo pacman -S imagemagick")
        return 1
    import argparse
    import buildlib as b
    own = argparse.ArgumentParser(add_help=False)
    own.add_argument("--matrix", nargs="?", const="tests/matrix.txt", help="run the plan of this file by itself (tiers of tests), write a report")
    own.add_argument("--tiers", help="only these tiers of the plan, e.g. smoke,full")
    own.add_argument("--only", help="only these runs of the plan, e.g. 26.3/fabric,1.21.11/neoforge")
    own.add_argument("--only-tests", help="only these tests of the plan")
    own.add_argument("--report", help="where the report of --matrix goes (default: test-reports/<time>)")
    own.add_argument("--exit", action="store_true", help="close the window when the plan is done")
    mine, extra = own.parse_known_args(sys.argv[1:])       # the rest are options of autotest.py, e.g. --menu
    autotest.SIZE = os.environ.get("AUTOTEST_SIZE", f"{TILE_W}x{TILE_H}")
    parser = argparse.ArgumentParser()
    b.add_common_args(parser)
    all_jobs = b.select_jobs(parser.parse_args([]), lambda loader: [])
    skip = autotest.load_json(autotest.SKIP_FILE)

    root = tk.Tk()
    root.title("autotest")
    root.configure(bg=UI_BG)
    root.option_add("*Font", ("DejaVu Sans", 10))
    root.geometry("1920x1080" if root.winfo_screenwidth() >= 1920 else "1280x720")
    PANEL = "#202020"
    flat = dict(relief="flat", bd=0, highlightthickness=0, padx=12, pady=8)
    button = dict(bg="#2d2d2d", fg=UI_FG, activebackground="#3a3a3a", activeforeground="white", **flat)
    green = {**button, "bg": "#2e7d3a", "activebackground": "#38944a", "fg": "white"}
    red = {**button, "bg": "#a03030", "activebackground": "#bb3a3a", "fg": "white"}
    heading = dict(bg=PANEL, fg="#7a7a7a", font=("DejaVu Sans", 9, "bold"), anchor="w")

    # the pictures on the left, one panel on the right with two blocks: RUN and RELEASE
    panel = tk.Frame(root, bg=PANEL, width=270)
    panel.pack(side="right", fill="y")
    panel.pack_propagate(False)
    canvas = tk.Canvas(root, bg=UI_BG, highlightthickness=0)
    canvas.pack(side="left", fill="both", expand=True)
    status = tk.Label(panel, anchor="w", justify="left", wraplength=240, bg=PANEL, fg="#9a9a9a")
    status.pack(side="bottom", fill="x", padx=14, pady=(0, 12))

    grid = Grid(root, canvas, status)
    autotest.ON_PLAN, autotest.ON_START, autotest.ON_FINISH = grid.on_plan, grid.on_start, grid.on_finish
    autotest.ON_PHASE = grid.on_phase
    autotest.ON_STATUS = lambda text: setattr(grid, "status", text)
    running = threading.Event()
    parallel = tk.IntVar(value=4)
    builds = tk.IntVar(value=2)
    force = tk.BooleanVar(value=False)
    fastmods = tk.BooleanVar(value=True)
    gpu = tk.BooleanVar(value=bool(shutil.which("vglrun")))
    # the tests are the files of tests/ (and autotest.yml); the list shows the `name:` written in each of them
    def test_name(path):
        for line in path.read_text().splitlines():
            if line.startswith("name:"):
                return line.split(":", 1)[1].strip()
        return path.stem

    test_files = {test_name(p): str(p.relative_to(autotest.b.ROOT))
                  for p in sorted(list(autotest.b.ROOT.glob("autotest*.yml")) + list(autotest.b.ROOT.glob("tests/*.yml")))}
    tests = list(test_files)
    test_var = tk.StringVar(value=tests[0] if tests else "")

    def current_version():
        try:
            out = subprocess.run([sys.executable, "build-publish.py", "--show-version"], cwd=autotest.b.ROOT, capture_output=True, text=True,
                                 stdin=subprocess.DEVNULL, timeout=30)
            return out.stdout.strip().splitlines()[-1] if out.stdout.strip() else "?"
        except Exception:
            return "?"

    # ---- the version picker: a panel on top of this window (not a window of its own), next to the right panel
    popup = tk.Frame(root, bg=PANEL, highlightbackground="#3a3a3a", highlightthickness=1)
    list_box = tk.Frame(popup, bg=PANEL)
    list_box.pack(fill="both", expand=True, padx=10, pady=(10, 4))
    scroll = tk.Scrollbar(list_box, bg="#2d2d2d", troughcolor=PANEL, activebackground="#555", relief="flat", bd=0, width=10)
    listbox = tk.Listbox(list_box, selectmode=tk.EXTENDED, exportselection=False, yscrollcommand=scroll.set, activestyle="none",
                         width=26, height=20, font=("DejaVu Sans Mono", 10), bg=UI_FIELD, fg=UI_FG, selectbackground="#2f7fd0",
                         selectforeground="white", highlightthickness=0, bd=0, relief="flat")
    scroll.config(command=listbox.yview)
    scroll.pack(side="right", fill="y")
    listbox.pack(side="left", fill="both", expand=True)
    for job in all_jobs:
        listbox.insert("end", job.label + ("   (skipped)" if job.label in skip else ""))
    tk.Label(popup, text="Click, Ctrl + click and Shift + click choose several", bg=PANEL, fg="#777", font=("DejaVu Sans", 9)).pack(anchor="w", padx=12)
    quick = tk.Frame(popup, bg=PANEL)
    quick.pack(fill="x", padx=10, pady=10)
    versions_button = tk.Button(panel, text="", anchor="w", **button)

    def refresh_label(event=None):
        count = len(listbox.curselection())
        versions_button.config(text=f"{count} versions chosen  \u25c2" if count else "Choose versions  \u25c2")

    def pick(kind):
        listbox.selection_clear(0, "end")
        for i, job in enumerate(all_jobs):
            if kind == "all" or job.loader == kind:
                listbox.selection_set(i)
        refresh_label()

    def clear():
        listbox.selection_clear(0, "end")
        refresh_label()

    for text, command in (("All", lambda: pick("all")), ("Fabric", lambda: pick("fabric")), ("NeoForge", lambda: pick("neoforge")),
                          ("Forge", lambda: pick("forge")), ("None", clear)):
        tk.Button(quick, text=text, command=command, **{**button, "padx": 8, "pady": 4}).pack(side="left", expand=True, fill="x", padx=2)
    listbox.bind("<<ListboxSelect>>", refresh_label)

    def toggle_popup(event=None):
        if popup.winfo_ismapped():
            popup.place_forget()
        else:
            popup.place(x=root.winfo_width() - panel.winfo_width() - 8, y=10, anchor="ne")
            popup.lift()

    # ---- debug: a click on a tile opens its log (the tail, refreshed) over the pictures
    viewer = LogViewer(root, grid, PANEL, button)

    def on_canvas_click(event):
        popup.place_forget()
        label = grid.tile_at(event.x, event.y)
        if label:
            viewer.open(label)

    canvas.bind("<Button-1>", on_canvas_click)
    root.bind("<Escape>", lambda e: viewer.close() if viewer.is_open() else None)
    versions_button.config(command=toggle_popup)
    refresh_label()

    def chosen(skip_unrunnable=True):
        return [all_jobs[i].label for i in listbox.curselection() if not (skip_unrunnable and all_jobs[i].label in skip)]

    # ---- tests
    def start():
        labels = chosen(False)
        popup.place_forget()
        if running.is_set():
            return
        if not labels:
            grid.status = "Choose versions first."
            return
        grid.reset()
        # the frames of the chosen versions are there at once; autotest.py then sorts out what is skipped or unchanged
        grid.on_plan([l for l in labels if l not in skip], [l for l in labels if l in skip], [])
        grid.draw()
        running.set()
        grid.status = "starting"
        argv = extra + ([test_files[test_var.get()]] if test_var.get() in test_files else []) + ["--labels", ",".join(labels), "-j", str(parallel.get()), "--builds", str(builds.get()), "--xvfb", "--plain"] \
            + (["--force"] if force.get() else []) + (["--fast-mods"] if fastmods.get() else []) + (["--gpu"] if gpu.get() else [])

        def work():
            try:
                code = autotest.main(argv)
                grid.status = "finished" if code == 0 else "finished, there are failures"
            except SystemExit as e:
                grid.status = f"stopped: {e}"
            except Exception as e:
                grid.status = f"error: {e}"
            finally:
                running.clear()
                pause_button.config(text="Pause")

        threading.Thread(target=work, daemon=True).start()

    def toggle_pause():
        if not running.is_set():
            return
        if autotest.PAUSE.is_set():
            autotest.PAUSE.clear()
            grid.status = "running"
            pause_button.config(text="Pause")
        else:
            autotest.PAUSE.set()
            grid.status = "paused: the running games finish, new ones wait"
            pause_button.config(text="Resume")

    procs = {"publish": None}

    def stop():
        if running.is_set():
            grid.status = "stopped"
            autotest.stop_all()
        stop_procs()

    def stop_procs():
        # gradle (the build and the publishing) is asked to stop and given time: a hard kill leaves the cache of Loom "disowned" and the
        # next build downloads everything again
        for proc in procs.values():
            if proc is not None:
                threading.Thread(target=autotest.stop_gradle, args=(proc,), daemon=True).start()

    # ---- the version of the mod (gradle.properties): shown in the field, written by itself when you leave it or publish
    version_var = tk.StringVar(value=current_version())
    saved = {"v": version_var.get()}

    def apply_version(event=None):
        value = version_var.get().strip()
        if not value or value == saved["v"]:
            return True
        out = subprocess.run([sys.executable, "build-publish.py", "--set-version-only", value], cwd=autotest.b.ROOT, capture_output=True,
                             text=True, stdin=subprocess.DEVNULL, timeout=30)
        if out.returncode != 0:
            grid.status = (out.stderr or out.stdout).strip().splitlines()[-1] if (out.stderr or out.stdout).strip() else "bad version"
            version_var.set(saved["v"])
            return False
        saved["v"] = value
        grid.status = f"version {value}"
        return True

    # ---- publish: sets the version, builds the chosen versions and uploads them
    armed = {"on": False}

    def do_publish():
        labels = chosen()
        popup.place_forget()
        if procs["publish"] is not None:
            return
        if not labels:
            grid.status = "Choose versions first."
            return
        if not armed["on"]:
            armed["on"] = True
            publish_button.config(text=f"Sure? {len(labels)} builds as {version_var.get()}", bg="#b05a20", activebackground="#c96a28")
            root.after(6000, disarm)
            return
        disarm()
        if not apply_version():
            return
        log = (autotest.SCRATCH / "publish.log").open("w")
        procs["publish"] = subprocess.Popen([sys.executable, "build-publish.py", "-y", "--plain", "--labels", ",".join(labels), "-j", "2"],
                                            cwd=autotest.b.ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        grid.status = f"building and publishing {len(labels)} builds as {saved['v']}"

        def wait():
            code = procs["publish"].wait()
            procs["publish"] = None
            grid.status = "published" if code == 0 else f"publishing failed (exit {code}), see {autotest.SCRATCH / 'publish.log'}"

        threading.Thread(target=wait, daemon=True).start()

    def disarm():
        armed["on"] = False
        publish_button.config(text="Publish", bg=green["bg"], activebackground=green["activebackground"])

    # ---- the panel
    pad = dict(fill="x", padx=14)
    tk.Label(panel, text="RUN", **heading).pack(pady=(16, 4), **pad)
    versions_button.pack(pady=2, **pad)
    if tests:
        menu = tk.OptionMenu(panel, test_var, *tests)
        menu.config(**button, indicatoron=False, anchor="w")
        menu["menu"].config(bg=UI_FIELD, fg=UI_FG, activebackground=UI_SELECT, bd=0)
        menu.pack(pady=2, **pad)
    for text, var in (("Games at once", parallel), ("Builds at once", builds)):
        row = tk.Frame(panel, bg=PANEL)
        row.pack(pady=(8, 0), **pad)
        tk.Label(row, text=text, bg=PANEL, fg="#9a9a9a").pack(side="left")
        tk.Spinbox(row, from_=1, to=16, textvariable=var, width=3, bg=UI_FIELD, fg=UI_FG, buttonbackground="#2d2d2d",
                   insertbackground=UI_FG, highlightthickness=0, bd=3, relief="flat").pack(side="right")
    for text, var in (("Ignore unchanged", force), ("Fast start mods", fastmods), ("GPU (VirtualGL)", gpu)):
        tk.Checkbutton(panel, text=text, variable=var, selectcolor=UI_FIELD, bg=PANEL, fg=UI_FG, activebackground=PANEL,
                       activeforeground=UI_FG, highlightthickness=0, bd=0, anchor="w").pack(pady=(4, 0), **pad)
    buttons = tk.Frame(panel, bg=PANEL)
    buttons.pack(pady=12, **pad)
    start_button = tk.Button(buttons, text="Start", command=start, **green)
    pause_button = tk.Button(buttons, text="Pause", command=toggle_pause, **button)
    stop_button = tk.Button(buttons, text="Stop", command=stop, **red)
    start_button.pack(side="left", expand=True, fill="x", padx=(0, 3))
    pause_button.pack(side="left", expand=True, fill="x", padx=3)
    stop_button.pack(side="left", expand=True, fill="x", padx=(3, 0))

    tk.Frame(panel, bg="#333333", height=1).pack(pady=(4, 0), **pad)
    tk.Label(panel, text="RELEASE", **heading).pack(pady=(14, 4), **pad)
    vrow = tk.Frame(panel, bg=PANEL)
    vrow.pack(**pad)
    tk.Label(vrow, text="Version", bg=PANEL, fg="#9a9a9a").pack(side="left")
    entry = tk.Entry(vrow, textvariable=version_var, width=12, bg=UI_FIELD, fg=UI_FG, insertbackground=UI_FG, relief="flat",
                     highlightthickness=0, bd=6, justify="center")
    entry.pack(side="right")
    entry.bind("<Return>", apply_version)
    entry.bind("<FocusOut>", apply_version)
    publish_button = tk.Button(panel, text="Publish", command=do_publish, **green)
    publish_button.pack(pady=12, **pad)

    def close():
        autotest.stop_all()
        stop_procs()
        for proc in grid.procs:
            proc.kill()
        root.destroy()

    # ---- the plan (--matrix): every test of every tier, one after another, each on its versions; the results and the pictures go to a report
    def run_matrix():
        import json
        from pathlib import Path
        sys.path.insert(0, str(autotest.b.ROOT / "tools"))
        import matrix_sheet
        report = Path(mine.report) if mine.report else autotest.b.ROOT / "test-reports" / time.strftime("%Y-%m-%d_%H-%M")
        (report / "shots").mkdir(parents=True, exist_ok=True)
        known = {job.label for job in all_jobs if job.label not in skip}
        plan = []
        for line in (autotest.b.ROOT / mine.matrix).read_text().splitlines():
            parts = [p.strip() for p in line.split("#", 1)[0].split("|")]
            if len(parts) < 3:
                continue
            tier, names, where = parts[0], [t.strip() for t in parts[1].split(",") if t.strip()], [w.strip() for w in parts[2].split(",") if w.strip()]
            games = int(parts[3]) if len(parts) > 3 and parts[3] else 3
            if mine.tiers and tier not in mine.tiers.split(","):
                continue
            labels = [l for l in sorted(known) if "all" in where or l in where or l.split("/")[0] in where]
            if mine.only:
                labels = [l for l in labels if l in mine.only.split(",")]
            for name in names:
                if labels and (not mine.only_tests or name in mine.only_tests.split(",")):
                    plan.append((tier, name, labels, games))
        state_file = report / "matrix.json"
        state = json.loads(state_file.read_text()) if state_file.exists() else {"tests": [], "results": {}, "seconds": {}}
        for number, (tier, name, labels, games) in enumerate(plan):
            if autotest.STOP.is_set() and number:
                break
            began = time.time()
            grid.reset()
            grid.on_plan(labels, [], [])
            grid.status = f"{tier}: {name}  ({number + 1} of {len(plan)} tests, {len(labels)} runs)"
            root.title(f"autotest - {grid.status}")
            running.set()
            argv = extra + [f"tests/{name}.yml", "--labels", ",".join(labels), "-j", str(games), "--builds", "2" if games > 1 else "1", "--xvfb", "--plain", "--force",
                            "--newest-first"] + (["--fast-mods"] if fastmods.get() else []) + (["--gpu"] if gpu.get() else [])
            try:
                autotest.main(argv)
            except SystemExit as e:
                grid.status = f"stopped: {e}"
            except Exception as e:
                grid.status = f"error: {e}"
            finally:
                running.clear()
            results = autotest.load_json(autotest.SCRATCH / "results.json")
            if name not in state["tests"]:
                state["tests"].append(name)
            state["results"].setdefault(name, {}).update({l: r for l, r in results.items() if l in labels})
            state["seconds"][name] = int(time.time() - began)
            (report / "shots" / name).mkdir(exist_ok=True)
            (report / "logs" / name).mkdir(parents=True, exist_ok=True)
            for label in labels:
                version, loader = label.split("/")
                for shot in (autotest.SCRATCH / "shots").glob(f"{version}-{loader}-*.png"):
                    if shot.stat().st_mtime >= began - 2:
                        shutil.copy2(shot, report / "shots" / name / shot.name)
                log = grid.log_path(label)
                if log is not None and os.path.exists(log) and os.path.getmtime(log) >= began - 2:
                    shutil.copy2(log, report / "logs" / name / os.path.basename(log))
            state_file.write_text(json.dumps(state, indent=1))
            print(f"MATRIX {tier} {name}: {sum(1 for l in labels if results.get(l, [False])[0])} of {len(labels)} passed in {int(time.time() - began)} s", flush=True)
        try:
            print("MATRIX sheet", matrix_sheet.build(report), flush=True)
        except Exception as e:
            print("MATRIX sheet failed:", e, flush=True)
        grid.status = f"the plan is done: {report}"
        print("MATRIX DONE", report, flush=True)
        if mine.exit:
            root.after(1500, close)

    if mine.matrix:
        root.after(800, lambda: threading.Thread(target=run_matrix, daemon=True).start())

    root.protocol("WM_DELETE_WINDOW", close)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
