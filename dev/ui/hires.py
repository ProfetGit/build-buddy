#!/usr/bin/env python3
"""Draws Cyanotype's interface art at 4 texels per GUI unit and ships it into the mod (style B, redrawn as fine line work).

Everything is drawn as vector shapes at 16 px per unit and reduced 4:1, so lines are 0.3-0.7 of a GUI unit wide and edges
are smooth. The game draws the atlas with a linear sampler (ui/Skin.java), so it stays crisp at any GUI scale. Sizes in
atlas.json are in GUI units (what the layouts use); x, y, w, h are texels of atlas.png.

Run: python3 dev/ui/hires.py   (writes src/main/resources/assets/cyanotype/ui/{atlas.png,atlas.json,grid.png,hatch.png}
and a preview sheet in dev/ui/out/hires_preview.png).  The wheel is not drawn here: it is made at run time for however many
tools there are (ui/WheelArt.java), from the same colours.
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
OUT = HERE.parent.parent / "src/main/resources/assets/cyanotype/ui"
R = 4       # texels per GUI unit in the shipped files
A = 4       # supersampling on top of that
U = R * A   # drawing pixels per GUI unit

NAVY = (20, 56, 92)
PANEL = (14, 42, 71)
DEEP = (10, 27, 48)
LINE = (232, 246, 255)
CYAN = (127, 227, 255)
DIM = (139, 182, 216)
BLUE = (44, 102, 201)
GRID = (30, 78, 126)


class Canvas:
    """A drawing in GUI units; shapes are rasterised at U pixels per unit and composited, so alpha blends over what is below."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (round(w * U), round(h * U)), (0, 0, 0, 0))

    def _draw(self, fn):
        layer = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        fn(ImageDraw.Draw(layer))
        self.img = Image.alpha_composite(self.img, layer)

    def _p(self, x, y):
        return (x * U, y * U)

    @staticmethod
    def _c(color, alpha=1.0):
        return (*color[:3], round(255 * alpha))

    def rect(self, x, y, w, h, fill, alpha=1.0):
        self._draw(lambda d: d.rectangle([x * U, y * U, (x + w) * U - 1, (y + h) * U - 1], fill=self._c(fill, alpha)))

    def frame(self, x, y, w, h, color, lw, alpha=1.0):
        self._draw(lambda d: d.rectangle([x * U, y * U, (x + w) * U - 1, (y + h) * U - 1], outline=self._c(color, alpha), width=max(1, round(lw * U))))

    def poly(self, pts, fill, alpha=1.0):
        self._draw(lambda d: d.polygon([self._p(*p) for p in pts], fill=self._c(fill, alpha)))

    def circle(self, cx, cy, r, fill=None, outline=None, lw=0.5, alpha=1.0):
        box = [(cx - r) * U, (cy - r) * U, (cx + r) * U, (cy + r) * U]
        self._draw(lambda d: d.ellipse(box, fill=self._c(fill, alpha) if fill else None, outline=self._c(outline, alpha) if outline else None, width=max(1, round(lw * U))))

    def erase_circle(self, cx, cy, r):
        mask = Image.new("L", self.img.size, 0)
        ImageDraw.Draw(mask).ellipse([(cx - r) * U, (cy - r) * U, (cx + r) * U, (cy + r) * U], fill=255)
        self.img.paste((0, 0, 0, 0), mask=mask)

    def stroke(self, pts, lw, color, alpha=1.0, closed=False):
        """A polyline with round joints and caps."""
        c = self._c(color, alpha)
        P = [self._p(*p) for p in pts] + ([self._p(*pts[0])] if closed else [])
        r = lw * U / 2

        def go(d):
            d.line(P, fill=c, width=max(1, round(lw * U)), joint="curve")
            for (x, y) in P:
                d.ellipse([x - r, y - r, x + r, y + r], fill=c)

        self._draw(go)

    def arc(self, cx, cy, r, a0, a1, lw, color, alpha=1.0):
        n = max(8, int(abs(a1 - a0) / 4))
        pts = [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]
        self.stroke(pts, lw, color, alpha)

    def dashed(self, x0, y0, x1, y1, lw, color, dash, gap, alpha=1.0, phase=0.0):
        length = math.hypot(x1 - x0, y1 - y0)
        ux, uy = (x1 - x0) / length, (y1 - y0) / length
        t = -phase
        while t < length:
            a, b = max(0, t), min(length, t + dash)
            if b > a:
                self.stroke([(x0 + ux * a, y0 + uy * a), (x0 + ux * b, y0 + uy * b)], lw, color, alpha)
            t += dash + gap

    def rrect(self, x, y, w, h, r, fill=None, outline=None, lw=0.4, alpha=1.0):
        box = [x * U, y * U, (x + w) * U - 1, (y + h) * U - 1]
        self._draw(lambda d: d.rounded_rectangle(box, radius=r * U, fill=self._c(fill, alpha) if fill else None,
                                                 outline=self._c(outline, alpha) if outline else None, width=max(1, round(lw * U))))

    def grad_rrect(self, x, y, w, h, r, top, bottom, alpha=1.0):
        """A rounded rectangle filled with a vertical gradient from the colour {top} to {bottom}."""
        gw, gh = round(w * U), round(h * U)
        grad = Image.new("RGBA", (gw, gh))
        px = grad.load()
        for j in range(gh):
            t = j / max(1, gh - 1)
            c = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (round(255 * alpha),)
            for i in range(gw):
                px[i, j] = c
        mask = Image.new("L", (gw, gh), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, gw - 1, gh - 1], radius=r * U, fill=255)
        layer = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        layer.paste(grad, (round(x * U), round(y * U)), mask)
        self.img = Image.alpha_composite(self.img, layer)

    def masked(self, x, y, w, h, r, draw):
        """Runs draw(canvas) on a copy of the drawing area and keeps only what falls inside the rounded rectangle."""
        tmp = Canvas(self.w, self.h)
        draw(tmp)
        mask = Image.new("L", self.img.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle([x * U, y * U, (x + w) * U - 1, (y + h) * U - 1], radius=r * U, fill=255)
        alpha = ImageChops.multiply(tmp.img.getchannel("A"), mask)
        tmp.img.putalpha(alpha)
        self.img = Image.alpha_composite(self.img, tmp.img)

    def glow(self, x, y, w, h, r, color, spread, alpha=0.6):
        """A soft halo behind a rounded rectangle: the shape drawn, blurred, and laid under what is drawn after."""
        layer = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).rounded_rectangle([x * U, y * U, (x + w) * U, (y + h) * U], radius=r * U, fill=self._c(color, alpha))
        layer = layer.filter(ImageFilter.GaussianBlur(spread * U))
        self.img = Image.alpha_composite(self.img, layer)

    def done(self):
        return self.img.resize((round(self.w * R), round(self.h * R)), Image.LANCZOS)


SPRITES = {}  # name -> (Image, logical w, logical h, slice)


def add(name, canvas, slice_=0):
    SPRITES[name] = (canvas.done(), canvas.w, canvas.h, slice_)


# ---- panels and controls ---------------------------------------------------------------------------------------------

def panel():
    c = Canvas(24, 24)
    c.rect(0.25, 0.25, 23.5, 23.5, PANEL)
    c.frame(0.25, 0.25, 23.5, 23.5, CYAN, 0.5)
    c.frame(2.1, 2.1, 19.8, 19.8, DIM, 0.3, 0.45)
    for (x, y, dx, dy) in [(0.25, 0.25, 1, 1), (23.75, 0.25, -1, 1), (0.25, 23.75, 1, -1), (23.75, 23.75, -1, -1)]:
        c.stroke([(x + dx * 4.2, y), (x, y), (x, y + dy * 4.2)], 0.9, LINE)
    add("panel", c, 5)


def inset():
    c = Canvas(16, 16)
    c.rect(0.25, 0.25, 15.5, 15.5, DEEP)
    c.frame(0.25, 0.25, 15.5, 15.5, DIM, 0.45, 0.85)
    add("inset", c, 3)


def buttons():
    c = Canvas(16, 16)
    c.rect(0.25, 0.25, 15.5, 15.5, NAVY)
    c.frame(0.25, 0.25, 15.5, 15.5, CYAN, 0.6)
    c.stroke([(1.5, 1.4), (14.5, 1.4)], 0.3, LINE, 0.35)
    add("button_0", c, 3)
    c = Canvas(16, 16)
    c.rect(0.25, 0.25, 15.5, 15.5, BLUE)
    c.frame(0.25, 0.25, 15.5, 15.5, LINE, 0.7)
    for (x, y) in [(1.7, 1.7), (14.3, 1.7), (1.7, 14.3), (14.3, 14.3)]:
        c.circle(x, y, 0.5, fill=LINE)
    add("button_1", c, 3)
    c = Canvas(16, 16)
    c.rect(0.25, 0.25, 15.5, 15.5, LINE)
    c.frame(0.25, 0.25, 15.5, 15.5, CYAN, 0.7)
    add("button_2", c, 3)


def tabs():
    c = Canvas(16, 13)
    c.rect(0.25, 0.25, 15.5, 13, BLUE)
    c.frame(0.25, 0.25, 15.5, 13, LINE, 0.7)
    add("tab_on", c, 3)
    c = Canvas(16, 13)
    c.rect(0.25, 0.25, 15.5, 12.5, DEEP)
    c.frame(0.25, 0.25, 15.5, 12.5, CYAN, 0.5, 0.85)
    add("tab_off", c, 3)


def tooltip():
    c = Canvas(16, 16)
    c.rect(0.25, 0.25, 15.5, 15.5, DEEP, 0.96)
    c.frame(0.25, 0.25, 15.5, 15.5, CYAN, 0.5)
    c.stroke([(0.25, 3), (0.25, 0.25), (3, 0.25)], 0.9, LINE)
    add("tooltip", c, 3)


def bar_track():
    c = Canvas(16, 8)
    c.rect(0.25, 0.25, 15.5, 7.5, DEEP)
    c.frame(0.25, 0.25, 15.5, 7.5, LINE, 0.6)
    add("bar_track", c, 3)


def checkbox():
    for name, on in (("checkbox_off", False), ("checkbox_on", True)):
        c = Canvas(10, 10)
        c.rect(0.6, 0.6, 8.8, 8.8, DEEP, 0.9)
        k = 0.6
        for (x0, y0, x1, y1) in [(k, k, 10 - k, k), (10 - k, k, 10 - k, 10 - k), (10 - k, 10 - k, k, 10 - k), (k, 10 - k, k, k)]:
            c.dashed(x0, y0, x1, y1, 0.55, CYAN, 1.6, 0.9)
        if on:
            c.stroke([(2.5, 5.2), (4.3, 7.0), (7.6, 2.9)], 1.2, LINE)
        add(name, c)


def checkbox_hover():
    c = Canvas(12, 12)
    c.frame(0.3, 0.3, 11.4, 11.4, CYAN, 0.6, 0.9)
    add("checkbox_hover", c)


def knob():
    c = Canvas(5, 13)
    c.rect(0.25, 0.25, 4.5, 12.5, LINE)
    c.frame(0.25, 0.25, 4.5, 12.5, CYAN, 0.5)
    c.stroke([(2.5, 2.6), (2.5, 10.4)], 0.5, NAVY, 0.7)
    add("knob", c)


# ---- tiles (repeat in the game) --------------------------------------------------------------------------------------

def grid_tile():
    """4 x 4 units: a fine line on the left and top; tiled over a panel it makes the blueprint paper."""
    c = Canvas(4, 4)
    c.rect(0, 0, 4, 0.3, GRID, 0.85)
    c.rect(0, 0, 0.3, 4, GRID, 0.85)
    return c.done()


def hatch_tile():
    """4 x 4 units of diagonal stripes for the progress fill."""
    c = Canvas(4, 4)
    c.rect(0, 0, 4, 4, (52, 150, 235))
    for k in (-4, 0, 4):
        c.poly([(k, 4), (k + 1.6, 4), (k + 5.6, 0), (k + 4, 0)], CYAN)
    # the tile wraps: draw the stripes again shifted so the diagonal is continuous at its edges
    return c.done()


# ---- icons: 14 x 14 units, light on navy and dark on the lit wedge -------------------------------------------------------

def icon(name, draw):
    for suffix, main, detail in (("", LINE, CYAN), ("_dark", DEEP, (28, 84, 150))):
        c = Canvas(14, 14)
        draw(c, main, detail)
        add("icon_" + name + suffix, c)


def arrowhead(c, x, y, dx, dy, size, color):
    px, py = -dy, dx
    c.poly([(x + dx * size, y + dy * size), (x - dx * size * 0.2 + px * size * 0.85, y - dy * size * 0.2 + py * size * 0.85),
            (x - dx * size * 0.2 - px * size * 0.85, y - dy * size * 0.2 - py * size * 0.85)], color)


def i_move(c, m, d):
    c.stroke([(7, 2.8), (7, 11.2)], 1.3, m)
    c.stroke([(2.8, 7), (11.2, 7)], 1.3, m)
    for (dx, dy) in [(0, -1), (0, 1), (-1, 0), (1, 0)]:
        arrowhead(c, 7 + dx * 4.3, 7 + dy * 4.3, dx, dy, 2.4, m)
    c.circle(7, 7, 1.1, fill=d)


def i_rotate(c, m, d):
    c.arc(7, 7, 4.5, 215, 470, 1.5, m)
    ang = math.radians(470)
    x, y = 7 + 4.5 * math.cos(ang), 7 + 4.5 * math.sin(ang)
    tx, ty = -math.sin(ang), math.cos(ang)
    arrowhead(c, x + tx * 0.5, y + ty * 0.5, tx, ty, 2.7, m)
    c.circle(7, 7, 1.2, fill=d)


def i_mirror(c, m, d):
    c.dashed(7, 1.4, 7, 12.6, 1.0, d, 1.4, 1.0)
    c.stroke([(1.6, 3), (5.6, 7), (1.6, 11)], 1.2, m, closed=True)
    c.poly([(12.4, 3), (8.4, 7), (12.4, 11)], m)


def i_layers(c, m, d):
    top = [(7, 1.4), (12.6, 4.2), (7, 7), (1.4, 4.2)]
    c.poly(top, m)
    c.stroke([(1.4, 7), (7, 9.8), (12.6, 7)], 1.2, m)
    c.stroke([(1.4, 9.8), (7, 12.6), (12.6, 9.8)], 1.2, m)


def i_select(c, m, d):
    k = 2.2
    for (x0, y0, x1, y1) in [(k, k, 14 - k, k), (14 - k, k, 14 - k, 14 - k), (14 - k, 14 - k, k, 14 - k), (k, 14 - k, k, k)]:
        c.dashed(x0, y0, x1, y1, 1.0, m, 1.8, 1.2)
    for (x, y) in [(k, k), (14 - k, k), (14 - k, 14 - k), (k, 14 - k)]:
        c.rect(x - 1.1, y - 1.1, 2.2, 2.2, m)


def i_save(c, m, d):
    c.poly([(1.8, 1.8), (10.6, 1.8), (12.2, 3.4), (12.2, 12.2), (1.8, 12.2)], m)
    c.rect(3.6, 1.8, 5.2, 3.4, d)
    c.rect(3.2, 7.4, 7.6, 4.8, d)
    c.rect(7.2, 2.4, 1.0, 2.2, m)


def i_folder(c, m, d):
    c.poly([(1.4, 2.8), (5.6, 2.8), (7, 4.4), (12.6, 4.4), (12.6, 11.8), (1.4, 11.8)], m)
    c.stroke([(1.4, 6.3), (12.6, 6.3)], 0.8, d)


def i_list(c, m, d):
    c.stroke([(2.2, 1.6), (11.8, 1.6), (11.8, 12.4), (2.2, 12.4)], 1.2, m, closed=True)
    for y in (4.6, 7.0, 9.4):
        c.circle(4.6, y, 0.7, fill=d)
        c.stroke([(6.4, y), (9.8, y)], 1.0, m)


def i_eye(c, m, d):
    top = [(1.2 + i * 11.6 / 12, 7 - 3.6 * math.sin(math.pi * i / 12)) for i in range(13)]
    bot = [(1.2 + i * 11.6 / 12, 7 + 3.6 * math.sin(math.pi * i / 12)) for i in range(13)]
    c.stroke(top + bot[::-1], 1.2, m, closed=True)
    c.circle(7, 7, 2.2, fill=m)
    c.circle(7, 7, 0.9, fill=d)


def i_eye_off(c, m, d):
    """A closed eye: the lid line and lashes."""
    c.stroke([(1.2, 6.4), (3.6, 8.6), (7, 9.8), (10.4, 8.6), (12.8, 6.4)], 1.2, m)
    for (x0, y0, x1, y1) in [(3.2, 8.6, 2.2, 10.4), (7, 9.8, 7, 11.8), (10.8, 8.6, 11.8, 10.4)]:
        c.stroke([(x0, y0), (x1, y1)], 1.0, m)


def i_trash(c, m, d):
    c.stroke([(2.2, 3.6), (11.8, 3.6)], 1.2, m)
    c.stroke([(5.4, 3.6), (5.4, 2.0), (8.6, 2.0), (8.6, 3.6)], 1.1, m)
    c.stroke([(3.2, 4.6), (10.8, 4.6), (10.0, 12.4), (4.0, 12.4)], 1.2, m, closed=True)
    for x in (5.6, 7.0, 8.4):
        c.stroke([(x, 6.4), (x, 10.6)], 0.8, d)


def i_plus(c, m, d):
    c.rect(5.8, 2.2, 2.4, 9.6, m)
    c.rect(2.2, 5.8, 9.6, 2.4, m)


def i_check(c, m, d):
    c.stroke([(2.4, 7.4), (5.6, 10.6), (11.8, 3.6)], 1.9, m)


def i_cross(c, m, d):
    c.stroke([(3, 3), (11, 11)], 1.9, m)
    c.stroke([(11, 3), (3, 11)], 1.9, m)


def i_arrow(c, m, d):
    c.poly([(7, 1.4), (12, 6.8), (8.4, 6.8), (8.4, 12.6), (5.6, 12.6), (5.6, 6.8), (2, 6.8)], m)


def curl(c, m, d, flip):
    """A curved arrow going back over the top (undo); flipped left to right it goes forward (redo)."""
    def X(x):
        return 14 - x if flip else x
    cx, cy, r = 7.0, 8.0, 4.2
    pts = []
    for i in range(0, 41):
        a = math.radians(35 - 215 * i / 40)
        pts.append((X(cx + r * math.cos(a)), cy + r * math.sin(a)))
    c.stroke(pts, 1.5, m)
    end = math.radians(35 - 215)
    ex, ey = cx + r * math.cos(end), cy + r * math.sin(end)
    tx, ty = math.sin(end), -math.cos(end)
    tx = -tx if flip else tx
    arrowhead(c, X(ex) + tx * 0.6, ey + ty * 0.6, tx, ty, 2.7, m)
    c.circle(X(cx + r * math.cos(math.radians(35))), cy + r * math.sin(math.radians(35)), 0.9, fill=d)


def i_undo(c, m, d):
    curl(c, m, d, False)


def i_redo(c, m, d):
    curl(c, m, d, True)


def i_wand(c, m, d):
    """Smart Pick: a wand with a sparkle at its tip."""
    c.stroke([(2.2, 12.0), (8.4, 5.8)], 1.9, m)
    c.stroke([(2.2, 12.0), (3.6, 10.6)], 1.9, d)
    sx, sy = 10.2, 3.8
    c.poly([(sx, sy - 3.0), (sx + 0.9, sy - 0.9), (sx + 3.0, sy), (sx + 0.9, sy + 0.9), (sx, sy + 3.0), (sx - 0.9, sy + 0.9), (sx - 3.0, sy), (sx - 0.9, sy - 0.9)], m)
    c.circle(3.6, 4.0, 0.8, fill=m)
    c.circle(11.6, 10.8, 0.8, fill=m)


def i_hammer(c, m, d):
    """Auto-place: a hammer."""
    c.stroke([(2.4, 12.2), (8.6, 6.0)], 1.9, m)
    c.poly([(6.0, 4.6), (9.4, 1.4), (12.8, 4.8), (9.4, 8.2)], m)
    c.stroke([(7.4, 4.6), (9.6, 2.6)], 0.9, d)


def i_cube(c, m, d):
    """Build it myself: a block."""
    c.poly([(7, 1.6), (12.4, 4.5), (7, 7.4), (1.6, 4.5)], m)
    c.stroke([(1.8, 6.2), (6.4, 8.7), (6.4, 12.6), (1.8, 10.1)], 1.1, m, closed=True)
    c.stroke([(12.2, 6.2), (7.6, 8.7), (7.6, 12.6), (12.2, 10.1)], 1.1, m, closed=True)


def i_sweep(c, m, d):
    """Place everything in reach: a row of blocks and a sweeping arrow over them."""
    for x in (1.6, 5.4, 9.2):
        c.rect(x, 9.0, 3.2, 3.2, m)
    c.stroke([(2.2, 6.6), (5.0, 3.4), (9.0, 3.4), (11.4, 6.0)], 1.3, m)
    arrowhead(c, 11.4, 6.0, 0.7, 0.7, 2.8, m)


ICONS = [("move", i_move), ("rotate", i_rotate), ("mirror", i_mirror), ("layers", i_layers), ("select", i_select), ("save", i_save),
         ("folder", i_folder), ("list", i_list), ("eye", i_eye), ("trash", i_trash), ("plus", i_plus), ("check", i_check),
         ("cross", i_cross), ("arrow", i_arrow), ("undo", i_undo), ("redo", i_redo), ("wand", i_wand), ("hammer", i_hammer), ("cube", i_cube), ("sweep", i_sweep), ("eye_off", i_eye_off)]


def make_gear():
    """The gear needs a real hole, which the helpers cannot cut: draw it, then clear the middle."""
    for suffix, main in (("", LINE), ("_dark", DEEP)):
        c = Canvas(14, 14)
        teeth = 8
        pts = []
        for i in range(teeth * 4):
            a = math.pi * 2 * i / (teeth * 4) + math.pi / (teeth * 2)
            r = 6.1 if (i % 4) in (0, 1) else 4.7
            pts.append((7 + r * math.cos(a), 7 + r * math.sin(a)))
        c.poly(pts, main)
        c.circle(7, 7, 4.2, fill=main)
        c.erase_circle(7, 7, 2.0)
        add("icon_gear" + suffix, c)


# ---- shortcut icons: the mouse and the key cap of the cursor hints -----------------------------------------------------

def mouse(name, left=False, right=False, wheel=False, extra=0.0):
    """A mouse seen from above: a soft body, two buttons and a wheel, the one in use lit with a glow."""
    w = 11 + extra
    c = Canvas(w, 15)
    body = (0.5, 0.5, 10, 14)
    c.glow(0.2, 0.2, 10.6, 14.6, 4.8, BLUE, 0.9, 0.35)
    c.grad_rrect(*body, 4.8, (38, 92, 146), (12, 33, 58))
    c.rrect(*body, 4.8, outline=DIM, lw=0.45, alpha=0.95)
    c.stroke([(1.5, 1.2), (4.2, 0.95)], 0.3, LINE, 0.25)
    # the buttons: a line between them and one under them
    c.stroke([(5.5, 0.9), (5.5, 6.3)], 0.35, DIM, 0.85)
    c.stroke([(1.0, 6.4), (10.0, 6.4)], 0.35, DIM, 0.85)
    # the lit button fills its corner of the body, edge to edge, so it follows the curve of the mouse
    for (lit, x0, x1) in ((left, 0.5, 5.5), (right, 5.5, 10.5)):
        if lit:
            def draw(t, x0=x0, x1=x1):
                t.grad_rrect(x0, 0.5, x1 - x0, 5.9, 0.2, (232, 251, 255), (84, 206, 255))
            c.masked(0.5, 0.5, 10, 14, 4.8, draw)
            c.rrect(*body, 4.8, outline=LINE, lw=0.45, alpha=0.55)
            c.stroke([(5.5, 0.9), (5.5, 6.3)], 0.35, BLUE, 0.9)
            c.stroke([(1.0, 6.4), (10.0, 6.4)], 0.35, BLUE, 0.9)
    # the wheel
    if wheel:
        c.glow(4.85, 1.7, 1.3, 3.0, 0.6, CYAN, 0.6, 1.0)
        c.rrect(4.85, 1.7, 1.3, 3.0, 0.65, fill=(255, 255, 255))
    else:
        c.rrect(4.9, 1.8, 1.2, 2.8, 0.6, fill=DEEP, outline=DIM, lw=0.3, alpha=0.95)
    if extra:
        # the wheel turns both ways
        c.stroke([(11.8, 4.9), (12.9, 3.5), (14.0, 4.9)], 0.55, CYAN)
        c.stroke([(11.8, 9.2), (12.9, 10.6), (14.0, 9.2)], 0.55, CYAN)
    add(name, c)


def keycap():
    """A key: a raised face over a darker edge, light on the top. Nine-sliced, so any width."""
    c = Canvas(16, 16)
    c.rrect(0.25, 1.6, 15.5, 14.15, 3.4, fill=(8, 22, 40))
    c.rrect(0.25, 1.6, 15.5, 14.15, 3.4, outline=(26, 66, 108), lw=0.4)
    c.grad_rrect(0.25, 0.25, 15.5, 13.2, 3.4, (46, 104, 160), (24, 66, 108))
    c.rrect(0.25, 0.25, 15.5, 13.2, 3.4, outline=CYAN, lw=0.4, alpha=0.9)
    c.stroke([(3.4, 1.1), (12.6, 1.1)], 0.35, LINE, 0.45)
    add("keycap", c, 4)


def drag_mark():
    """A short arrow with a trail: what dragging looks like."""
    c = Canvas(10, 8)
    for i, a in enumerate((0.35, 0.6, 0.85)):
        c.circle(1.2 + i * 1.7, 4.0, 0.45, fill=CYAN, alpha=a)
    c.stroke([(6.6, 1.9), (9.0, 4.0), (6.6, 6.1)], 0.7, CYAN)
    add("mark_drag", c)


# ---- pack everything -------------------------------------------------------------------------------------------------

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    panel(); inset(); buttons(); tabs(); tooltip(); bar_track(); checkbox(); checkbox_hover(); knob()
    for name, fn in ICONS:
        icon(name, fn)
    make_gear()
    mouse("mouse_none")
    mouse("mouse_left", left=True)
    mouse("mouse_right", right=True)
    mouse("mouse_wheel", wheel=True, extra=3.0)
    keycap()
    drag_mark()
    pad = 4
    names = sorted(SPRITES, key=lambda n: -SPRITES[n][0].height)
    W = 512
    x = y = pad
    rowh = 0
    place = {}
    for n in names:
        im = SPRITES[n][0]
        if x + im.width + pad > W:
            x = pad
            y += rowh + pad
            rowh = 0
        place[n] = (x, y)
        x += im.width + pad
        rowh = max(rowh, im.height)
    H = 1
    while H < y + rowh + pad:
        H *= 2
    atlas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    meta = {}
    for n in names:
        im, lw, lh, sl = SPRITES[n]
        atlas.paste(im, place[n])
        meta[n] = {"x": place[n][0], "y": place[n][1], "w": im.width, "h": im.height, "lw": lw, "lh": lh, "slice": sl}
    atlas.save(OUT / "atlas.png")
    (OUT / "atlas.json").write_text(json.dumps({"r": R, "size": [W, H], "sprites": meta}, indent=1) + "\n")
    grid_tile().save(OUT / "grid.png")
    hatch_tile().save(OUT / "hatch.png")

    # a preview on the panel colour, enlarged, to look at
    prev = Image.new("RGBA", (W * 2, H * 2), (*PANEL, 255))
    big = atlas.resize((W * 2, H * 2), Image.LANCZOS)
    prev.alpha_composite(big)
    (HERE / "out").mkdir(exist_ok=True)
    prev.save(HERE / "out" / "hires_preview.png")
    print(f"{len(meta)} sprites, atlas {W}x{H}, in {OUT}")


if __name__ == "__main__":
    main()
