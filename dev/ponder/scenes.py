"""The lessons. Run make.py to write them into src/main/resources/assets/cyanotype/ponders/."""
from pondlib import Lesson, Follow, Builder, add, follow_cursor, stage, cells_of, cottage_keys, COTTAGE
from recording import Recording, Take

J = ["J", "How it works"]   # the last row of every tool's hints in the game
GHOST_FLOAT = 0.18   # a ghost that follows the crosshair floats a little above the ground, as in the game
TOP = 2.0            # the top of the ground slab


def centred(cx, cz, y=TOP + GHOST_FLOAT):
    """Where the cottage's corner goes so that its 5 x 5 footprint is centred on the cell (cx, cz)."""
    return [cx - 2.5, y, cz - 2.5]


def place():
    """The place lesson, built on a take recorded in the real client (rec/place.rec.json, made by `PONDER=... dev/demo/run.sh <out> ponder-take`):
    where the ghost goes, how it turns, what a lift and a mirror do and where the crosshair was are what the mod really did.
    The words, the camera, the Library card and the little pictures are written here and hang on the take's marks."""
    take = Take(Recording.load("place"), 0.0)
    take.offset = 1.9 - take.rec.t("appear")          # the ghost appears at 1.9 s of the lesson
    T = take.T
    L = Lesson("place", "Place a blueprint", "Pick a blueprint, turn it, lift it, mirror it and click to put it down.", action="place",
               tags=["library", "ghost", "turn", "lift", "mirror", "lock", "place"], duration=18.0, size=(11, 9, 11), focus=[5.5, 3.2, 5.5])
    stage(L)
    cottage_keys(L)
    L.group("ghost", COTTAGE, pos=[0, TOP, 0], mode="ghost")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.6, focus=[5.5, 3.2, 5.5])
    L.cam(9, ease="inOut", yaw=0.90)
    L.cam(18, ease="inOut", yaw=0.785)

    # the ghost: the recorded transform, between its appearing and the end of the take, then it fades so the lesson loops
    appear, end = T("appear"), take.rec.duration + take.offset
    L.move("ghost", 0, visible=0, alpha=0, turn=0, mirror=0)
    L.move("ghost", appear, ease="step", visible=1)
    L.move("ghost", appear + 0.4, ease="out", alpha=1)
    pos, turn = take.apply_ghost(L, "ghost", "Cottage")
    L.move("ghost", 17.0, alpha=1)
    L.move("ghost", 17.6, ease="inOut", alpha=0)
    L.move("ghost", 17.7, ease="step", visible=0, turn=0)
    L.move("ghost", 17.7, mirror=0)

    # the cursor: the card of the Library, then the crosshair of the take
    L.cursor(0, screen=[0.7, 0.62], alpha=0)
    L.cursor(0.3, ease="out", alpha=1)
    L.cursor(0.9, ease="inOut", panel=["library", 0])
    L.click(1.1)
    first = take.rec.aim()[0]
    L.cursor(appear, ease="inOut", at=[first["x"], first["y"], first["z"]])
    take.cursor(L, until=T("done") + 0.5, after=appear + 0.05)
    L.cursor(17.0, ease="inOut", alpha=0)
    L.cursor(18.0, ease="inOut", screen=[0.7, 0.62], alpha=0)
    L.panel("library", 0.0, appear, cards=["Cottage", "Barn", "Windmill"], keys=[{"t": 0, "hot": -1}, {"t": 0.9, "hot": 0}])

    # the steps hang on the marks
    s2, s3, s4 = T("turn1") - 0.5, T("lift_up") - 0.6, T("lock") - 0.8
    L.caption(0.0, s2, "Pick a blueprint in the Library. It follows where you look.", "1  Pick it")
    L.caption(s2, s3, "Scroll to turn it a quarter at a time.", "2  Turn it")
    L.caption(s3, s4, "Shift and scroll lifts it, M mirrors it.", "3  Lift or mirror it")
    L.caption(s4, 18.0, "Click to put it down. It settles and stays where you left it.", "4  Lock it")
    rows = [["Scroll", "Turn"], ["Shift+Scroll", "Up / down"], ["M", "Mirror"], ["Click", "Lock in place"], ["V", "Cancel"], J]
    lit = [(0, T("turn1"), T("turn1") + 0.7), (0, T("turn2"), T("turn2") + 0.7), (1, T("lift_up"), T("lift_up") + 0.6), (1, T("lift_down"), T("lift_down") + 0.6),
           (2, T("mirror"), T("mirror") + 0.6), (3, T("lock"), T("lock") + 0.6)]
    L.chips(appear + 0.3, 18.0, rows, lit=lit)

    # how far it is lifted: a line from the ground to the ghost, while it is up
    up0, up1 = T("lift_up"), T("lift_down") + 0.9
    ground = [pos.ks[0][1][0] + 5.7, TOP, pos.ks[0][1][2] + 5.7]
    near = [(t, v, e) for t, v, e in pos.ks if up0 - 0.3 <= t <= up1 + 0.3]
    keys = []
    for t, v, e in near:
        k = {"t": t, "a": [v[0] + 5.7, TOP, v[2] + 5.7], "b": [v[0] + 5.7, v[1], v[2] + 5.7]}
        if e:
            k["ease"] = e
        keys.append(k)
    L.overlay("dim", up0 - 0.05, up1, a=ground, b=ground, text="+1", color="gold", keys=keys)
    fx = pos.ks[-1][1]
    L.overlay("label", T("lock") + 0.9, 17.0, at=[fx[0] + 2.5, TOP + 6.6, fx[2] + 2.5], text="Locked", color="green")
    return L


def handles(L, pos, turn, t0, t1, hot=None, fade=0.4):
    """The six arrows and the ring round the cottage whose corner follows `pos`, between two times; `hot` is an arrow name that is grabbed (a Follow-like list of hot keys)."""
    spots = {  # name: (offset from the corner, direction)
        "east": ((5, 3, 2.5), "+x"), "west": ((0, 3, 2.5), "-x"), "north": ((2.5, 3, 0), "-z"), "south": ((2.5, 3, 5), "+z"), "up": ((2.5, 6, 2.5), "+y"), "down": ((2.5, 0, 2.5), "-y"),
    }
    for name, (off, d) in spots.items():
        keys = pos.derive("at", lambda v, o=off: add(v, o), t0, t1)
        if hot and name in hot:
            keys = keys + [{"t": t, "hot": h} for (t, h) in hot[name]]
            keys.sort(key=lambda k: k["t"])
        L.overlay("arrow", t0, t1, at=add(pos.ks[0][1], off), dir=d, len=1.9, keys=keys, fade=fade)
    ring_keys = pos.derive("center", lambda v: add(v, (2.5, 0, 2.5)), t0, t1)
    if turn is not None:
        ring_keys += turn.derive("angle", lambda v: v, t0, t1)
        ring_keys.sort(key=lambda k: k["t"])
    if hot and "ring" in hot:
        ring_keys += [{"t": t, "hot": h} for (t, h) in hot["ring"]]
        ring_keys.sort(key=lambda k: k["t"])
    L.overlay("ring", t0, t1, center=add(pos.ks[0][1], (2.5, 0, 2.5)), radius=3.6, angle=0, keys=ring_keys, fade=fade)


def edit():
    """The edit lesson, built on a recorded take: the east arrow dragged, the ring dragged, the build carried by its body and three undos
    (rec/edit.rec.json). Where the build goes, and when, is what the mod did; the pictures round it follow."""
    import math
    take = Take(Recording.load("edit"), 0.0)
    T = take.T
    dur = math.ceil((take.rec.duration + 1.2) * 2) / 2
    L = Lesson("edit", "Move and turn a build", "Drag arrows or the ring, or carry the whole build, and undo anything.", action="edit",
               tags=["edit", "move", "turn", "carry", "arrows", "ring", "undo", "mirror", "delete"], duration=dur, size=(12, 9, 12), focus=[6.0, 3.4, 6.0])
    stage(L, 12, 12)
    cottage_keys(L)
    L.group("ghost", COTTAGE, pos=[3, 2, 4], mode="ghost")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.5, focus=[6.0, 3.4, 6.0])
    L.cam(dur / 2, ease="inOut", yaw=0.9)
    L.cam(dur, ease="inOut", yaw=0.785)
    pos, turn = take.apply_ghost(L, "ghost", "Cottage")

    # the handles are there from the start, go while the build is carried (the game hides them then), and come back
    hot = {"east": [(0, 0), (T("grab_east"), 1), (T("drop_east") + 0.2, 0)], "ring": [(0, 0), (T("grab_ring"), 1), (T("drop_ring") + 0.2, 0)]}
    carry0, carry1 = T("grab_body") - 0.15, T("drop_body") + 0.3
    handles(L, pos, turn, 0.6, carry0, hot=hot)
    handles(L, pos, turn, carry1, dur - 0.5)

    # the cursor rides on the thing it moves
    tip = (5.8, 3, 2.5)
    L.cursor(0, screen=[0.82, 0.55], alpha=0)
    L.cursor(T("grab_east") - 0.9, ease="out", alpha=1)
    L.cursor(T("grab_east"), ease="inOut", at=add(pos.value_at(T("grab_east")), tip))
    L.cursor(T("drag_east") - 0.2, down=True)
    follow_cursor(L, pos, tip, T("drag_east") - 0.15, T("drop_east"))
    L.cursor(T("drop_east"), down=False)
    rc = add(pos.value_at(T("grab_ring")), (2.5, 0.05, 2.5))
    L.cursor(T("grab_ring"), ease="inOut", at=[rc[0] + 3.6, rc[1], rc[2]])
    L.cursor(T("drag_ring") - 0.1, down=True)
    for i in range(5):
        a = math.radians(90 * i / 4)
        L.cursor(T("drag_ring") + (T("drop_ring") - T("drag_ring") - 0.2) * i / 4, ease="linear", at=[rc[0] + 3.6 * math.cos(a), rc[1], rc[2] + 3.6 * math.sin(a)])
    L.cursor(T("drop_ring"), down=False)
    body = (1.0, 1.5, 5.0)
    L.cursor(T("grab_body"), ease="inOut", at=add(pos.value_at(T("grab_body")), body))
    L.cursor(T("carry") - 0.2, down=True)
    follow_cursor(L, pos, body, T("carry") - 0.15, T("drop_body"))
    L.cursor(T("drop_body"), down=False)
    L.cursor(T("drop_body") + 0.9, ease="inOut", alpha=0)
    L.cursor(dur, alpha=0)

    # words in the picture: how far, counting, the angle, the carry
    xs = [(t, v[0]) for t, v, _ in pos.ks]
    crossing = [next(t for t, x in xs if x >= 3 + n - 0.05) for n in (1, 2, 3)]
    ends = crossing[1:] + [T("drop_east") + 1.0]
    for n, (a0, a1) in enumerate(zip(crossing, ends), 1):
        L.overlay("label", a0, a1, at=add(pos.value_at(a0), (2.5, 7.4, 2.5)), text=f"+{n} east", color="x",
                  keys=pos.derive("at", lambda v: add(v, (2.5, 7.4, 2.5)), a0, a1), fade=0)
    t_turn = next(t for t, v, _ in turn.ks if v >= 1)
    L.overlay("label", t_turn, T("drop_ring") + 0.9, at=add(pos.value_at(t_turn), (2.5, 7.4, 2.5)), text="90 degrees", color="ring", fade=0.2)
    before, after = pos.value_at(T("carry") - 0.1), pos.value_at(T("drop_body"))
    dx, dz = round(after[0] - before[0]), round(after[2] - before[2])
    words = [f"{abs(dx)} {'east' if dx > 0 else 'west'}"] if dx else []
    words += [f"{abs(dz)} {'south' if dz > 0 else 'north'}"] if dz else []
    L.overlay("label", T("carry") + 0.2, T("drop_body") + 0.5, at=add(pos.value_at(T("carry") + 0.2), (2.5, 7.4, 2.5)), text=", ".join(words), color="gold",
              keys=pos.derive("at", lambda v: add(v, (2.5, 7.4, 2.5)), T("carry") + 0.2, T("drop_body") + 0.5), fade=0.2)

    L.caption(0.0, T("grab_east") - 0.6, "Tap V to edit. Arrows and a ring appear round the build.", "1  Handles")
    L.caption(T("grab_east") - 0.6, T("grab_ring") - 0.6, "Drag an arrow to move the build along that line, a whole block at a time.", "2  Drag an arrow")
    L.caption(T("grab_ring") - 0.6, T("grab_body") - 0.6, "Drag the ring for quarter turns. Ctrl and scroll turns from anywhere.", "3  Turn it")
    L.caption(T("grab_body") - 0.6, T("undo1") - 0.6, "Or press on the build itself and look where it should go. Let go to drop it.", "4  Carry it")
    L.caption(T("undo1") - 0.6, dur, "Ctrl and Z undoes your last move, Ctrl and Y does it again. Delete removes the build.", "5  Undo")

    def idle(first):
        return [["Drag", first], ["Scroll", "Push east"], ["Ctrl+Scroll", "Turn"], ["M", "Mirror"], ["Ctrl+Z / Y", "Undo / Redo"], ["Delete", "Remove"], ["V", "Done"], J]

    release = [["Release", "Drop it here"]]
    L.chips(0.0, T("grab_east"), idle("Carry the build"))
    L.chips(T("grab_east"), T("drag_east") - 0.2, idle("Move east or west"))
    L.chips(T("drag_east") - 0.2, T("drop_east"), release, lit=[(0, T("drag_east") - 0.2, T("drop_east"))])
    L.chips(T("drop_east"), T("grab_ring"), idle("Carry the build"))
    L.chips(T("grab_ring"), T("drag_ring") - 0.1, idle("Turn in quarter turns"))
    L.chips(T("drag_ring") - 0.1, T("drop_ring"), release, lit=[(0, T("drag_ring") - 0.1, T("drop_ring"))])
    L.chips(T("drop_ring"), T("grab_body"), idle("Carry the build"))
    L.chips(T("grab_body"), T("carry") - 0.2, idle("Carry the build"))
    L.chips(T("carry") - 0.2, T("drop_body"), [["Release", "Drop it here"], ["Scroll", "Lift it up / down"], ["Right click", "Put it back"]], lit=[(0, T("drop_body") - 0.3, T("drop_body"))])
    L.chips(T("drop_body"), dur, idle("Carry the build"), lit=[(4, T(f"undo{i}"), T(f"undo{i}") + 0.6) for i in (1, 2, 3)])
    return L


def layers():
    """The layers lesson, built on a recorded take: the window of layers the mod really shows at every moment (rec/layers.rec.json)."""
    take = Take(Recording.load("layers"), 0.0)
    T = take.T
    L = Lesson("layers", "Show a few layers", "Scroll a window of layers up and down the build, make it thicker, show it all again.", action="layers",
               tags=["layers", "slice", "window", "scroll", "floor", "level"], duration=18.0, size=(11, 9, 11), focus=[5.5, 3.4, 5.5])
    stage(L)
    cottage_keys(L)
    C = [3, 2, 3]
    L.group("ghost", COTTAGE, pos=C, mode="ghost")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.65, focus=[5.5, 3.4, 5.5])
    L.cam(9, ease="inOut", yaw=0.95)
    L.cam(18, ease="inOut", yaw=0.785)
    windows = take.window_keys("Cottage", 6)
    for t, (lo, hi) in windows:
        L.move("ghost", t, ease="step", window=[lo, hi])
    # the border of the window and its name, while the tool is on: from its start to the click, and again from the second start to the right click
    on = [(T("tool"), T("click") + 0.2), (T("again"), T("right") + 0.3)]
    for i, (a0, a1) in enumerate(on):
        inside = [(t, w) for t, w in windows if a0 <= t < a1]
        # the window as it is when the tool starts (the one before its first change)
        start = [w for t, w in windows if t <= a0][-1] if i else windows[1][1]
        seq = [(a0, start)] + [(t, w) for t, w in inside if t > a0]
        keys = [{"t": t, "ease": "step", "from": [C[0], C[1] + w[0], C[2]], "to": [C[0] + 5, C[1] + w[1] + 1, C[2] + 5]} for t, w in seq]
        L.overlay("box", a0, a1, from_=keys[0]["from"], to=keys[0]["to"], keys=keys, fade=0.2)
        ends = [t for t, _ in seq[1:]] + [a1]
        for (t, w), end in zip(seq, ends):
            text = f"Layer {w[0] + 1} of 6" if w[0] == w[1] else f"Layers {w[0] + 1} to {w[1] + 1} of 6"
            L.overlay("label", t, end, at=[C[0] + 2.5, C[1] + w[1] + 1.6, C[2] + 2.5], text=text, color="cyan", fade=0.1)

    s2, s3, s4 = T("up1") - 0.6, T("thick1") - 0.6, T("click") - 0.7
    L.caption(0.0, s2, "Layers shows a few layers of the build at a time. It starts at the lowest one that is not finished.", "1  A window")
    L.caption(s2, s3, "Scroll moves the window up and down the build.", "2  Move it")
    L.caption(s3, s4, "Shift and scroll makes the window thicker or thinner.", "3  Thicker, thinner")
    L.caption(s4, 18.0, "Click ends the tool and keeps what you see. In the tool, right click shows every layer again.", "4  Done")
    rows = [["Scroll", "Move up / down"], ["Shift+Scroll", "Thicker / thinner"], ["Click", "Done"], ["Right click", "Show all layers"], J]
    pulse = lambda row, *marks: [(row, T(m), T(m) + 0.45) for m in marks]
    lit = pulse(0, "up1", "up2", "up3", "up4", "down1", "down2") + pulse(1, "thick1", "thick2", "thin1") + pulse(2, "click")
    L.chips(T("tool"), T("click") + 0.4, rows, lit=lit)
    L.chips(T("again"), T("right") + 0.5, rows, lit=pulse(3, "right"))
    return L


def build():
    L = Lesson("build", "Build and check", "The ghost lets go of every block you get right and turns red over a wrong one; the bar counts, a marker shows what is next.", action="build",
               tags=["build", "verify", "check", "progress", "wrong", "red", "next block", "marker"], duration=22.0, size=(11, 9, 11), focus=[5.5, 3.4, 5.5])
    stage(L)
    cottage_keys(L)
    L.key("x", "minecraft:stone")
    C = [3, 2, 3]
    real_layers = [list(l) for l in COTTAGE]
    real_layers[1][4] = "lxbpl"          # a stone where the planks belong, in the south wall where it can be seen: this one will be wrong
    L.group("ghost", COTTAGE, pos=C, mode="ghost", match=["real", "fix"])
    L.group("real", real_layers, pos=C, start="empty")
    L.group("fix", [["p"]], pos=[C[0] + 1, C[1] + 1, C[2] + 4], start="empty")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.65, focus=[5.5, 3.4, 5.5])
    L.cam(11, ease="inOut", yaw=0.95)
    L.cam(22, ease="inOut", yaw=0.785)

    B = Builder(L, "real")
    row0 = [[x, 0, 0] for x in range(5)]
    t = B.go(4.6, row0, 0.4)
    t = B.go(6.9, cells_of(COTTAGE, lambda ch, x, y, z: y == 0), 0.05)
    # layer 1, with the wrong block in it
    B.go(9.6, [c for c in cells_of(real_layers, lambda ch, x, y, z: y == 1)], 0.045)
    L.clear("real", 11.8, anim="fade", dur=0.3, cells=[[1, 1, 4]])
    L.reveal("fix", 12.4, anim="drop", all=True)
    L.overlay("label", 10.5, 12.0, at=[C[0] + 1.5, C[1] + 3.0, C[2] + 5.3], text="Wrong block", color="red", fade=0.2)
    # the next block: the marker walks along the first row of layer 2 as the blocks go down
    nxt = cells_of(COTTAGE, lambda ch, x, y, z: y == 2)[:4]
    times = [14.4, 15.2, 16.0, 16.8]
    marker_keys = [{"t": 13.4, "ease": "step", "at": [C[0] + nxt[0][0], C[1] + 2, C[2] + nxt[0][2]]}]
    for i, c in enumerate(nxt):
        B.go(times[i], [c], 0.0)
        if i + 1 < len(nxt):
            marker_keys.append({"t": times[i], "ease": "step", "at": [C[0] + nxt[i + 1][0], C[1] + 2, C[2] + nxt[i + 1][2]]})
    L.overlay("marker", 13.4, 17.6, at=[C[0] + nxt[0][0], C[1] + 2, C[2] + nxt[0][2]], keys=marker_keys, fade=0.3)
    L.overlay("label", 13.6, 17.4, at=[C[0] + 2.5, C[1] + 6.4, C[2] + 2.5], text="Next block", color="gold", fade=0.3)
    # the rest, then the reset
    B.go(17.9, cells_of(COTTAGE), 0.03)
    L.clear("real", 21.2, anim="fade", dur=0.5, all=True)
    L.clear("fix", 21.2, anim="fade", dur=0.5, all=True)

    L.panel("bar", 0.4, 21.0, label="Cottage", value=0, done=True, keys=[
        {"t": 4.6, "value": 0}, {"t": 6.4, "value": 0.05}, {"t": 8.2, "value": 0.24}, {"t": 9.6, "value": 0.24}, {"t": 10.8, "value": 0.38}, {"t": 12.2, "value": 0.37},
        {"t": 12.9, "value": 0.40}, {"t": 17.6, "value": 0.46}, {"t": 20.6, "value": 1.0}], fade=0.4)
    L.panel("stamp", 20.6, 21.4, text="DONE", color="green", size=2, fade=0.1)

    L.caption(0.0, 4.4, "The ghost shows what is still missing. The bar counts what you have built.", "1  Check")
    L.caption(4.4, 9.4, "Place a block where the ghost is and the ghost lets go of it.", "2  Build")
    L.caption(9.4, 13.2, "A block that does not match shows the ghost in red. Swap it for the right one.", "3  Wrong blocks go red")
    L.caption(13.2, 17.6, "In Build, turn on Mark the next block to build: a marker shows where to build next.", "4  The next block")
    L.caption(17.6, 22.0, "When everything matches the bar is full and a chime plays.", "5  Done")
    return L


def auto():
    L = Lesson("auto", "Let the mod place blocks", "Assist places what you look at, Sweep builds what is in reach while you walk, and a server is asked about first.", action="auto",
               tags=["auto", "assist", "sweep", "place", "server", "warning", "printer", "build"], duration=21.0, size=(11, 9, 11), focus=[5.5, 3.4, 5.5])
    stage(L)
    cottage_keys(L)
    C = [3, 2, 3]
    L.group("ghost", COTTAGE, pos=C, mode="ghost", match=["real"])
    L.group("real", COTTAGE, pos=C, start="empty")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.65, focus=[5.5, 3.4, 5.5])
    L.cam(10, ease="inOut", yaw=0.95)
    L.cam(21, ease="inOut", yaw=0.785)
    B = Builder(L, "real")

    # Assist: the crosshair goes over the foundation, a row after a row, and each ghost block under it goes down at once
    order = []
    for z in range(3):
        xs = range(5) if z % 2 == 0 else range(4, -1, -1)
        order += [[x, 0, z] for x in xs]
    t0 = 1.6
    for k, c in enumerate(order):
        L.cursor(t0 + 0.2 * k, ease="linear", at=[C[0] + c[0] + 0.5, C[1] + 1, C[2] + c[2] + 0.5], down=True, alpha=1) if k else L.cursor(t0, ease="inOut", at=[C[0] + c[0] + 0.5, C[1] + 1, C[2] + c[2] + 0.5], down=True)
    B.go(t0, order, 0.2, anim="pop", dur=0.15)
    L.cursor(0, screen=[0.8, 0.6], alpha=0)
    L.cursor(0.5, ease="out", alpha=1)
    L.cursor(t0 + 0.2 * len(order), down=False)
    L.cursor(t0 + 0.2 * len(order) + 0.6, ease="inOut", alpha=0)
    L.chips(1.4, 5.2, [["Hold use", "Place cobblestone"]], lit=[(0, t0, t0 + 0.2 * len(order))])

    # Sweep: the player walks round the house and everything within reach goes down, lowest layer first
    reach = 4.4
    stops = [(5.5, 10.4), (5.5, 1.7)]
    path = [(9.8, 10.2), (5.5, 10.4)]
    L.overlay("path", 5.6, 13.6, points=[[9.6, 2, 10.4], [5.5, 2, 10.4], [5.5, 2, 10.4], [9.4, 2, 6.0], [5.5, 2, 1.7]], color="dim", fade=0.3)

    def centre(c):
        return (C[0] + c[0] + 0.5, C[2] + c[2] + 0.5)

    walk = Follow(L, "ghost", "alpha")  # unused placeholder to keep the Follow import honest
    av_keys = [{"t": 5.6, "at": [9.6, 2, 10.4], "yaw": 2}, {"t": 6.8, "at": [5.5, 2, 10.4], "yaw": 2}, {"t": 9.8, "at": [5.5, 2, 10.4], "yaw": 2},
               {"t": 10.6, "at": [9.4, 2, 6.0], "yaw": 3}, {"t": 11.4, "at": [5.5, 2, 1.7], "yaw": 0}, {"t": 13.2, "at": [5.5, 2, 1.7], "yaw": 0}]
    L.overlay("avatar", 5.6, 13.6, at=av_keys[0]["at"], yaw=2, keys=av_keys, fade=0.3)
    L.overlay("disc", 5.6, 13.6, center=av_keys[0]["at"], radius=reach, keys=[{"t": k["t"], "center": k["at"]} for k in av_keys], fade=0.3)
    all_cells = cells_of(COTTAGE)

    def in_reach(c, stop):
        x, z = centre(c)
        return (x - stop[0]) ** 2 + (z - stop[1]) ** 2 <= reach ** 2

    first = [c for c in all_cells if in_reach(c, stops[0])]
    first.sort(key=lambda c: (c[1], (centre(c)[0] - stops[0][0]) ** 2 + (centre(c)[1] - stops[0][1]) ** 2))
    t = B.go(7.0, first, 0.04)
    second = [c for c in all_cells if in_reach(c, stops[1])]
    second.sort(key=lambda c: (c[1], (centre(c)[0] - stops[1][0]) ** 2 + (centre(c)[1] - stops[1][1]) ** 2))
    B.go(11.6, second, 0.04)
    L.panel("stamp", 1.4, 13.6, text="AUTO: ON", color="gold", size=1, rect=[0.74, 0.03, 0.24, 0.1], fade=0.3)

    # the server warning
    L.panel("warning", 13.9, 19.0, title="Auto-placing on a server",
            text="Auto-placing may break this server's rules and can get you banned. Cyanotype can't know what this server allows. Check the rules or ask the staff first. You're responsible for how you use it.",
            buttons=["Keep it off", "Enable on this server"], focus=0, wait=3, lit=-1, states=[{"t": 17.7, "lit": 1}], rect=[0.12, 0.12, 0.76, 0.68], fade=0.35)
    L.cursor(16.4, ease="inOut", screen=[0.85, 0.85], alpha=0)
    L.cursor(16.8, ease="out", alpha=1)
    L.cursor(17.5, ease="inOut", panel=["warning", 1])
    L.click(17.7, hold=0.25)
    L.cursor(18.6, ease="inOut", alpha=0)
    L.clear("real", 20.3, anim="fade", dur=0.6, all=True)

    L.caption(0.0, 5.4, "In Build choose Place what I look at, then hold use on a ghost block: it goes down at once. Nothing else can be placed.", "1  Assist")
    L.caption(5.4, 13.8, "Place everything in reach builds what is near you, lowest layer first, while you walk. Sped up here: four blocks a second in the game.", "2  Sweep")
    L.caption(13.8, 21.0, "On a server it asks first. Enable stays dim for three seconds so the warning gets read.", "3  Servers")
    return L


def materials():
    L = Lesson("materials", "What you still need", "Materials lists what the build needs, what you carry and what is left to get; click a row to see where it goes.", action="materials",
               tags=["materials", "list", "shopping", "inventory", "need", "have", "gold", "copy"], duration=16.5, size=(11, 9, 11), focus=[8.4, 3.2, -4.6])
    stage(L)
    cottage_keys(L)
    C = [3, 2, 3]
    L.group("ghost", COTTAGE, pos=C, mode="ghost")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.05, focus=[8.4, 3.2, -4.6])
    L.cam(8, ease="inOut", yaw=0.9)
    L.cam(16.5, ease="inOut", yaw=0.785)
    header = ["Material", "Need", "Have", "To get"]
    rows0 = [["Cobblestone", "27", "0", "27"], ["Spruce planks", "20", "0", "20"], ["Oak planks", "19", "0", "19"], ["Spruce stairs", "19", "0", "19"], ["Oak log", "8", "0", "8"],
             ["Spruce slab", "5", "0", "5"], ["Glass", "3", "0", "3"], ["Oak door", "1", "0", "1"]]
    rows1 = [list(r) for r in rows0]
    rows1[0][2], rows1[0][3] = "27", "enough"
    rows2 = [list(r) for r in rows1]
    rows2[2][2], rows2[2][3] = "19", "enough"
    rows3 = [list(r) for r in rows2]
    rows3[4][2], rows3[4][3] = "8", "enough"
    rows3[6][2], rows3[6][3] = "3", "enough"
    L.panel("list", 0.5, 16.0, title="Materials  Cottage", header=header, rows=rows0, hot=-1, rect=[0.34, 0.03, 0.65, 0.92], buttons=["Copy list", "Save file"], lit=-1,
            states=[{"t": 5.6, "hot": 2}, {"t": 7.6, "hot": 0}, {"t": 9.4, "hot": -1}, {"t": 10.2, "rows": rows1}, {"t": 11.4, "rows": rows2}, {"t": 12.4, "rows": rows3},
                    {"t": 14.6, "lit": 0}, {"t": 15.2, "lit": -1}, {"t": 15.6, "rows": rows0}], fade=0.4)
    L.overlay("tint", 5.8, 7.6, group="ghost", key="p", color="gold", alpha=0.7, pulse=True, fade=0.15)
    L.overlay("tint", 7.8, 9.4, group="ghost", key="c", color="gold", alpha=0.7, pulse=True, fade=0.15)
    L.cursor(0, screen=[0.9, 0.8], alpha=0)
    L.cursor(4.4, ease="out", alpha=1)
    L.cursor(5.3, ease="inOut", panel=["list", 2])
    L.click(5.6)
    L.cursor(7.3, ease="inOut", panel=["list", 0])
    L.click(7.6)
    L.cursor(14.0, ease="inOut", screen=[0.75, 0.86])
    L.cursor(14.4, ease="inOut", screen=[0.55, 0.86])
    L.click(14.6, hold=0.5)
    L.cursor(15.6, ease="inOut", alpha=0)
    L.overlay("label", 6.0, 7.6, at=[C[0] + 2.5, C[1] + 3.2, C[2] + 5.6], text="Oak planks go here", color="gold", fade=0.2)
    L.overlay("label", 8.0, 9.4, at=[C[0] + 2.5, C[1] + 0.4, C[2] + 5.6], text="Cobblestone goes here", color="gold", fade=0.2)

    L.caption(0.0, 4.6, "Materials counts what the build still needs and what you carry. The biggest shortage comes first.", "1  The list")
    L.caption(4.6, 9.8, "Click a row and the blocks that use it light up gold in the world.", "2  Where it goes")
    L.caption(9.8, 14.0, "As you gather blocks the list follows. Enough means you have them all.", "3  Gather")
    L.caption(14.0, 16.5, "Copy list or Save file takes the shopping list with you.", "4  Take it along")
    return L


def pick():
    L = Lesson("pick", "Pick a build to save", "Click a build and a box fits itself round it; drag or scroll to change it, then save.", action="pick",
               tags=["save", "pick", "smart pick", "select", "box", "build", "name"], duration=21.0, size=(12, 9, 11), focus=[5.2, 3.4, 5.0])
    stage(L, 12, 11)
    cottage_keys(L)
    L.keys(f="minecraft:oak_leaves[persistent=false]", F="minecraft:oak_fence")
    C = [2, 2, 3]
    L.group("cottage", COTTAGE, pos=C)
    L.group("tree", [["l"], ["l"], ["l"], ["l"], ["f"]], pos=[9, 2, 2])
    L.group("tree2", [["fff", "fff", "fff"], ["fff", "fff", "fff"]], pos=[8, 6, 1])
    L.group("fence", [["FFFFF"]], pos=[2, 2, 9])
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.45, focus=[5.4, 3.4, 5.2])
    L.cam(10, ease="inOut", yaw=0.9)
    L.cam(21, ease="inOut", yaw=0.785)
    wall = [C[0] + 1.5, C[1] + 1.7, C[2] + 5.0]
    L.cursor(0, screen=[0.85, 0.7], alpha=0)
    L.cursor(0.6, ease="out", alpha=1)
    L.cursor(2.6, ease="inOut", at=wall)
    L.click(3.0, hold=0.2)
    box_to = [C[0] + 5, C[1] + 6, C[2] + 5]
    box_from = [C[0], C[1], C[2]]
    centre_pt = [wall[0], wall[1], wall[2] - 0.0]
    L.overlay("box", 3.1, 14.8, from_=centre_pt, to=centre_pt, keys=[
        {"t": 3.1, "from": centre_pt, "to": [centre_pt[0] + 0.01, centre_pt[1] + 0.01, centre_pt[2] + 0.01]},
        {"t": 4.3, "ease": "out", "from": box_from, "to": box_to},
        {"t": 10.2, "from": box_from, "to": box_to}, {"t": 11.3, "ease": "inOut", "from": box_from, "to": [box_to[0] + 1, box_to[1], box_to[2]]},
        {"t": 12.3, "from": box_from, "to": [box_to[0] + 1, box_to[1], box_to[2]]}, {"t": 12.7, "ease": "inOut", "from": box_from, "to": box_to}], fade=0.2)
    L.overlay("label", 4.4, 9.2, at=[C[0] + 2.5, C[1] + 6.9, C[2] + 2.5], text="5 x 6 x 5", color="cyan", fade=0.3)
    # the east arrow of the box, dragged one block out and back
    arrow_keys = [{"t": 9.6, "at": [box_to[0], C[1] + 3, C[2] + 2.5]}, {"t": 10.2, "at": [box_to[0], C[1] + 3, C[2] + 2.5]}, {"t": 11.3, "ease": "inOut", "at": [box_to[0] + 1, C[1] + 3, C[2] + 2.5]},
                  {"t": 12.3, "at": [box_to[0] + 1, C[1] + 3, C[2] + 2.5]}, {"t": 12.7, "ease": "inOut", "at": [box_to[0], C[1] + 3, C[2] + 2.5]}, {"t": 13.8, "at": [box_to[0], C[1] + 3, C[2] + 2.5]}]
    L.overlay("arrow", 9.6, 13.8, at=[box_to[0], C[1] + 3, C[2] + 2.5], dir="+x", len=1.9, keys=arrow_keys + [{"t": 10.2, "hot": 1}, {"t": 11.4, "hot": 0}], fade=0.3)
    L.cursor(9.4, ease="inOut", at=[box_to[0] + 0.9, C[1] + 3, C[2] + 2.5])
    L.click(10.2, hold=1.2)
    L.cursor(11.3, ease="inOut", at=[box_to[0] + 1.9, C[1] + 3, C[2] + 2.5])
    L.cursor(12.6, ease="inOut", at=[C[0] + 3, C[1] + 7.2, C[2] + 3])
    L.click(12.7, hold=0.0)
    L.cursor(13.6, ease="inOut", screen=[0.85, 0.75])
    L.overlay("label", 10.5, 11.9, at=[box_to[0] + 1.6, C[1] + 4.6, C[2] + 2.5], text="+1 east", color="x", fade=0.1)
    # saving
    L.panel("save", 14.6, 20.2, name="My cottage", typed=0, hot=0, tags="house, wood", keys=[{"t": 15.4, "typed": 0}, {"t": 17.0, "ease": "linear", "typed": 10}, {"t": 18.3, "hot": 0}, {"t": 18.3, "hot": 1}, {"t": 18.8, "hot": 0}], fade=0.4)
    L.cursor(14.4, ease="inOut", screen=[0.85, 0.85])
    L.cursor(17.8, ease="inOut", panel=["save", 0])
    L.click(18.3, hold=0.3)
    L.cursor(19.2, ease="inOut", alpha=0)
    L.panel("stamp", 18.7, 20.0, text="Saved to your Library", color="green", size=1, rect=[0.2, 0.04, 0.6, 0.12], fade=0.2)

    L.caption(0.0, 4.4, "Open Save and choose Pick a build. Aim at the build and click once.", "1  Click it")
    L.caption(4.4, 9.4, "A box fits itself round the build, leaving out the tree, the fence and the lawn.", "2  The box")
    L.caption(9.4, 14.4, "Drag a side to change it, or scroll to grow the side you face.", "3  Adjust")
    L.caption(14.4, 21.0, "Right click to save. Give it a name and it goes into your Library.", "4  Save it")
    L.chips(0.4, 4.4, [["Click", "Fit a box round this build"], ["V", "Cancel"], J], lit=[(0, 3.0, 3.4)])
    L.chips(4.4, 14.4, [["Drag", "Move the east side"], ["Scroll", "Grow / shrink the box"], ["Right click", "Save it..."], ["V", "Cancel"], J], lit=[(0, 10.2, 11.6), (1, 12.5, 12.9)])
    return L


def box():
    L = Lesson("box", "Select a box", "Click two corners, then drag the sides until the box holds your build; right click to save.", action="box",
               tags=["save", "box", "select", "corner", "area", "size"], duration=17.0, size=(11, 9, 11), focus=[5.5, 3.6, 5.5])
    stage(L)
    cottage_keys(L)
    C = [3, 2, 3]
    L.group("cottage", COTTAGE, pos=C)
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.4, focus=[5.5, 3.8, 5.5])
    L.cam(8.5, ease="inOut", yaw=0.93)
    L.cam(17, ease="inOut", yaw=0.785)
    c1, c2 = [2, 2, 2], [9, 3, 9]
    L.cursor(0, screen=[0.85, 0.7], alpha=0)
    L.cursor(0.5, ease="out", alpha=1)
    L.cursor(1.8, ease="inOut", at=[c1[0] + 0.5, c1[1], c1[2] + 0.5])
    L.click(2.2)
    L.cursor(4.6, ease="inOut", at=[c2[0] - 0.5, c2[1] - 1, c2[2] - 0.5])
    L.click(5.0)
    L.overlay("marker", 2.2, 5.0, at=[c1[0], c1[1], c1[2]], beam=False, color="cyan", fade=0.1)
    # the box between the corners; scroll raises its top, then two sides are dragged in and scroll shrinks the other two
    steps = [
        (5.1, [2, 2, 2], [9, 3, 9], "step"), (6.0, [2, 2, 2], [9, 4, 9], "step"), (6.3, [2, 2, 2], [9, 5, 9], "step"), (6.6, [2, 2, 2], [9, 6, 9], "step"),
        (6.9, [2, 2, 2], [9, 7, 9], "step"), (7.2, [2, 2, 2], [9, 8, 9], "step"),
        (9.6, [2, 2, 2], [9, 8, 9], None), (10.5, [3, 2, 2], [9, 8, 9], "inOut"), (11.0, [3, 2, 2], [9, 8, 9], None), (11.9, [3, 2, 3], [9, 8, 9], "inOut"),
        (12.5, [3, 2, 3], [8, 8, 9], "step"), (13.0, [3, 2, 3], [8, 8, 8], "step")]
    keys = []
    for t, f, to, e in steps:
        k = {"t": t, "from": f, "to": to}
        if e:
            k["ease"] = e
        keys.append(k)
    L.overlay("box", 5.1, 14.6, from_=[2, 2, 2], to=[9, 3, 9], keys=keys, fade=0.2)
    # the arrows that are dragged: the west one from x = 2 to 3, the north one from z = 2 to 3
    L.overlay("arrow", 9.0, 10.9, at=[2, 5, 5.5], dir="-x", len=1.9, keys=[{"t": 9.0, "at": [2, 5, 5.5]}, {"t": 9.6, "at": [2, 5, 5.5], "hot": 1}, {"t": 10.5, "ease": "inOut", "at": [3, 5, 5.5]}, {"t": 10.8, "hot": 0}], fade=0.2)
    L.overlay("arrow", 10.9, 12.2, at=[5.5, 5, 2], dir="-z", len=1.9, keys=[{"t": 10.9, "at": [5.5, 5, 2]}, {"t": 11.0, "at": [5.5, 5, 2], "hot": 1}, {"t": 11.9, "ease": "inOut", "at": [5.5, 5, 3]}, {"t": 12.1, "hot": 0}], fade=0.2)
    L.cursor(8.6, ease="inOut", screen=[0.85, 0.7])
    L.cursor(9.4, ease="inOut", at=[1.1, 5, 5.5])
    L.click(9.6, hold=1.0)
    L.cursor(10.5, ease="inOut", at=[2.1, 5, 5.5])
    L.cursor(10.9, ease="inOut", at=[5.5, 5, 1.1])
    L.click(11.0, hold=0.95)
    L.cursor(11.9, ease="inOut", at=[5.5, 5, 2.1])
    L.cursor(12.6, ease="inOut", alpha=0)
    L.overlay("label", 7.4, 9.2, at=[5.5, 10.4, 5.5], text="6 layers tall", color="cyan", fade=0.2)
    L.overlay("label", 13.2, 14.6, at=[5.5, 10.4, 5.5], text="5 x 6 x 5", color="cyan", fade=0.2)
    L.panel("save", 14.8, 16.6, name="My cottage", typed=10, hot=0, tags="house, wood", fade=0.4)

    L.caption(0.0, 4.2, "Open Save and choose Select a box. Click one corner.", "1  First corner")
    L.caption(4.2, 8.8, "Click the opposite corner. Scroll raises or lowers the box.", "2  Second corner")
    L.caption(8.8, 14.4, "Drag a side in, or scroll to shrink the side you face, until the box holds just your build.", "3  Fit it")
    L.caption(14.4, 17.0, "Right click to save it.", "4  Save")
    L.chips(0.4, 4.2, [["Click", "First corner"], ["V", "Cancel"], J], lit=[(0, 2.2, 2.6)])
    L.chips(4.2, 8.8, [["Click", "Second corner"], ["Scroll", "Raise / lower it"], ["Right click", "Back"], ["V", "Cancel"], J], lit=[(0, 5.0, 5.4), (1, 6.0, 7.4)])
    L.chips(8.8, 14.4, [["Drag", "Move the west side"], ["Scroll", "Grow / shrink the box"], ["Right click", "Save it..."], ["V", "Cancel"], J], lit=[(0, 9.6, 10.6), (0, 11.0, 12.0), (1, 12.5, 13.2)])
    return L


def paste():
    L = Lesson("paste", "Paste into the world", "In creative, in a world you host, P puts the whole build in at once and Ctrl+Z takes it back.", action="paste",
               tags=["paste", "creative", "instant", "undo", "world"], duration=12.0, size=(11, 9, 11), focus=[5.5, 3.4, 5.5])
    stage(L)
    cottage_keys(L)
    C = [3, 2, 3]
    L.group("ghost", COTTAGE, pos=C, mode="ghost", match=["real"])
    L.group("real", COTTAGE, pos=C, start="empty")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.65, focus=[5.5, 3.4, 5.5])
    L.cam(6, ease="inOut", yaw=0.9)
    L.cam(12, ease="inOut", yaw=0.785)
    cells = cells_of(COTTAGE)
    L.reveal("real", 4.2, anim="drop", dur=0.3, stagger=0.02, order="given", cells=cells)
    L.move("ghost", 0, visible=1)
    L.move("ghost", 6.6, ease="step", visible=0)
    L.move("ghost", 8.4, ease="step", visible=1)
    L.panel("bar", 4.0, 6.8, label="Pasting into the world", value=0, rect=[0.2, 0.03, 0.6, 0.12], keys=[{"t": 4.2, "value": 0}, {"t": 6.3, "ease": "linear", "value": 1.0}], fade=0.2)
    L.clear("real", 8.4, anim="fade", dur=0.3, stagger=0.015, order="given", cells=list(reversed(cells)))
    L.caption(0.0, 3.8, "In creative, in a world you host, aim the ghost where it should go and press P.", "1  Press P")
    L.caption(3.8, 7.6, "The whole build goes into the world at once, and the ghost steps aside.", "2  It is there")
    L.caption(7.6, 12.0, "Ctrl and Z puts everything back, the old blocks and chests too, and the ghost returns.", "3  Undo")
    L.chips(0.4, 3.8, [["Scroll", "Turn"], ["Shift+Scroll", "Up / down"], ["M", "Mirror"], ["Click", "Lock in place"], ["P", "Paste it here"], ["V", "Cancel"], J], lit=[(4, 3.4, 3.8)])
    L.chips(7.6, 12.0, [["Ctrl+Z / Y", "Undo / Redo"]], lit=[(0, 8.2, 8.8)])
    return L


LESSONS = [place, edit, layers, build, auto, materials, pick, box, paste]
