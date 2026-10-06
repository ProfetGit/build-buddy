#!/usr/bin/env python3
"""Cyanotype description banner (1536x512, 3:1): blueprint paper, the title and tagline, and the house animation from
make_icon.frame() at x4 on the right (its texels are the lettering's 8 px, its grid lines the paper's). Writes
dev/icon/out/banner.png (STILL_FRAME) and banner-animated.gif (shared palette, slot 255 = stepped corners + unchanged
pixels, every frame decode-checked). Copy the GIF to docs/banner.gif.
"""
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_icon as mi  # noqa: E402

ROOT = mi.ROOT
W, H = 1536, 512
S = 4
ART_X = 896
LAYERS = (("banner_title", 8, (72, 150)), ("banner_tagline", 6, (74, 300)))
GIF_LIMIT = 5 * 1024 * 1024
GRID = 8
CORNER = (4, 2, 1, 1)


def sprite(name, scale):
    im = Image.open(mi.SPR / f"{name}.png").convert("RGBA")
    return im.resize((im.width * scale, im.height * scale), Image.NEAREST)


def corner_mask():
    gw, gh = W // GRID, H // GRID
    m = np.ones((gh, gw), bool)
    for row, cut in enumerate(CORNER):
        m[row, :cut] = m[row, gw - cut:] = False
        m[gh - 1 - row, :cut] = m[gh - 1 - row, gw - cut:] = False
    return np.repeat(np.repeat(m, GRID, 0), GRID, 1)


def main():
    paper = mi.paper_bg(W // S, H // S, ART_X // S)
    bg = Image.fromarray(paper).resize((W, H), Image.NEAREST).convert("RGBA")
    for n, sc, pos in LAYERS:
        bg.alpha_composite(sprite(n, sc), pos)
    frames = []
    for f in range(mi.FRAMES):
        art = Image.fromarray(mi.frame(f), "RGBA").resize((mi.N * S, mi.N * S), Image.NEAREST)
        out = bg.copy()
        out.alpha_composite(art, (ART_X, 0))
        frames.append(out.convert("RGB"))

    mask = corner_mask()
    mi.OUT.mkdir(parents=True, exist_ok=True)
    still = np.array(frames[mi.STILL_FRAME].convert("RGBA"))
    still[~mask, 3] = 0
    Image.fromarray(still).save(mi.OUT / "banner.png", optimize=True)

    stack = np.stack([np.array(f) for f in frames]).astype(np.uint32)
    code = (stack[..., 0] << 16) | (stack[..., 1] << 8) | stack[..., 2]
    colours = mi.palette_for(code)
    pal_rgb = np.stack([(colours >> 16) & 255, (colours >> 8) & 255, colours & 255], 1).astype(float)
    uniq = np.unique(code)
    urgb = np.stack([(uniq >> 16) & 255, (uniq >> 8) & 255, uniq & 255], 1).astype(float)
    nearest = np.argmin(((urgb[:, None, :] - pal_rgb[None]) ** 2).sum(2), 1)
    idx = nearest[np.searchsorted(uniq, code)].astype(np.uint8)
    pal = [v for c in colours for v in (int(c >> 16) & 255, int(c >> 8) & 255, int(c) & 255)]
    pal += [0, 0, 0] * (255 - len(colours)) + [255, 0, 255]
    gif = []
    for i in range(len(frames)):
        q = idx[i].copy()
        if i:
            q[idx[i] == idx[i - 1]] = 255
        q[~mask] = 255
        p = Image.fromarray(q, "P")
        p.putpalette(pal)
        gif.append(p)
    out = mi.OUT / "banner-animated.gif"
    gif[0].save(out, save_all=True, append_images=gif[1:], duration=1000 // mi.FPS, loop=0, optimize=True, disposal=1,
                transparency=255)

    back, i = Image.open(out), 0
    for n in range(back.n_frames):
        back.seek(n)
        got = np.array(back.convert("RGBA"))
        for _ in range(round(back.info["duration"] * mi.FPS / 1000)):
            want = pal_rgb[idx[i]].astype(np.uint8)
            if not (np.array_equal(got[mask][:, :3], want[mask]) and (got[..., 3][mask] == 255).all()
                    and (got[..., 3][~mask] == 0).all()):
                sys.exit(f"banner GIF frame {i} decodes differently")
            i += 1
    if i != len(frames):
        sys.exit(f"banner GIF: {i} frames decoded, {len(frames)} expected")
    size = out.stat().st_size
    (ROOT / "docs").mkdir(exist_ok=True)
    shutil.copyfile(out, ROOT / "docs" / "banner.gif")
    bgpix = tuple(np.array(frames[0])[100, 700]) if False else None
    print(f"banner.png (frame {mi.STILL_FRAME}); {len(frames)} frames, {len(colours)} colours -> {out.relative_to(ROOT)} "
          f"{size / 1024:.0f} KiB ({'OK' if size <= GIF_LIMIT else 'OVER'} 5 MiB), verified; copied to docs/banner.gif")
    if size > GIF_LIMIT:
        sys.exit(1)


if __name__ == "__main__":
    main()
