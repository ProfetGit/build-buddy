"""Turns a take recorded in the real client (PonderRecorder, `*.rec.json`) into lesson tracks.

The log is raw: positions relative to the stage box, one frame for every moment something changed. This module does the maths that
makes it a lesson: where the lesson's group goes so that its turned, mirrored box lands where the placement's did, a turn that glides
instead of snapping, keys thinned to what the eye can tell, and the cursor of the take. Captions, camera and overlays are written by
hand in scenes.py and hang on the take's marks (`rec.t("turn1")`), so a new take keeps them in place.

The maths is checked against an independent copy in Java (RecordedLessonTest) and in test_recording.py.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
REC = HERE / "rec"

# the columns of a ghost frame, as PonderRecorder writes them
FRAME = ("t", "x", "y", "z", "rot", "mirror", "locked", "visible", "opacity", "layerLo", "layerHi", "active")


class Recording:
    def __init__(self, data):
        self.d = data
        self.marks = data["marks"]
        self.duration = data["duration"]

    @classmethod
    def load(cls, name):
        return cls(json.loads((REC / f"{name}.rec.json").read_text()))

    def t(self, mark, dt=0.0):
        return self.marks[mark] + dt

    def ghost(self, name):
        g = self.d["ghosts"][name]
        frames = [dict(zip(FRAME, f)) for f in g["frames"]]
        return g["grid"]["size"], frames

    def aim(self):
        return [dict(t=a[0], x=a[1], y=a[2], z=a[3], face=a[4]) for a in self.d["aim"]]

    def inputs(self, name):
        """(t, down) of one button or key."""
        return [(e[0], bool(e[2])) for e in self.d["input"] if e[1] == name]

    def scrolls(self):
        return [(e[0], e[1]) for e in self.d["scroll"]]


# ---- the maths -----------------------------------------------------------------------------------------------------------

def turn_and_mirror(rot, mirror):
    """The engine mirrors across x first and then turns clockwise by `turn` quarters. The game's Mirror.LEFT_RIGHT flips z, which is a flip across x and a half turn."""
    return (rot + (2 if mirror == 2 else 0)) % 4, 1 if mirror else 0


def lesson_pos(frame, size):
    """Where the lesson's group (the unturned blueprint, its corner at pos) goes so that its turned box sits where the placement's does: the middle of the footprint stays where the placement's middle is."""
    sx, _, sz = size
    tx, tz = (sz, sx) if frame["rot"] % 2 else (sx, sz)
    return [frame["x"] + tx / 2 - sx / 2, frame["y"], frame["z"] + tz / 2 - sz / 2]


def unwrap(turns):
    """Turns as numbers that never jump: 3 to 0 is one more step, not three back."""
    out, prev = [], None
    for t in turns:
        if prev is None:
            out.append(float(t))
        else:
            d = (t - prev) % 4
            # three steps clockwise are one step back; a half turn goes clockwise
            out.append(out[-1] + (d - 4 if d == 3 else d))
        prev = t
    return out


def with_holds(keys, step=0.05):
    """The log has a frame only where something changed, so a gap between two frames is a hold: the value stayed. Adds the key that says so (one step before the next change), or the lesson would glide through the hold."""
    out = []
    for i, k in enumerate(keys):
        if i and k[0] - keys[i - 1][0] > 1.5 * step:
            out.append((round(k[0] - step, 4), keys[i - 1][1]))
        out.append(k)
    return out


def simplify(keys, eps=0.015):
    """Drops keys that the straight line between their neighbours (at their times) already says. keys: [(t, value)]; value a number or a list."""
    if len(keys) <= 2:
        return list(keys)

    def at(a, b, t):
        k = 0 if b[0] == a[0] else (t - a[0]) / (b[0] - a[0])
        if isinstance(a[1], list):
            return [x + (y - x) * k for x, y in zip(a[1], b[1])]
        return a[1] + (b[1] - a[1]) * k

    def err(p, q):
        if isinstance(p, list):
            return max(abs(x - y) for x, y in zip(p, q))
        return abs(p - q)

    out = [keys[0]]
    for i in range(1, len(keys) - 1):
        a = out[-1]
        b = keys[i + 1]
        if err(at(a, b, keys[i][0]), keys[i][1]) > eps:
            out.append(keys[i])
    out.append(keys[-1])
    return out


class Take:
    """A recording laid on a lesson's clock: `offset` is added to every time of the log."""

    def __init__(self, rec, offset):
        self.rec, self.offset = rec, offset

    def T(self, mark, dt=0.0):
        return self.rec.t(mark, dt) + self.offset

    def end(self, mark="done", dt=0.0):
        return self.T(mark, dt)

    def ghost_keys(self, name, ramp=0.4):
        """Keys for a group that is a recorded placement: pos, turn (glides over `ramp` seconds from each change), mirror (switches at the change)."""
        size, frames = self.rec.ghost(name)
        pos = with_holds([(f["t"] + self.offset, lesson_pos(f, size)) for f in frames])
        tm = [turn_and_mirror(f["rot"], f["mirror"]) for f in frames]
        turns = unwrap([t for t, _ in tm])
        turn_keys, mirror_keys = [], []
        for i, f in enumerate(frames):
            t = f["t"] + self.offset
            if i == 0:
                turn_keys.append((t, turns[0]))
                mirror_keys.append((t, tm[0][1]))
                continue
            if turns[i] != turns[i - 1]:
                turn_keys.append((t, turns[i - 1]))
                turn_keys.append((t + ramp, turns[i]))
            if tm[i][1] != tm[i - 1][1]:
                mirror_keys.append((t, tm[i][1]))
        turn_keys.sort(key=lambda k: k[0])
        return simplify(pos), turn_keys, mirror_keys

    def window_keys(self, name, layers):
        """(t, (lowest, highest)) of the layer window of a recorded placement, on the lesson's clock; an open end is the lowest or the highest layer."""
        _, frames = self.rec.ghost(name)
        out = []
        for f in frames:
            lo = 0 if f["layerLo"] < 0 else int(f["layerLo"])
            hi = layers - 1 if f["layerHi"] < 0 else int(f["layerHi"])
            if not out or out[-1][1] != (lo, hi):
                out.append((f["t"] + self.offset, (lo, hi)))
        return out

    def apply_ghost(self, L, group, name, ramp=0.4):
        """Puts the recorded transform into a lesson's group; returns the Follow objects of pos and turn so overlays can follow them."""
        from pondlib import Follow
        pos_keys, turn_keys, mirror_keys = self.ghost_keys(name, ramp)
        pos, turn = Follow(L, group, "pos"), Follow(L, group, "turn")
        # the frames are a tick apart: straight lines between them (an ease on each would stutter), and a turn glides into its new value
        for t, v in pos_keys:
            pos.key(round(t, 3), [round(x, 3) for x in v], "linear")
        last = None
        for t, v in turn_keys:
            turn.key(round(t, 3), v, "inOut" if last is not None and last != v else "linear")
            last = v
        for t, v in mirror_keys:
            L.move(group, round(t, 3), ease="step", mirror=v)
        return pos, turn

    def cursor(self, L, y=None, until=None, after=None):
        """The crosshair of the take as the lesson's cursor: where it was on the ground, pressed while the attack button was held.
        `after` leaves out what came before that time on the lesson's clock (the lesson has its own cursor story there)."""
        pts = with_holds([(a["t"] + self.offset, [a["x"], a["y"] if y is None else y, a["z"]]) for a in self.rec.aim()])
        for t, v in simplify(pts, 0.03):
            if (until is None or t <= until) and (after is None or t >= after):
                L.cursor(round(t, 3), ease="linear", at=[round(c, 3) for c in v])
        for t, down in self.rec.inputs("attack"):
            if (until is None or t + self.offset <= until) and (after is None or t + self.offset >= after):
                L.cursor(round(t + self.offset, 3), down=down)
