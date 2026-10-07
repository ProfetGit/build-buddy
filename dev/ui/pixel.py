#!/usr/bin/env python3
"""Pixel-art interface icons: 16 x 16, hand-placed pixels, one style (a white body, a dark outline, blue where it matters).

Shapes are drawn as filled layers on a 16 x 16 grid and each layer gets its outline drawn round it automatically (one pixel,
straight neighbours only, so corners come out soft). The mouse and the key are the approved designs (2026-10-05).

    python3 dev/ui/pixel.py preview   -> dev/ui/out/pixel_all.png (every icon at 1x and 8x)
    python3 dev/ui/pixel.py build     -> src/main/resources/assets/cyanotype/ui/pixel.png + pixel.json (drawn crisp in the game)
"""
import json
import math
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"
SHIP = HERE.parent.parent / "src/main/resources/assets/cyanotype/ui"

K = (28, 34, 46)        # ink: the outline
W = (250, 252, 255)     # white body
S = (200, 208, 218)     # light shade
E = (160, 170, 184)     # darker shade
B = (30, 122, 222)      # blue: the accent
BL = (110, 176, 245)    # light blue
GR = (66, 190, 108)     # green
RD = (226, 78, 78)      # red
CY = (127, 227, 255)    # cyan, for marks drawn on the dark chips
GL = (150, 232, 170)    # light green: the one glint of the check


class Icon:
    def __init__(self, w=16, h=16):
        self.w, self.h = w, h
        self.px = {}

    def p(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[(x, y)] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.p(x, y, c)

    def hline(self, x0, x1, y, c):
        self.rect(x0, y, x1, y, c)

    def vline(self, x, y0, y1, c):
        self.rect(x, y0, x, y1, c)

    def erase(self, x, y):
        self.px.pop((x, y), None)

    def poly(self, pts, c):
        """Fills a polygon: a pixel is in when its middle is."""
        n = len(pts)
        for y in range(self.h):
            for x in range(self.w):
                px, py = x + 0.5, y + 0.5
                inside = False
                j = n - 1
                for i in range(n):
                    xi, yi = pts[i]
                    xj, yj = pts[j]
                    if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                        inside = not inside
                    j = i
                if inside:
                    self.p(x, y, c)

    def line(self, x0, y0, x1, y1, c, t=1):
        """A line of pixels t thick (a t x t brush walked along it)."""
        steps = max(abs(x1 - x0), abs(y1 - y0), 1)
        for s in range(steps + 1):
            x = round(x0 + (x1 - x0) * s / steps)
            y = round(y0 + (y1 - y0) * s / steps)
            for dy in range(t):
                for dx in range(t):
                    self.p(x + dx - (t - 1) // 2, y + dy - (t - 1) // 2, c)

    def stroke(self, pts, c, t=1):
        for a, b in zip(pts, pts[1:]):
            self.line(a[0], a[1], b[0], b[1], c, t)

    def outlined(self, ink=K):
        """A copy with a one pixel outline round the pixels it has (not on the diagonals, so the corners are soft)."""
        out = Icon(self.w, self.h)
        out.px = dict(self.px)
        for (x, y) in self.px:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if (x + dx, y + dy) not in self.px:
                    out.p(x + dx, y + dy, ink)
        return out

    def put(self, other):
        """Lays another icon over this one."""
        for k, c in other.px.items():
            self.px[k] = c

    def layer(self, fn, ink=K):
        """Draws a shape on its own, outlines it, and lays it over what is here."""
        sub = Icon(self.w, self.h)
        fn(sub)
        self.put(sub.outlined(ink) if ink else sub)

    def mirrored(self):
        out = Icon(self.w, self.h)
        for (x, y), c in self.px.items():
            out.p(self.w - 1 - x, y, c)
        return out

    def image(self):
        im = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        for (x, y), c in self.px.items():
            im.putpixel((x, y), (*c, 255))
        return im


# ---- the icons --------------------------------------------------------------------------------------------------------

def folder():
    i = Icon()

    def back(s):
        s.rect(1, 4, 14, 13, W)
        s.rect(1, 2, 6, 4, W)

    i.layer(back)
    i.layer(lambda s: s.rect(1, 7, 14, 13, B))
    i.hline(2, 13, 8, BL)
    return i


def save():
    i = Icon()

    def body(s):
        s.rect(1, 1, 14, 14, W)
        for (x, y) in [(12, 1), (13, 1), (14, 1), (13, 2), (14, 2), (14, 3)]:
            s.erase(x, y)

    i.layer(body)
    # the shutter, dark with a white slot
    i.rect(4, 1, 10, 5, K)
    i.rect(8, 2, 9, 4, W)
    # the label with its lines
    i.rect(3, 8, 12, 13, S)
    i.hline(4, 11, 10, B)
    i.hline(4, 9, 12, B)
    return i


def move():
    i = Icon()

    def arrows(s):
        s.rect(1, 7, 14, 8, W)
        s.rect(7, 1, 8, 14, W)
        for k in range(3):
            s.rect(7 - k, 1 + k, 8 + k, 1 + k, W)
            s.rect(7 - k, 14 - k, 8 + k, 14 - k, W)
            s.rect(1 + k, 7 - k, 1 + k, 8 + k, W)
            s.rect(14 - k, 7 - k, 14 - k, 8 + k, W)

    i.layer(arrows)
    i.rect(7, 7, 8, 8, B)
    return i


def layers():
    i = Icon()
    half = [1, 3, 5, 7, 5, 3, 1]

    def diamond(top, colour):
        def draw(s):
            for k, hw in enumerate(half):
                s.hline(8 - hw, 7 + hw, top + k, colour)
        return draw

    i.layer(diamond(7, B))
    i.layer(diamond(4, S))
    i.layer(diamond(1, W))
    return i


def list_():
    i = Icon()
    i.layer(lambda s: s.rect(2, 2, 13, 14, W))
    i.layer(lambda s: s.rect(5, 1, 10, 3, S))
    for (x1, y) in ((11, 6), (9, 8), (11, 10), (8, 12)):
        i.hline(4, x1, y, B)
    return i


def eye():
    i = Icon()
    rows = {4: (5, 10), 5: (3, 12), 6: (1, 14), 7: (1, 14), 8: (1, 14), 9: (1, 14), 10: (3, 12), 11: (5, 10)}

    def lens(s):
        for y, (a, b) in rows.items():
            s.hline(a, b, y, W)

    i.layer(lens)
    for y, (a, b) in {5: (6, 9), 6: (5, 10), 7: (5, 10), 8: (5, 10), 9: (5, 10), 10: (6, 9)}.items():
        i.hline(a, b, y, B)
    i.rect(7, 7, 8, 8, K)
    i.p(6, 6, BL)
    return i


def eye_off():
    i = Icon()
    rows = {4: (5, 10), 5: (3, 12), 6: (1, 14), 7: (1, 14), 8: (1, 14), 9: (1, 14), 10: (3, 12), 11: (5, 10)}

    def lens(s):
        for y, (a, b) in rows.items():
            s.hline(a, b, y, S)

    i.layer(lens)
    for y, (a, b) in {6: (6, 9), 7: (5, 10), 8: (5, 10), 9: (6, 9)}.items():
        i.hline(a, b, y, E)
    # a slash across it
    i.layer(lambda s: s.stroke([(2, 13), (13, 2)], W, 2))
    return i


def trash():
    i = Icon()
    i.layer(lambda s: s.rect(6, 1, 9, 2, S))
    i.layer(lambda s: s.rect(2, 3, 13, 5, W))

    def body(s):
        s.rect(3, 6, 12, 12, W)
        s.rect(4, 13, 11, 14, W)

    i.layer(body)
    i.vline(6, 8, 13, K)
    i.vline(9, 8, 13, K)
    return i


def undo():
    i = Icon()

    def arrow(s):
        s.poly([(1, 5.0), (6, 0.5), (6, 9.5)], W)
        s.stroke([(5, 5), (10, 5), (13, 8), (13, 10), (10, 13), (4, 13)], W, 3)

    i.layer(arrow)
    return i


def wand():
    i = Icon()
    i.layer(lambda s: s.stroke([(2, 13), (9, 6)], W, 2))
    # the sparkle: a four pointed star, and a small one
    i.layer(lambda s: s.poly([(11.5, 0.5), (12.6, 3.0), (15, 4.0), (12.6, 5.0), (11.5, 7.5), (10.4, 5.0), (8, 4.0), (10.4, 3.0)], B))
    i.p(3, 3, B)
    i.p(2, 3, B)
    i.p(4, 3, B)
    i.p(3, 2, B)
    i.p(3, 4, B)
    return i


def hammer():
    i = Icon()
    i.layer(lambda s: s.rect(6, 6, 8, 14, W))
    i.layer(lambda s: s.rect(2, 1, 12, 5, B))
    i.hline(3, 11, 2, BL)
    # the claw side of the head
    i.layer(lambda s: s.rect(13, 2, 14, 4, B))
    return i


def cube():
    i = Icon()

    def left(s):
        s.poly([(1, 5), (7.9, 8.5), (7.9, 15), (1, 11.5)], S)

    def right(s):
        s.poly([(14.9, 5), (8.1, 8.5), (8.1, 15), (14.9, 11.5)], B)

    def top(s):
        half = [1, 3, 5, 7, 5, 3, 1]
        for k, hw in enumerate(half):
            s.hline(8 - hw, 7 + hw, 1 + k, W)

    i.layer(left)
    i.layer(right)
    i.layer(top)
    return i


def house():
    """A cottage: the glyph of a blueprint in the lessons' mock Library and Save screens."""
    i = Icon()
    i.layer(lambda s: s.rect(11, 2, 12, 6, S))
    i.layer(lambda s: s.rect(2, 8, 13, 14, W))
    i.layer(lambda s: s.poly([(0.6, 8.6), (8, 1.4), (15.4, 8.6)], B))
    i.hline(5, 6, 6, BL)
    i.rect(7, 10, 8, 14, B)
    i.rect(3, 10, 4, 11, BL)
    i.rect(11, 10, 12, 11, BL)
    return i


def sweep():
    i = Icon()
    for x in (1, 6, 11):
        i.layer(lambda s, x=x: s.rect(x, 10, x + 3, 13, W))
    i.layer(lambda s: s.stroke([(2, 7), (4, 4), (8, 3), (11, 4)], B, 2))
    i.layer(lambda s: s.poly([(10, 5.5), (15, 5.5), (12.5, 9.5)], B))
    return i


def gear():
    i = Icon()

    def body(s):
        cx = cy = 7.5
        for y in range(16):
            for x in range(16):
                dx, dy = x + 0.5 - 8, y + 0.5 - 8
                d = math.hypot(dx, dy)
                a = math.degrees(math.atan2(dy, dx)) % 45
                tooth = d <= 7.3 and (a < 14 or a > 31)
                if (d <= 5.6 or tooth) and d >= 2.4:
                    s.p(x, y, W)

    i.layer(body)
    return i


def select():
    i = Icon()

    def corners(s):
        for (x0, y0, sx, sy) in ((1, 1, 1, 1), (14, 1, -1, 1), (1, 14, 1, -1), (14, 14, -1, -1)):
            xa, xb = sorted((x0, x0 + 4 * sx))
            ya, yb = sorted((y0, y0 + 4 * sy))
            s.rect(xa, min(y0, y0 + sy), xb, max(y0, y0 + sy), W)
            s.rect(min(x0, x0 + sx), ya, max(x0, x0 + sx), yb, W)

    i.layer(corners)
    i.rect(6, 6, 9, 9, B)
    return i


def check():
    """The red cross's two exact diagonals, 3 px thick, a long arm and a short one, with one light glint like the eye's."""
    i = Icon()
    i.layer(lambda s: s.stroke([(2, 8), (5, 11), (13, 3)], GR, 3))
    i.p(12, 3, GL)
    i.p(11, 4, GL)
    return i


def cross():
    i = Icon()

    def x(s):
        s.stroke([(3, 3), (12, 12)], RD, 3)
        s.stroke([(12, 3), (3, 12)], RD, 3)

    i.layer(x)
    return i


def mouse(left=False, right=False, wheel=False):
    """The approved mouse (2026-10-05): stubby, white with a dark outline, the button in use flat blue."""
    i = Icon()
    shape = {1: (4, 11), 2: (3, 12), 13: (3, 12), 14: (4, 11)}
    for y in range(3, 13):
        shape[y] = (2, 13)
    for y, (x0, x1) in shape.items():
        i.rect(x0, y, x1, y, K)
    for y in range(2, 14):
        if y in (2, 13):
            i.rect(4, y, 11, y, W)
        else:
            x0, x1 = shape[y]
            i.rect(x0 + 1, y, x1 - 1, y, W)
    i.hline(3, 12, 7, K)
    i.vline(6, 2, 6, K)
    i.vline(9, 2, 6, K)
    if left:
        i.rect(3, 3, 5, 6, B)
        i.rect(4, 2, 5, 2, B)
    if right:
        i.rect(10, 3, 12, 6, B)
        i.rect(10, 2, 11, 2, B)
    if wheel:
        i.rect(7, 2, 8, 6, B)
    i.hline(4, 11, 12, S)
    i.p(12, 11, S)
    i.p(3, 12, S)
    return i


def keycap():
    """The approved key (2026-10-05), without its letter: the game writes the name on it. Nine-sliced at 5 pixels."""
    i = Icon()
    outer = {1: (3, 12), 14: (3, 12)}
    for y in range(2, 14):
        outer[y] = (1, 14)
    for y, (x0, x1) in outer.items():
        i.rect(x0, y, x1, y, K)
    for y in range(2, 12):
        i.rect(2 if y > 2 else 3, y, 13 if y > 2 else 12, y, W)
    i.rect(2, 12, 13, 13, S)
    i.hline(2, 13, 13, E)
    i.p(2, 3, (255, 255, 255))
    return i


def drag_mark():
    """What dragging looks like, on the dark chips: a trail of dots and an arrowhead. 10 x 8."""
    i = Icon(10, 8)
    for k, x in enumerate((0, 2, 4)):
        i.p(x, 4, CY)
    i.stroke([(6, 1), (9, 4), (6, 7)], CY, 1)
    i.hline(5, 8, 4, CY)
    return i


def paste():
    """A block coming down onto the world: an arrow over a slab."""
    i = Icon()
    i.layer(lambda s: s.rect(1, 12, 14, 14, W))
    i.layer(lambda s: (s.rect(6, 1, 9, 6, B), s.poly([(2.5, 6), (13.5, 6), (8, 11.5)], B)))
    return i


def scroll_mark():
    """What scrolling looks like on the dark chips: an arrowhead up and one down. 5 x 11."""
    i = Icon(5, 11)
    for k in range(3):
        i.hline(2 - k, 2 + k, k, CY)
        i.hline(2 - k, 2 + k, 10 - k, CY)
    return i


def cursor():
    """The mouse pointer of the lessons: the tip is the pixel at (3, 1)."""
    i = Icon()
    i.layer(lambda s: s.poly([(3, 1.2), (3, 13.2), (5.9, 10.4), (8.2, 14.6), (10.4, 13.6), (8.1, 9.4), (12, 9.4)], W))
    return i


def play():
    i = Icon()
    i.layer(lambda s: s.poly([(4, 2.2), (4, 13.8), (13.2, 8)], B))
    return i


def pause():
    i = Icon()
    i.layer(lambda s: (s.rect(3, 3, 6, 12, B), s.rect(9, 3, 12, 12, B)))
    return i


def step_next():
    i = Icon()
    i.layer(lambda s: (s.poly([(2, 3), (2, 13), (9.5, 8)], B), s.rect(11, 3, 13, 12, B)))
    return i


RESTART_ROWS = [
    "................",
    "......##........",
    "......####......",
    ".....#######....",
    "....########....",
    "...##.####......",
    "..###.##........",
    "..##............",
    "..##........##..",
    "..##........##..",
    "..###......###..",
    "...##......##...",
    "....########....",
    ".....######.....",
]


def restart():
    """A 2 px ring open at the top right with a triangle head at its top end pointing right (clockwise), flat blue like the sweep arrow. Hand-placed: a coarse arc keeps the pixels clean."""
    sub = Icon()
    for y, row in enumerate(RESTART_ROWS):
        for x, ch in enumerate(row):
            if ch == "#":
                sub.p(x, y + 1, B)
    i = Icon()
    i.put(sub.outlined(K))
    return i


QMARK_ROWS = [
    ".####.",
    "##..##",
    "##..##",
    "....##",
    "...##.",
    "..##..",
    "..##..",
    "......",
    "..##..",
    "..##..",
]


def help_():
    """A white disc with a bold blue question mark; a flat shade plane along the bottom, like the mouse's."""
    i = Icon()
    disc = {(x, y) for y in range(16) for x in range(16) if (x + 0.5 - 8) ** 2 + (y + 0.5 - 8) ** 2 <= 6.8 ** 2}
    sub = Icon()
    for (x, y) in disc:
        sub.p(x, y, S if y >= 12 else W)
    i.put(sub.outlined(K))
    for dy, row in enumerate(QMARK_ROWS):
        for dx, ch in enumerate(row):
            if ch == "#":
                i.p(5 + dx, 2 + dy, B)
    return i


ICONS = [
    ("folder", folder), ("move", move), ("layers", layers), ("hammer", hammer), ("list", list_), ("save", save),
    ("gear", gear), ("eye", eye), ("eye_off", eye_off), ("trash", trash), ("undo", undo), ("redo", lambda: undo().mirrored()),
    ("wand", wand), ("cube", cube), ("sweep", sweep), ("select", select), ("check", check), ("cross", cross),
    ("mouse_left", lambda: mouse(left=True)), ("mouse_right", lambda: mouse(right=True)), ("mouse_wheel", lambda: mouse(wheel=True)), ("mouse_none", lambda: mouse()),
    ("keycap", keycap), ("mark_drag", drag_mark), ("mark_scroll", scroll_mark), ("paste", paste),
    ("cursor", cursor), ("play", play), ("pause", pause), ("step_next", step_next), ("step_prev", lambda: step_next().mirrored()), ("restart", restart), ("help", help_), ("house", house),
]


def sheet():
    cols = 8
    rows = (len(ICONS) + cols - 1) // cols
    im = Image.new("RGBA", (cols * 16, rows * 16), (0, 0, 0, 0))
    meta = {}
    for n, (name, fn) in enumerate(ICONS):
        icon = fn()
        x, y = (n % cols) * 16, (n // cols) * 16
        im.alpha_composite(icon.image(), (x, y))
        meta[name] = {"x": x, "y": y, "w": icon.w, "h": icon.h}
    return im, meta


def preview(path, scale=6):
    im, meta = sheet()
    cols = 8
    rows = (len(ICONS) + cols - 1) // cols
    pad = 6
    cell = 16 * scale + pad
    out = Image.new("RGBA", (cols * cell + pad, rows * cell + pad + 40), (14, 42, 71, 255))
    small = Image.new("RGBA", (cols * cell + pad, 40), (10, 27, 48, 255))
    for n, (name, fn) in enumerate(ICONS):
        icon = fn().image()
        x, y = pad + (n % cols) * cell, pad + (n // cols) * cell
        # on the dark base of the wheel, and a lit wedge half
        bg = Image.new("RGBA", (16 * scale, 16 * scale), (10, 27, 48, 255) if n % 2 == 0 else (127, 227, 255, 255))
        bg.alpha_composite(icon.resize((16 * scale, 16 * scale), Image.NEAREST))
        out.alpha_composite(bg, (x, y))
        small.alpha_composite(icon, (pad + n * 20, 10))
    out.alpha_composite(small, (0, rows * cell + pad))
    path.parent.mkdir(parents=True, exist_ok=True)
    out.save(path)


def build():
    im, meta = sheet()
    SHIP.mkdir(parents=True, exist_ok=True)
    im.save(SHIP / "pixel.png")
    (SHIP / "pixel.json").write_text(json.dumps({"cell": 16, "size": [im.width, im.height], "sprites": meta}, indent=1))
    print(len(meta), "sprites,", im.width, "x", im.height, "in", SHIP)


if __name__ == "__main__":
    if sys.argv[1:] == ["preview"]:
        preview(OUT / "pixel_all.png")
        print("wrote", OUT / "pixel_all.png")
    elif sys.argv[1:] == ["build"]:
        build()
    else:
        print(__doc__)
