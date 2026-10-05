#!/usr/bin/env python3
"""Copies the chosen UI kit (dev/ui/out/B_final) into the mod as GUI sprites.

Every sprite of atlas.json becomes assets/cyanotype/textures/gui/sprites/<name>.png; those with a 9-slice size also get a
.png.mcmeta so the game stretches only the middle. Run after redrawing the kit: `python3 dev/ui/ship.py`.
"""
import json
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
KIT = HERE / "out" / "B_final"
OUT = HERE.parent.parent / "src/main/resources/assets/cyanotype/textures/gui/sprites"


def main():
    atlas = json.loads((KIT / "atlas.json").read_text())
    OUT.mkdir(parents=True, exist_ok=True)
    for stale in OUT.glob("*"):
        stale.unlink()
    n = 0
    for name, s in atlas["sprites"].items():
        src = KIT / f"{name}.png"
        if not src.exists():
            raise SystemExit(f"missing sprite file {src}")
        shutil.copy(src, OUT / f"{name}.png")
        if "slice" in s:
            meta = {"gui": {"scaling": {"type": "nine_slice", "width": s["w"], "height": s["h"], "border": s["slice"]}}}
            (OUT / f"{name}.png.mcmeta").write_text(json.dumps(meta, indent=4) + "\n")
        n += 1
    print(f"shipped {n} sprites to {OUT.relative_to(HERE.parent.parent)}")


if __name__ == "__main__":
    main()
