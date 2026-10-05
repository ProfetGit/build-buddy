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


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    panel(); inset(); buttons(); tabs(); tooltip(); bar_track(); checkbox(); checkbox_hover(); knob()
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
