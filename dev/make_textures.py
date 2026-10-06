#!/usr/bin/env python3
"""Cyanotype icon textures, drawn from scratch (12x12 texels, 2 px each in the icon): oak siding, window, roof bricks.

  python3 dev/make_textures.py        # writes dev/icon/sprites/{planks,window,brick}.png and a review sheet
"""
from pathlib import Path

import numpy as np
from PIL import Image

SPR = Path(__file__).resolve().parent / "icon" / "sprites"
H = lambda s: tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))

WOOD = {"e": H("#F7DB9E"), "d": H("#EDB96C"), "c": H("#D49A52"), "b": H("#A96F36"), "a": H("#7A4B24")}
BRICK = {"l": H("#E8774F"), "m": H("#C4503A"), "d": H("#96382E"), "x": H("#682A28")}
GLASS = {"1": H("#F2FBFF"), "2": H("#BDEBFF"), "3": H("#7FD3F5"), "4": H("#4FA4E2"), "k": H("#2A5C8F")}


def put(a, x, y, c):
    a[y, x] = c + (255,)


def planks() -> np.ndarray:
    a = np.zeros((12, 12, 4), np.uint8)
    joints = [4, 9, 2, 7]
    flecks = [(7, 1), (1, 4), (10, 5), (5, 7), (8, 10), (3, 9)]
    for k in range(4):
        y = k * 3
        for x in range(12):
            put(a, x, y, WOOD["d"])
            put(a, x, y + 1, WOOD["c"])
            put(a, x, y + 2, WOOD["b"])
        j = joints[k]
        put(a, j, y, WOOD["c"])
        put(a, j, y + 1, WOOD["b"])
        put(a, j, y + 2, WOOD["a"])
        put(a, (j + 1) % 12, y, WOOD["e"])
    for x, y in flecks:
        put(a, x, y, WOOD["b"] if y % 3 == 1 else WOOD["a"])
    return a


def window() -> np.ndarray:
    a = planks()
    for y in range(2, 10):
        for x in range(2, 10):
            put(a, x, y, GLASS["3"])
    for i in range(2, 10):
        put(a, i, 2, GLASS["k"])
        put(a, 2, i, GLASS["k"])
        put(a, i, 9, GLASS["k"])
        put(a, 9, i, GLASS["k"])
    for y in range(3, 9):
        for x in range(3, 9):
            put(a, x, y, GLASS["3"] if y > 5 else GLASS["2"])
    for x in range(3, 9):
        put(a, x, 8, GLASS["4"])
    for x, y in [(3, 7), (4, 6), (5, 5), (6, 4), (7, 3), (4, 7), (5, 6), (6, 5), (7, 4), (8, 3)]:
        put(a, x, y, GLASS["1"] if x + y in (10, 11) else GLASS["2"])
    return a


def brick() -> np.ndarray:
    a = np.zeros((12, 12, 4), np.uint8)
    tone = [["m", "l"], ["d", "m"], ["l", "m"], ["m", "d"]]
    for course in range(4):
        y = course * 3
        off = 0 if course % 2 == 0 else 3
        for x in range(12):
            bx = ((x + off) % 12) // 6
            body = tone[course][bx]
            put(a, x, y, BRICK["l"] if body != "d" else BRICK["m"])
            put(a, x, y + 1, BRICK[body])
            put(a, x, y + 2, BRICK["x"])
        for jx in ((5 + off) % 12, (11 + off) % 12):
            put(a, jx, y, BRICK["x"])
            put(a, jx, y + 1, BRICK["x"])
    return a


def main() -> None:
    SPR.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGBA", (3 * 12 * 12 + 4 * 8, 12 * 12 + 16), (20, 56, 92, 255))
    for i, (name, fn) in enumerate((("planks", planks), ("window", window), ("brick", brick))):
        a = fn()
        Image.fromarray(a, "RGBA").save(SPR / f"{name}.png")
        im = Image.fromarray(a, "RGBA").resize((144, 144), Image.NEAREST)
        sheet.alpha_composite(im, (8 + i * (144 + 8), 8))
    sheet.save(SPR / "review_textures.png")
    print("textures ->", SPR)


if __name__ == "__main__":
    main()
