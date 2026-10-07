#!/usr/bin/env python3
"""Writes the lessons (scenes.py) into the mod's resources, with the list the game reads.

    python3 dev/ponder/make.py            write everything
    python3 dev/ponder/make.py place      write one
"""
import json
import sys

import pondlib
import scenes


def main(only):
    pondlib.SHIP.mkdir(parents=True, exist_ok=True)
    ids = []
    for fn in scenes.LESSONS:
        L = fn()
        ids.append(L.id)
        if only and L.id not in only:
            continue
        path = L.write()
        print("wrote", path.relative_to(pondlib.SHIP.parent.parent.parent.parent.parent.parent), f"({path.stat().st_size // 1024} KiB)")
    (pondlib.SHIP / "index.json").write_text(json.dumps({"format": 1, "lessons": ids}, indent=1) + "\n")


if __name__ == "__main__":
    main(set(sys.argv[1:]))
