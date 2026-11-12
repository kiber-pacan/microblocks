#!/usr/bin/env python3
"""One picture of a test matrix: a row per version / loader (newest first), a column per test, in every cell the last
screenshot of that run with a green (passed) or red (failed, with the reason) frame.

    python tools/matrix_sheet.py <report dir>          # reads matrix.json and shots/, writes matrix.png (and matrix.md)
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CELL_W, CELL_H, LEFT, TOP, GAP = 256, 144, 150, 34, 4
GREEN, RED, EMPTY, BACK, TEXT = (60, 200, 90), (230, 70, 70), (38, 38, 38), (20, 20, 20), (220, 220, 220)


def font(size):
    for name in ("DejaVuSans-Bold.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def version_key(label):
    version, loader = label.split("/")
    return [-int(x) for x in version.split(".")] + [0] * (4 - len(version.split("."))), loader


def build(report: Path) -> Path:
    data = json.loads((report / "matrix.json").read_text())
    tests = data["tests"]                                   # in the order they ran
    results = data["results"]                               # test -> label -> [ok, why]
    labels = sorted({l for t in tests for l in results.get(t, {})}, key=version_key)
    width, height = LEFT + len(tests) * (CELL_W + GAP), TOP + len(labels) * (CELL_H + GAP)
    sheet = Image.new("RGB", (width, height), BACK)
    draw = ImageDraw.Draw(sheet)
    big, small = font(15), font(11)
    for c, test in enumerate(tests):
        draw.text((LEFT + c * (CELL_W + GAP) + 6, 8), test, fill=TEXT, font=big)
    for r, label in enumerate(labels):
        y = TOP + r * (CELL_H + GAP)
        draw.text((8, y + CELL_H // 2 - 8), label, fill=TEXT, font=big)
        for c, test in enumerate(tests):
            x = LEFT + c * (CELL_W + GAP)
            result = results.get(test, {}).get(label)
            if result is None:
                draw.rectangle((x, y, x + CELL_W - 1, y + CELL_H - 1), fill=EMPTY)
                continue
            ok, why = result
            version, loader = label.split("/")
            shots = sorted((report / "shots" / test).glob(f"{version}-{loader}-*.png"), key=lambda f: f.stat().st_mtime)
            if shots:
                try:
                    sheet.paste(Image.open(shots[-1]).convert("RGB").resize((CELL_W, CELL_H), Image.LANCZOS), (x, y))
                except Exception:
                    shots = []
            if not shots:
                draw.rectangle((x, y, x + CELL_W - 1, y + CELL_H - 1), fill=(30, 60, 36) if ok else (70, 26, 26))
                draw.text((x + 8, y + 8), "passed, no screenshot" if ok else "failed", fill=TEXT, font=small)
            draw.rectangle((x, y, x + CELL_W - 1, y + CELL_H - 1), outline=GREEN if ok else RED, width=3)
            if not ok:
                words, line, lines = str(why).split(), "", []
                for word in words:
                    if len(line) + len(word) > 38:
                        lines.append(line)
                        line = ""
                    line += word + " "
                lines.append(line)
                box = y + CELL_H - 14 * min(len(lines), 4) - 6
                draw.rectangle((x + 3, box, x + CELL_W - 4, y + CELL_H - 4), fill=(90, 20, 20))
                for i, text in enumerate(lines[:4]):
                    draw.text((x + 7, box + 3 + 14 * i), text, fill=(255, 235, 235), font=small)
    out = report / "matrix.png"
    sheet.save(out)
    # the same as a table
    rows = ["| | " + " | ".join(tests) + " |", "|---|" + "---|" * len(tests)]
    for label in labels:
        cells = []
        for test in tests:
            result = results.get(test, {}).get(label)
            cells.append("" if result is None else "ok" if result[0] else "FAIL: " + str(result[1])[:60])
        rows.append(f"| {label} | " + " | ".join(cells) + " |")
    done = [r for t in tests for r in results.get(t, {}).values()]
    (report / "matrix.md").write_text(f"{sum(1 for r in done if r[0])} of {len(done)} runs passed\n\n" + "\n".join(rows) + "\n")
    return out


if __name__ == "__main__":
    print(build(Path(sys.argv[1])))
