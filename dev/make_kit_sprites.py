#!/usr/bin/env python3
"""Small sprites for the description kit (tools/Description-Kit): a dimension-line strip and three 16x16 iso cubes
(ghost, oak planks, roof bricks), built with the icon's 2:1 geometry at 8 px per unit and the same 12x12 textures.

  python3 dev/make_kit_sprites.py     # -> dev/icon/sprites/{dim_strip,ghost_cube,planks_cube,brick_cube}.png
"""
from pathlib import Path

import numpy as np
from PIL import Image

SPR = Path(__file__).resolve().parent / "icon" / "sprites"
H = lambda s: tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))
LINE, CYAN, INK = H("#E8F6FF"), H("#7FE3FF"), H("#0A1B30")
K = {0: 0.64, 1: 1.0, 2: 0.82}
TINT = np.array(H("#0E2042"), float)
U = 8


def cube(tex=None):
    a = np.zeros((16, 16, 4), np.uint8)
    t = np.array(Image.open(SPR / f"{tex}.png").convert("RGBA")) if tex else None
    for j in range(16):
        for i in range(16):
            px, py = i + 0.5 - 8, j + 0.5 - 8
            x0 = (px / U + py / (U / 2)) / 2
            z0 = (py / (U / 2) - px / U) / 2
            sin = [x0 - 1, -1.0, z0 - 1]
            sout = [x0, 0.0, z0]
            s, f = max(sin), int(np.argmax(sin))
            if s >= min(sout) - 1e-9:
                continue
            x, y, z = x0 - s, -s, z0 - s
            u, v = {0: (1 - z, 1 - y), 1: (x, z), 2: (x, 1 - y)}[f]
            edge = any(min(c, 1 - c) < 0.5 / U for c in ((z, y), (x, z), (x, y))[f])
            if t is not None:
                c = t[min(int(v * 12), 11), min(int(u * 12), 11), :3].astype(float)
                c = c * K[f] + TINT * (1 - K[f]) * 0.5
                if edge:
                    c = c * 0.72 + np.array(INK, float) * 0.28
            else:
                c = np.array(LINE if f == 1 and edge else CYAN if edge else H("#3A86C0") if f == 1 else H("#2C6AA0") if f == 2 else H("#1F4F84"), float)
            a[j, i] = (*np.round(c).astype(np.uint8), 255)
    return a


def strip() -> np.ndarray:
    a = np.zeros((5, 16, 4), np.uint8)
    a[2, :] = (*CYAN, 255)
    a[:, 0] = (*LINE, 255)
    a[1:4, 8] = (*H("#5FA8D8"), 255)
    return a


def main():
    out = {"dim_strip": strip(), "ghost_cube": cube(), "planks_cube": cube("planks"), "brick_cube": cube("brick")}
    for n, a in out.items():
        Image.fromarray(a, "RGBA").save(SPR / f"{n}.png")
    sheet = Image.new("RGBA", (4 * 16 * 8 + 40, 16 * 8 + 16), (20, 56, 92, 255))
    for i, a in enumerate(out.values()):
        sheet.alpha_composite(Image.fromarray(a, "RGBA").resize((a.shape[1] * 8, a.shape[0] * 8), Image.NEAREST), (8 + i * 136, 8))
    sheet.save(SPR / "review_kit.png")


if __name__ == "__main__":
    main()
