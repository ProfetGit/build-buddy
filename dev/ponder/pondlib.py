"""Helpers for writing lessons (PRD 7.12a). A lesson is built with calls like `L.cursor(...)`, `L.caption(...)` and written as
format-1 JSON into src/main/resources/assets/cyanotype/ponders/. The loader in the mod is strict, so a typo fails there (the
Java test over every shipped lesson) and not as a missing arrow in the game."""
import json
from pathlib import Path

SHIP = Path(__file__).resolve().parent.parent.parent / "src/main/resources/assets/cyanotype/ponders"


def r(x, n=4):
    """Short numbers in the file."""
    return round(x, n) if isinstance(x, float) else x


class Lesson:
    def __init__(self, id, title, summary, action="none", tags=(), duration=16.0, size=(13, 9, 13), focus=None, frame=None):
        self.id, self.title, self.summary, self.action = id, title, summary, action
        self.tags = list(tags)
        self.duration = duration
        self.size = list(size)
        self.focus = list(focus) if focus else [size[0] / 2, 2.5, size[2] / 2]
        self.frame = list(frame) if frame else None
        self.palette = {}
        self.groups = []
        self.ops = []
        self.camera_keys = []
        self.group_keys = {}
        self.cursor_keys = []
        self.captions_ = []
        self.chips_ = []
        self.overlays_ = []
        self.panels_ = []
        self.sounds_ = []

    # ---- blocks
    def key(self, ch, spec):
        assert len(ch) == 1 and ch not in ". ", ch
        self.palette[ch] = spec
        return self

    def keys(self, **kw):
        for ch, spec in kw.items():
            self.key(ch, spec)

    def group(self, id, layers, pos=(0, 0, 0), mode="solid", start="full", match=None):
        g = {"id": id, "mode": mode, "pos": list(pos), "layers": [list(l) for l in layers], "start": start}
        if match:
            g["match"] = match
        self.groups.append(g)
        return id

    def reveal(self, group, t, anim="drop", dur=None, stagger=0.0, order="yzx", **sel):
        op = {"t": r(t), "group": group, "do": "reveal", "anim": anim, **sel}
        if dur is not None:
            op["dur"] = dur
        if stagger:
            op["stagger"] = r(stagger)
        if order != "yzx":
            op["order"] = order
        self.ops.append(op)

    def clear(self, group, t, anim="fade", dur=None, stagger=0.0, **sel):
        op = {"t": r(t), "group": group, "do": "clear", "anim": anim, **sel}
        if dur is not None:
            op["dur"] = dur
        if stagger:
            op["stagger"] = r(stagger)
        self.ops.append(op)

    # ---- tracks
    def cam(self, t, ease=None, **f):
        k = {"t": r(t), **f}
        if ease:
            k["ease"] = ease
        self.camera_keys.append(k)

    def move(self, group, t, ease=None, **f):
        k = {"t": r(t), **{a: v for a, v in f.items()}}
        if ease:
            k["ease"] = ease
        self.group_keys.setdefault(group, []).append(k)

    def cursor(self, t, at=None, screen=None, panel=None, down=None, shape=None, alpha=None, ease=None):
        k = {"t": r(t)}
        if at is not None:
            k["at"] = [r(float(v)) for v in at]
        if screen is not None:
            k["screen"] = list(screen)
        if panel is not None:
            k["panel"] = list(panel)
        if down is not None:
            k["down"] = bool(down)
        if shape:
            k["shape"] = shape
        if alpha is not None:
            k["alpha"] = alpha
        if ease:
            k["ease"] = ease
        self.cursor_keys.append(k)

    def click(self, t, hold=0.18, sound=True):
        """The button goes down at t and up again after hold seconds, wherever the cursor is (with the click's sounds)."""
        self.cursor(t, down=True)
        self.cursor(t + hold, down=False)
        if sound:
            self.sound(t, "ui_press", 0.8)
            if hold >= 0.1:
                self.sound(t + hold, "ui_release", 0.7)

    def sound(self, t, name, volume=1.0, pitch=1.0):
        """A sound of the mod's interface at a time (it plays only while the lesson is playing, at the player's Sounds volume)."""
        c = {"t": r(t), "name": name}
        if volume != 1.0:
            c["volume"] = volume
        if pitch != 1.0:
            c["pitch"] = pitch
        self.sounds_.append(c)

    # ---- words
    def caption(self, t0, t1, text, title=None):
        c = {"t0": r(t0), "t1": r(t1), "text": text}
        if title:
            c["title"] = title
        self.captions_.append(c)

    def chips(self, t0, t1, rows, lit=()):
        c = {"t0": r(t0), "t1": r(t1), "rows": [list(x) for x in rows]}
        if lit:
            c["lit"] = [[i, r(a), r(b)] for (i, a, b) in lit]
        self.chips_.append(c)

    def overlay(self, type, t0, t1, keys=None, fade=None, id=None, **props):
        o = {"type": type, "t0": r(t0), "t1": r(t1), **{k.rstrip("_"): v for k, v in props.items()}}
        if id:
            o["id"] = id
        if fade is not None:
            o["fade"] = fade
        if keys:
            o["keys"] = sorted(keys, key=lambda k: k["t"])
        self.overlays_.append(o)

    def panel(self, type, t0, t1, keys=None, states=None, fade=None, **props):
        o = {"type": type, "t0": r(t0), "t1": r(t1), **props}
        if fade is not None:
            o["fade"] = fade
        if keys:
            o["keys"] = sorted(keys, key=lambda k: k["t"])
        if states:
            o["states"] = sorted(states, key=lambda k: k["t"])
        self.panels_.append(o)

    # ---- output
    def to_dict(self):
        tracks = {}
        if self.camera_keys:
            tracks["camera"] = sorted(self.camera_keys, key=lambda k: k["t"])
        if self.group_keys:
            tracks["groups"] = {g: sorted(ks, key=lambda k: k["t"]) for g, ks in self.group_keys.items()}
        if self.cursor_keys:
            tracks["cursor"] = sorted(self.cursor_keys, key=lambda k: k["t"])
        d = {
            "format": 1, "id": self.id, "title": self.title, "summary": self.summary, "tags": self.tags, "action": self.action,
            "duration": self.duration, "stage": {"size": self.size, "focus": self.focus, **({"frame": self.frame} if self.frame else {})},
            "palette": self.palette, "groups": self.groups,
        }
        if self.ops:
            d["ops"] = sorted(self.ops, key=lambda o: o["t"])
        if tracks:
            d["tracks"] = tracks
        d["captions"] = self.captions_
        if self.chips_:
            d["chips"] = self.chips_
        if self.overlays_:
            d["overlays"] = self.overlays_
        if self.panels_:
            d["panels"] = self.panels_
        if self.sounds_:
            d["sounds"] = sorted(self.sounds_, key=lambda c: c["t"])
        return d

    def write(self, directory=SHIP):
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{self.id}.json"
        path.write_text(json.dumps(self.to_dict(), indent=1, ensure_ascii=False) + "\n")
        return path


class Follow:
    """One keyed field of a group (its pos, its turn) that overlays follow: every key goes into the group's track and is
    remembered, so `derive` can key an overlay's property at exactly the same times with the same eases, and the two move as one."""

    def __init__(self, L, group, field):
        self.L, self.group, self.field = L, group, field
        self.ks = []

    def key(self, t, value, ease=None):
        self.ks.append((t, value, ease))
        self.L.move(self.group, t, ease=ease, **{self.field: value})

    def value_at(self, t):
        """The value at a time, for placing a cursor: the same easing as the game (inOut between keys unless a key says otherwise)."""
        ks = self.ks
        if t <= ks[0][0]:
            return ks[0][1]
        for (t0, v0, _), (t1, v1, e1) in zip(ks, ks[1:]):
            if t <= t1:
                k = (t - t0) / (t1 - t0)
                if e1 == "linear":
                    f = k
                elif e1 == "step":
                    f = 0 if k < 1 else 1
                else:
                    f = 4 * k ** 3 if k < 0.5 else 1 - (-2 * k + 2) ** 3 / 2
                if isinstance(v0, list):
                    return [a + (b - a) * f for a, b in zip(v0, v1)]
                return v0 + (v1 - v0) * f
        return ks[-1][1]

    def derive(self, prop, fn=lambda v: v, t0=None, t1=None):
        """Keys for an overlay property, one for each key of this field (within the overlay's life, with the values at its ends)."""
        out = []
        if t0 is not None:
            out.append({"t": t0, prop: fn(self.value_at(t0))})
        for t, v, e in self.ks:
            if (t0 is None or t > t0) and (t1 is None or t < t1):
                k = {"t": t, prop: fn(v)}
                if e:
                    k["ease"] = e
                out.append(k)
        if t1 is not None:
            out.append({"t": t1, prop: fn(self.value_at(t1))})
        return out


def follow_cursor(L, f, offset, t0, t1, ease="linear"):
    """Cursor keys that stay at `offset` from a Follow's value between two times (the cursor rides on the thing being moved)."""
    L.cursor(t0, ease="inOut", at=add(f.value_at(t0), offset))
    for t, v, e in f.ks:
        if t0 < t < t1:
            L.cursor(t, ease=e or ease, at=add(v, offset))
    L.cursor(t1, ease=ease, at=add(f.value_at(t1), offset))


def add(p, d):
    return [p[i] + d[i] for i in range(3)]


class Builder:
    """Brings blocks of a group in, never twice: each call takes the cells it is given that have not come yet."""

    def __init__(self, L, group):
        self.L, self.group, self.done = L, group, set()

    def go(self, t, cells, stagger=0.05, anim="drop", dur=None):
        new = [c for c in cells if tuple(c) not in self.done]
        self.done.update(tuple(c) for c in new)
        if new:
            self.L.reveal(self.group, t, anim=anim, stagger=stagger, order="given", cells=new, dur=dur)
        return t + len(new) * stagger

    def rest(self):
        return self.done


# ---- shared set pieces ---------------------------------------------------------------------------------------------------

def stage(L, w=11, d=11):
    """The ground every lesson stands on: a slab of dirt with grass on top, 2 blocks thick. Its top is at y = 2."""
    L.keys(G="minecraft:grass_block", D="minecraft:dirt")
    L.group("ground", [["D" * w] * d, ["G" * w] * d])


def cells_of(layers, pred=lambda ch, x, y, z: True):
    """The (x, y, z) of every block of a grid of layers that passes a test, in the order the game builds: bottom up, north to south, west to east."""
    out = []
    for y, layer in enumerate(layers):
        for z, row in enumerate(layer):
            for x, ch in enumerate(row):
                if ch not in ". " and pred(ch, x, y, z):
                    out.append([x, y, z])
    return out


def cottage_keys(L):
    L.keys(c="minecraft:cobblestone", m="minecraft:mossy_cobblestone", p="minecraft:oak_planks", l="minecraft:oak_log",
           g="minecraft:glass_pane[north=true,south=true]", h="minecraft:glass_pane[east=true,west=true]", w="minecraft:cobblestone_wall[up=true]",
           n="minecraft:brick_stairs[facing=south]", s="minecraft:brick_stairs[facing=north]", t="minecraft:brick_slab[type=bottom]",
           u="minecraft:bricks", b="minecraft:oak_door[facing=south,half=lower]", B="minecraft:oak_door[facing=south,half=upper]")


# The cottage every lesson builds or moves: 5 wide, 5 deep, 6 high. Its door is in the south wall and its chimney in the north-east
# corner, so a turn or a mirror shows. Layers go from the bottom up, rows from the north (z = 0) to the south, columns west to east.
COTTAGE = [
    # y0: the foundation
    ["cmccc", "ccccm", "mcccc", "ccmcc", "cccmc"],
    # y1: walls, a window in each side wall, the door in the south wall
    ["lpppl", "p...p", "g...g", "p...p", "lpbpl"],
    # y2: walls, a window in the north wall, the top of the door
    ["lphpl", "p...p", "p...p", "p...p", "lpBpl"],
    # y3: the lower roof (north and south) with the ceiling between
    ["nnnnn", "uuuuu", "uuuuu", "uuuuu", "sssss"],
    # y4: the upper roof, and the chimney
    [".....", "nnnnc", "uuuuu", "sssss", "....."],
    # y5: the ridge and the top of the chimney
    [".....", "....w", "ttttt", ".....", "....."],
]
