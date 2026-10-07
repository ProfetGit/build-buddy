#!/usr/bin/env python3
"""Lossless animated WebPs of the icon and the banner at 60 fps (GIF cannot go past 50 and browsers slow it down; WebP can).

  CYANO_FPS=60 python3 dev/make_webp.py     # -> docs/banner.webp, dev/icon/out/webp/{icon-animated,promo-tile}.webp

The animation is time-based (squash, flash, ring, fall, shimmer and shine are interpolated; blinks and sparks stay at the
design's 20 steps a second), so 60 fps only adds in-betweens. Frame times are 17/17/16 ms, 50 ms per three frames.
"""
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

os.environ.setdefault("CYANO_FPS", "60")
sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_icon as mi  # noqa: E402
import make_banner as mb  # noqa: E402


def save(frames, out):
    fps = mi.FPS
    dur = [round((i + 1) * 1000 / fps) - round(i * 1000 / fps) for i in range(len(frames))]
    frames[0].save(out, save_all=True, append_images=frames[1:], duration=dur, loop=0, lossless=True, quality=100, method=6, exact=True)
    return out.stat().st_size / 1024


def main():
    art = [mi.frame(f) for f in range(mi.FRAMES)]
    if not np.array_equal(art[0], mi.frame(mi.FRAMES)):
        sys.exit("loop seam differs")
    out = mi.OUT / "webp"
    out.mkdir(parents=True, exist_ok=True)
    icon = [mi.compose(a, mi.GIF_SIZE).convert("RGBA") for a in art]
    print(f"icon-animated.webp {save(icon, out / 'icon-animated.webp'):.0f} KiB, {mi.FPS} fps, {len(icon)} frames")

    paper = mi.paper_bg(mb.W // mb.S, mb.H // mb.S, mb.ART_X // mb.S)
    bg = Image.fromarray(paper).resize((mb.W, mb.H), Image.NEAREST).convert("RGBA")
    for n, sc, pos in mb.LAYERS:
        bg.alpha_composite(mb.sprite(n, sc), pos)
    mask = mb.corner_mask()
    banner = []
    for a in art:
        fr = bg.copy()
        fr.alpha_composite(Image.fromarray(a, "RGBA").resize((mi.N * mb.S, mi.N * mb.S), Image.NEAREST), (mb.ART_X, 0))
        arr = np.array(fr)
        arr[~mask] = 0
        banner.append(Image.fromarray(arr, "RGBA"))
    sys.path.insert(0, str(mi.ROOT.parents[1] / "tools/Description-Kit/dev"))
    import make_promo as mp
    base, _ = mp.compose_static("cyanotype", mp.TILES["cyanotype"])
    base = base.convert("RGBA")
    pos = (mp.icon_x(mi.GIF_SIZE), (mp.TH * mp.S - mi.GIF_SIZE) // 2)
    tile = []
    for ic in icon:
        fr = base.copy()
        fr.alpha_composite(ic, pos)
        tile.append(fr)
    print(f"promo-tile.webp {save(tile, out / 'promo-tile.webp'):.0f} KiB, {mi.FPS} fps, {len(tile)} frames")
    dst = mi.ROOT / "docs" / "banner.webp"
    print(f"banner.webp {save(banner, dst):.0f} KiB, {mi.FPS} fps, {len(banner)} frames -> {dst.relative_to(mi.ROOT)}")


if __name__ == "__main__":
    main()
