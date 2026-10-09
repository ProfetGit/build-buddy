#!/usr/bin/env python3
"""Build Buddy icon: a blueprint ghost of a little house, built block by block (pack-icon-animation skill, pure 2D).

No Blockbench: every frame is ray-cast on a 128 px canvas from boxes in world units (24 px per unit, 2:1 isometric,
12x12 texel textures at 2 px per texel), so squash, stairs and the ghost lines stay pixel-crisp. Boxes are either solid
(textured) or ghost (blueprint lines + hatched translucent fill). The loop (4 s, 20 fps, 80 frames): the ghost house
shimmers, eight pieces drop onto their ghosts (four walls, then the four stairs of the roof), each snaps with a squash,
a flash and a ring; the house is done, a check pops and the shine runs over it; then it goes back to a ghost.

  python3 dev/make_icon.py [--options]

Textures: dev/make_textures.py -> dev/icon/sprites. Outputs in dev/icon/out/ (icon-animated.gif 256 px, icon-512.png),
the jar icon src/main/resources/assets/buildbuddy/icon.png, dev/icon/contact.png.
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ICON = ROOT / "dev" / "icon"
SPR = ICON / "sprites"
OUT = ICON / "out"
MOD_ICON = ROOT / "src/main/resources/assets/buildbuddy/icon.png"
N = 128
U = 24                      # px per unit along x and z (and up)
HU = U // 2
OX, OY = 64, 68             # screen position of world (0, 0, 0)
import os
TICK = 20                       # the design's step rate: stepped beats (blink, sparks) hold for 1/20 s
FPS = int(os.environ.get("CYANO_FPS", TICK))
FRAMES = int(4.0 * FPS)
LEN = FRAMES / FPS
STILL_FRAME = 28
GIF_SIZE, GIF_LIMIT = 256, 256 * 1024

H = lambda s: tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))
NAVY, GRID, GRID_MAJOR = H("#14385C"), H("#1E4E7E"), H("#28608F")
LINE, CYAN, DIM = H("#E8F6FF"), H("#7FE3FF"), H("#8BB6D8")
INK = H("#0A1B30")
SHADE_TINT = np.array(H("#0E2042"), float)
FACE_K = {0: 0.64, 1: 1.0, 2: 0.82}          # +x (right), +y (top), +z (left)
GHOST_FILL = {0: 0.22, 1: 0.40, 2: 0.30}
GHOST_FILL_RGB = np.array(H("#5FC3F0"), float)

TEX = {n: np.array(Image.open(SPR / f"{n}.png").convert("RGBA")) for n in ("planks", "window", "brick")}

T = dict(shimmer=0.10, drop=[0.45, 0.65, 0.85, 1.05, 1.60, 1.76, 1.92, 2.08], fall=0.15, pre=0.15,
         done=2.40, badge=2.50, reset=3.30, reset_gap=0.05)


# ---- the house -------------------------------------------------------------------------------------------------------

class Piece:
    def __init__(self, name, cell, boxes, tex):
        self.name, self.cell, self.boxes, self.tex = name, cell, boxes, tex      # tex: {face: texture name}
        self.pivot = (cell[0] + 0.5, cell[1], cell[2] + 0.5)


def wall(name, cx, cz, window_face=None):
    tex = {0: "planks", 1: "planks", 2: "planks"}
    if window_face is not None:
        tex[window_face] = "window"
    return Piece(name, (cx, 0, cz), [((cx, 0, cz), (cx + 1, 1, cz + 1))], tex)


def stair(name, cx, cz):
    hi = ((cx, 1.5, cz), (cx + 1, 2, cz + 0.5)) if cz == 1 else ((cx, 1.5, cz + 0.5), (cx + 1, 2, cz + 1))
    return Piece(name, (cx, 1, cz), [((cx, 1, cz), (cx + 1, 1.5, cz + 1)), hi], {0: "brick", 1: "brick", 2: "brick"})


PIECES = [wall("w00", 0, 0), wall("w01", 0, 1, 2), wall("w11", 1, 1), wall("w10", 1, 0, 0),
          stair("r00", 0, 0), stair("r10", 1, 0), stair("r01", 0, 1), stair("r11", 1, 1)]

YY, XX = np.mgrid[0:N, 0:N]
PX, PY = XX + 0.5 - OX, YY + 0.5 - OY
X0 = (PX / U + PY / HU) / 2
Z0 = (PY / HU - PX / U) / 2
EPS = 0.5 / U


def cast(lo, hi):
    """Nearest hit of one box: (hit mask, depth s, face id, x, y, z). s grows away from the viewer."""
    sin = np.stack([X0 - hi[0], np.full_like(X0, -hi[1]), Z0 - hi[2]])
    sout = np.stack([X0 - lo[0], np.full_like(X0, -lo[1]), Z0 - lo[2]])
    s = sin.max(0)
    face = sin.argmax(0)
    hit = s < sout.min(0) - 1e-9
    return hit, s, face, X0 - s, -s, Z0 - s


def sample(tex, u, v):
    t = TEX[tex]
    ix = np.clip((u * 12).astype(int), 0, 11)
    iy = np.clip((v * 12).astype(int), 0, 11)
    return t[iy, ix, :3].astype(float)


def shade(rgb, face):
    k = FACE_K[face]
    return rgb * k + SHADE_TINT * (1 - k) * 0.5


def solid_piece(p, move=(0, 0, 0), scale=(1, 1, 1)):
    """Colour (N,N,3), depth (N,N), edge mask for the solid piece."""
    sx, sy, sz = scale
    px, py, pz = p.pivot
    col = np.zeros((N, N, 3))
    depth = np.full((N, N), np.inf)
    for lo, hi in p.boxes:
        wlo = (px + (lo[0] - px) * sx + move[0], py + (lo[1] - py) * sy + move[1], pz + (lo[2] - pz) * sz + move[2])
        whi = (px + (hi[0] - px) * sx + move[0], py + (hi[1] - py) * sy + move[1], pz + (hi[2] - pz) * sz + move[2])
        hit, s, face, x, y, z = cast(wlo, whi)
        hit &= s < depth
        lx = px + (x - move[0] - px) / sx
        ly = py + (y - move[1] - py) / sy
        lz = pz + (z - move[2] - pz) / sz
        cx, cy, cz = p.cell
        fx, fy, fz = lx - cx, ly - cy, lz - cz
        c = np.zeros((N, N, 3))
        for f in (0, 1, 2):
            m = hit & (face == f)
            if not m.any():
                continue
            if f == 0:
                u, v = 1 - fz, 1 - fy
            elif f == 2:
                u, v = fx, 1 - fy
            else:
                u, v = fx, fz
            c[m] = shade(sample(p.tex[f], u, v), f)[m]
        for fidx, coords in ((0, ((z, wlo[2], whi[2]), (y, wlo[1], whi[1]))), (1, ((x, wlo[0], whi[0]), (z, wlo[2], whi[2]))),
                             (2, ((x, wlo[0], whi[0]), (y, wlo[1], whi[1])))):
            m = hit & (face == fidx)
            e = np.zeros((N, N), bool)
            for a, l, h_ in coords:
                e |= (a - l < EPS) | (h_ - a < EPS)
            c[m & e] = c[m & e] * 0.72 + np.array(INK, float) * 0.28
        col[hit] = c[hit]
        depth[hit] = s[hit]
    return col, depth


def ghost_piece(p, light=0.0, lines_only=False):
    """Blueprint look: hatched translucent fill, 1 px lines on every face boundary. Returns colour, alpha, depth."""
    col = np.zeros((N, N, 3))
    alpha = np.zeros((N, N))
    depth = np.full((N, N), np.inf)
    hatch = ((XX + YY) % 6 == 0)
    for lo, hi in p.boxes:
        hit, s, face, x, y, z = cast(lo, hi)
        hit &= s < depth
        c = np.zeros((N, N, 3))
        a = np.zeros((N, N))
        for f, coords in ((0, ((z, lo[2], hi[2]), (y, lo[1], hi[1]))), (1, ((x, lo[0], hi[0]), (z, lo[2], hi[2]))),
                          (2, ((x, lo[0], hi[0]), (y, lo[1], hi[1])))):
            m = hit & (face == f)
            e = np.zeros((N, N), bool)
            for av, l, h_ in coords:
                e |= (av - l < EPS) | (h_ - av < EPS)
            fill = GHOST_FILL[f] + 0.10 * hatch
            line = np.array(LINE if f == 1 else CYAN, float)
            line = line * (1 - light) + 255 * light
            c[m] = GHOST_FILL_RGB
            a[m] = 0.0 if lines_only else np.clip(fill[m], 0, 0.95)
            c[m & e] = line
            a[m & e] = 1.0
        col[hit], alpha[hit], depth[hit] = c[hit], a[hit], s[hit]
    return col, alpha, depth


# ---- animation tracks ------------------------------------------------------------------------------------------------

SQUASH = [(1.30, 0.62, 1.30), (1.12, 0.90, 1.12), (0.94, 1.12, 0.94), (1.03, 0.96, 1.03)]
FLASH = [0.60, 0.32, 0.12]
SPAWN_H = 0.55


def piece_state(i, t):
    """('ghost'|'solid'|'none', ghost light, solid move y, solid scale, solid flash) for piece i at time t."""
    td = T["drop"][i]
    tl = td + T["fall"]
    rank = len(PIECES) - 1 - i
    tr = T["reset"] + T["reset_gap"] * rank
    f = lambda x: int(math.floor(x * TICK + 1e-6))
    if t < td - 1e-9:
        pre = T["pre"]
        light = 0.0
        if t >= td - pre:
            light = 0.75 if (f(t) - f(td - pre)) % 2 == 0 else 0.35
        return dict(ghost=True, light=light, solid=False)
    if t < tl - 1e-9:
        k = (t - td) / T["fall"]
        y = SPAWN_H * (1 - k * k)
        sc = (0.55, 0.55, 0.55) if f(t) == f(td) else (1, 1, 1)
        return dict(ghost=True, light=0.0, lines=True, solid=True, move=y, scale=sc, flash=0.5 if f(t) == f(td) else 0.0)
    if t < tr - 1e-9:
        n = (t - tl) * TICK + 1e-6
        sc = tuple(float(np.interp(n, range(len(SQUASH) + 1), [q[c] for q in SQUASH] + [1.0])) for c in range(3))
        fl = float(np.interp(n, range(len(FLASH) + 1), FLASH + [0.0]))
        return dict(ghost=False, light=0.0, solid=True, move=0.0, scale=sc, flash=fl)
    n = f(t) - f(tr)
    if n == 0:
        return dict(ghost=False, light=0.0, solid=True, move=0.0, scale=(1.06, 1.06, 1.06), flash=0.85)
    return dict(ghost=True, light=max(0.0, 0.8 - 0.25 * (n - 1)), solid=False)


# ---- fx ---------------------------------------------------------------------------------------------------------------

SPARK = ["..w..", ".wWw.", "wWWWw", ".wWw.", "..w.."]
SPARK_S = ["..w..", "..W..", "wWWWw", "..W..", "..w.."]
CHECK = ["......GG", ".....GGG", "GG..GGG.", "GGGGGG..", ".GGGG...", "..GG...."]
GREEN = {"G": H("#46C46A")}


def paste_grid(rgb, a, rows, cols, x, y, scale=1):
    for ry, row in enumerate(rows):
        for rx, ch in enumerate(row):
            if ch in cols:
                c = cols[ch]
                for dy in range(scale):
                    for dx in range(scale):
                        xx, yy = x + rx * scale + dx, y + ry * scale + dy
                        if 0 <= xx < N and 0 <= yy < N:
                            rgb[yy, xx] = c
                            a[yy, xx] = 1.0


def badge(rgb, a, k):
    """Green check with an ink outline and a light top edge, popping in at scale k (0..1.3) at the top right of the house."""
    if k <= 0:
        return
    size = 2 if k >= 0.7 else 1
    w, h = 8 * size, 6 * size
    layer = np.zeros((N, N, 3))
    la = np.zeros((N, N))
    ox = OX + 40 - w // 2 + (0 if size == 2 else 4)
    oy = OY - 52 + (0 if size == 2 else 3)
    paste_grid(layer, la, CHECK, GREEN, ox, oy, size)
    m = la > 0
    lit = np.zeros_like(m)
    lit[1:] = m[1:] & ~m[:-1]
    dark = np.zeros_like(m)
    dark[:-1] = m[:-1] & ~m[1:]
    layer[lit] = H("#9CF5B0")
    layer[dark] = H("#2A8A48")
    ring = np.zeros_like(m)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            ring |= np.roll(np.roll(m, dy, 0), dx, 1)
    ring &= ~m
    rgb[ring] = H("#0C3320")
    a[ring] = 1.0
    rgb[m] = layer[m]
    a[m] = 1.0


def spark(rgb, a, cx, cy, step):
    rows = SPARK if step == 1 else SPARK_S if step == 2 else None
    if rows is None:
        return
    cols = {"w": CYAN, "W": LINE}
    paste_grid(rgb, a, rows, cols, cx - 2, cy - 2)


def ring_fx(rgb, a, cell, n):
    """Snap ring on the floor under a wall piece: a 2:1 diamond growing over six frames."""
    if n >= 6:
        return
    ni = int(n)
    cx = OX + U * (cell[0] - cell[2])
    cy = OY + HU * (cell[0] + cell[2] + 1) - U * cell[1]
    r = float(np.interp(n, range(6), [5, 11, 17, 23, 28, 32]))
    d = np.abs(XX + 0.5 - cx) / 2 + np.abs(YY + 0.5 - cy)
    m = np.abs(d - r / 2) < 0.75
    colr = [LINE, LINE, CYAN, CYAN, DIM, DIM][ni]
    rgb[m] = colr
    a[m] = 1.0


def shimmer(light_mask_a, t):
    """A diagonal band that sweeps over the ghost lines: returns an (N,N) mask."""
    k = (t - T["shimmer"]) / 0.32
    if k < 0 or k > 1:
        return None
    pos = -20 + k * (N + 40)
    d = (XX - YY * 0.5) - pos
    return (d > -5) & (d < 5)


def paper_bg(w=N, h=N, ax=0, marks=True):
    """Blueprint paper on a w x h canvas whose art canvas starts at column ax: navy, a grid every 16 px (brighter every 64),
    corner marks, a dashed plot round the footprint."""
    rgb = np.empty((h, w, 3), np.uint8)
    rgb[:] = NAVY
    for i in range(0, h, 16):
        rgb[i, :] = GRID_MAJOR if i % 64 == 0 else GRID
    for j in range(0, w, 16):
        rgb[:, j] = GRID_MAJOR if j % 64 == 0 else GRID
    for i in range(0, h, 64):
        for j in range(0, w, 64):
            rgb[i, max(j - 1, 0):j + 2] = DIM
            rgb[max(i - 1, 0):i + 2, j] = DIM
    m = 6
    if marks:
        for cx, cy, sx, sy in ((m, m, 1, 1), (w - 1 - m, m, -1, 1), (m, h - 1 - m, 1, -1), (w - 1 - m, h - 1 - m, -1, -1)):
            for k in range(6):
                rgb[cy, cx + sx * k] = DIM
                rgb[cy + sy * k, cx] = DIM
    pad = 0.18
    pts = [(-pad, -pad), (2 + pad, -pad), (2 + pad, 2 + pad), (-pad, 2 + pad)]
    scr = [(ax + OX + U * (x - z), OY + HU * (x + z)) for x, z in pts]
    for k in range(4):
        (x0, y0), (x1, y1) = scr[k], scr[(k + 1) % 4]
        steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 2)
        for s in range(steps + 1):
            if (s // 7) % 2 == 0:
                x = int(round(x0 + (x1 - x0) * s / steps))
                y = int(round(y0 + (y1 - y0) * s / steps))
                if 0 <= x < w and 0 <= y < h:
                    rgb[y, x] = H("#3C86B8")
    return rgb


BG = paper_bg()


# ---- one frame --------------------------------------------------------------------------------------------------------

def frame(f):
    """The art layer of frame f: (RGBA uint8, ring/fx drawn in). Transparent where the paper shows through."""
    t = f / FPS
    states = [piece_state(i, t) for i in range(len(PIECES))]
    rgb = np.zeros((N, N, 3))
    a = np.zeros((N, N))

    for i, st in enumerate(states):
        n = (t - T["drop"][i] - T["fall"]) * TICK + 1e-6
        if i < 4 and 0 <= n < 6 and st["solid"] and not st["ghost"]:
            ring_fx(rgb, a, PIECES[i].cell, n)

    best_s = np.full((N, N), np.inf)
    scol = np.zeros((N, N, 3))
    smask = np.zeros((N, N), bool)
    for i, st in enumerate(states):
        if not st["solid"]:
            continue
        c, d = solid_piece(PIECES[i], (0, st["move"], 0), st["scale"])
        if st.get("flash"):
            c = c * (1 - st["flash"]) + 255 * st["flash"]
        m = d < best_s
        best_s[m] = d[m]
        scol[m] = c[m]
        smask |= m & np.isfinite(d)
    # shine over the finished house
    ts = (t - T["done"]) / 0.34
    if 0 <= ts <= 1 and smask.any():
        pos = -30 + ts * (N + 60)
        d = (XX + YY * 0.5) - pos
        band = smask & (d > -6) & (d < 5)
        core = smask & (d > -3) & (d < 2)
        scol[band] = scol[band] * 0.78 + 255 * 0.22
        scol[core] = scol[core] * 0.55 + 255 * 0.45
    ink = np.zeros((N, N), bool)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            ink |= np.roll(np.roll(smask, dy, 0), dx, 1)
    ink &= ~smask
    rgb[ink] = INK
    a[ink] = 1.0
    rgb[smask] = scol[smask]
    a[smask] = 1.0

    # ghosts: nearest ghost layer, over the solid when it is nearer
    g_s = np.full((N, N), np.inf)
    g_c = np.zeros((N, N, 3))
    g_a = np.zeros((N, N))
    for i, st in enumerate(states):
        if not st["ghost"]:
            continue
        c, al, d = ghost_piece(PIECES[i], st["light"], st.get("lines", False))
        m = d < g_s
        g_s[m] = d[m]
        g_c[m] = c[m]
        g_a[m] = al[m]
    sh = shimmer(None, t)
    if sh is not None:
        glow = (g_a >= 0.999) & sh
        g_c[glow] = g_c[glow] * 0.2 + 255 * 0.8
        fill = (g_a > 0) & (g_a < 0.999) & sh
        g_a[fill] = np.clip(g_a[fill] + 0.30, 0, 0.95)
    over = np.isfinite(g_s) & (g_a > 0) & (g_s < best_s)
    base_a = a.copy()
    out_rgb = rgb.copy()
    new_a = g_a + base_a * (1 - g_a)
    mix = np.where(new_a[..., None] > 0, (g_c * g_a[..., None] + rgb * (base_a * (1 - g_a))[..., None]) / np.maximum(new_a[..., None], 1e-9), 0)
    out_rgb[over] = mix[over]
    a = np.where(over, new_a, a)
    rgb = out_rgb

    # sparkles and the check
    bk = (t - T["badge"]) * TICK + 1e-6
    if t >= T["badge"] and t < T["reset"]:
        k = 0.4 if bk < 1 else 1.0
        badge(rgb, a, k)
    if t >= T["reset"]:
        bk2 = (t - T["reset"]) * TICK + 1e-6
        if bk2 < 2:
            badge(rgb, a, 1.0 if bk2 < 1 else 0.4)
    for j, (sx, sy, st0) in enumerate(((-40, -14, 0.0), (44, -4, 0.10), (-6, -46, 0.20), (22, 34, 0.30), (-34, 26, 0.38))):
        k = int(math.floor((t - T["done"] - st0) * TICK + 1e-6))
        step = {0: 2, 1: 1, 2: 1, 3: 2}.get(k)
        if step:
            spark(rgb, a, OX + sx, OY + sy, step)

    out = np.zeros((N, N, 4), np.uint8)
    out[..., :3] = np.round(rgb).astype(np.uint8)
    out[..., 3] = np.round(a * 255).astype(np.uint8)
    return out


def compose(art, size=GIF_SIZE, bg=None):
    base = Image.fromarray(BG if bg is None else bg).convert("RGBA")
    a = art[..., 3:4].astype(float) / 255
    out = np.array(base).astype(float)
    out[..., :3] = art[..., :3] * a + out[..., :3] * (1 - a)
    im = Image.fromarray(np.round(out).astype(np.uint8)[..., :3], "RGB")
    return im.resize((size, size), Image.NEAREST)


def palette_for(code, limit=232, exact=185):
    """The `exact` most frequent colours verbatim, the rest of the pixels folded into limit-exact weighted k-means centres."""
    u, c = np.unique(code, return_counts=True)
    if len(u) <= limit:
        return u
    order = np.argsort(-c)
    keep = u[order[:exact]]
    rest, w = u[order[exact:]], c[order[exact:]].astype(float)
    rgb = np.stack([(rest >> 16) & 255, (rest >> 8) & 255, rest & 255], 1).astype(float)
    k = limit - exact
    cen = rgb[np.argsort(-w)[:k]].copy()
    for _ in range(12):
        near = np.argmin(((rgb[:, None, :] - cen[None]) ** 2).sum(2), 1)
        for j in range(k):
            sel = near == j
            if sel.any():
                cen[j] = (rgb[sel] * w[sel, None]).sum(0) / w[sel].sum()
    cen = np.round(cen).astype(np.uint32)
    extra = (cen[:, 0] << 16) | (cen[:, 1] << 8) | cen[:, 2]
    return np.unique(np.concatenate([keep, extra]))


def write_gif(frames, out):
    """One shared palette (exact for the common colours), slot 255 transparent marks unchanged pixels (disposal 1)."""
    stack = np.stack([np.array(f.convert("RGB")) for f in frames]).astype(np.uint32)
    code = (stack[..., 0] << 16) | (stack[..., 1] << 8) | stack[..., 2]
    colours = palette_for(code)
    pal_rgb = np.stack([(colours >> 16) & 255, (colours >> 8) & 255, colours & 255], 1).astype(float)
    uniq = np.unique(code)
    urgb = np.stack([(uniq >> 16) & 255, (uniq >> 8) & 255, uniq & 255], 1).astype(float)
    nearest = np.argmin(((urgb[:, None, :] - pal_rgb[None]) ** 2).sum(2), 1)
    idx = nearest[np.searchsorted(uniq, code)].astype(np.uint8)
    drift = np.abs(urgb - pal_rgb[nearest]).max()
    pal = [v for c in colours for v in (int(c >> 16) & 255, int(c >> 8) & 255, int(c) & 255)]
    pal += [0, 0, 0] * (255 - len(colours)) + [255, 0, 255]
    ims = []
    for i in range(len(frames)):
        q = idx[i].copy()
        if i:
            q[idx[i] == idx[i - 1]] = 255
        p = Image.fromarray(q, "P")
        p.putpalette(pal)
        ims.append(p)
    ims[0].save(out, save_all=True, append_images=ims[1:], duration=1000 // FPS, loop=0, optimize=True, disposal=1, transparency=255)
    back, i = Image.open(out), 0
    for n in range(back.n_frames):
        back.seek(n)
        got = np.array(back.convert("RGB"))
        for _ in range(round(back.info["duration"] * FPS / 1000)):
            want = pal_rgb[idx[i]].reshape(got.shape)
            if not np.array_equal(got, want.astype(np.uint8)):
                sys.exit(f"{out.name}: frame {i} decodes differently")
            i += 1
    if i != len(frames):
        sys.exit(f"{out.name}: {i} frames decoded, {len(frames)} expected")
    return len(colours), drift


def render_all():
    return [frame(f) for f in range(FRAMES)]


def sheets(raw, big):
    cols = 10
    rows = -(-len(raw) // cols)
    contact = Image.new("RGB", (cols * 128, rows * 128))
    for i, fr in enumerate(big):
        contact.paste(fr.resize((128, 128), Image.LANCZOS), ((i % cols) * 128, (i // cols) * 128))
    contact.save(ICON / "contact.png")
    small = Image.new("RGB", (cols * 96, rows * 96))
    for i, fr in enumerate(big):
        small.paste(fr.resize((96, 96), Image.LANCZOS), ((i % cols) * 96, (i // cols) * 96))
    small.save(ICON / "contact_96.png")


def main():
    args = sys.argv[1:]
    OUT.mkdir(parents=True, exist_ok=True)
    raw = render_all()
    if not np.array_equal(raw[0], frame(FRAMES)):
        sys.exit("loop seam: frame %d differs from frame 0" % FRAMES)
    big = [compose(r, 512) for r in raw]
    big[STILL_FRAME].save(OUT / "icon-512.png", optimize=True)
    MOD_ICON.parent.mkdir(parents=True, exist_ok=True)
    compose(raw[STILL_FRAME], 128).save(MOD_ICON, optimize=True)
    small = [compose(r, GIF_SIZE) for r in raw]
    out = OUT / "icon-animated.gif"
    n, drift = write_gif(small, out)
    sheets(raw, big)
    size = out.stat().st_size
    print(f"{FRAMES} frames, {n} colours (worst rare-colour drift {drift:.0f}/255) -> {out.relative_to(ROOT)} {size / 1024:.0f} KiB "
          f"({'OK' if size <= GIF_LIMIT else 'OVER'} 256 KiB); seam exact; still = frame {STILL_FRAME}")
    if size > GIF_LIMIT:
        sys.exit(1)


if __name__ == "__main__":
    main()
