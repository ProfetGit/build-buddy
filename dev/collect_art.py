#!/usr/bin/env python3
"""Copy every finished art file into release-art/ (generated, not in Git), grouped by use. Run after make_icon, make_banner,
make_webp and the description kit.

  python3 dev/collect_art.py
"""
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "release-art"
WS = ROOT.parents[1]
ICON = ROOT / "dev/icon/out"
KIT_PROMO = WS / "tools/Description-Kit/dist/promo/cyanotype.gif"

FILES = {
    "icon": [(ICON / "icon-animated.gif", "icon-animated.gif"), (ICON / "webp/icon-animated.webp", "icon-animated-60fps.webp"),
             (ICON / "icon-512.png", "icon-512.png"), (ROOT / "src/main/resources/assets/buildbuddy/icon.png", "jar-icon-128.png")],
    "banner": [(ICON / "banner-animated.gif", "banner-animated.gif"), (ROOT / "docs/banner.webp", "banner-animated-60fps.webp"),
               (ICON / "banner.png", "banner.png")],
    "promo-tile": [(KIT_PROMO, "promo-tile.gif"), (ICON / "webp/promo-tile.webp", "promo-tile-60fps.webp")],
}


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    for group, items in FILES.items():
        (OUT / group).mkdir(parents=True)
        for src, name in items:
            shutil.copy2(src, OUT / group / name)
    desc = OUT / "description"
    desc.mkdir()
    for f in sorted((ROOT / "docs/desc").iterdir()):
        shutil.copy2(f, desc / f.name)
    still = OUT / "curseforge-logo.png"
    shutil.copy2(ICON / "icon-512.png", still)
    for p in sorted(OUT.rglob("*")):
        if p.is_file():
            print(f"{p.relative_to(OUT)}  {p.stat().st_size / 1024:.0f} KiB")


if __name__ == "__main__":
    main()
