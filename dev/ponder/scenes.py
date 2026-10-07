"""The lessons. Run make.py to write them into src/main/resources/assets/cyanotype/ponders/."""
from pondlib import Lesson, Follow, Builder, add, stage, cells_of, cottage_keys, COTTAGE

GHOST_FLOAT = 0.18   # a ghost that follows the crosshair floats a little above the ground, as in the game
TOP = 2.0            # the top of the ground slab


def centred(cx, cz, y=TOP + GHOST_FLOAT):
    """Where the cottage's corner goes so that its 5 x 5 footprint is centred on the cell (cx, cz)."""
    return [cx - 2.5, y, cz - 2.5]


def place():
    L = Lesson("place", "Place a blueprint", "Pick a blueprint, turn it, lift it, mirror it and click to put it down.", action="place",
               tags=["library", "ghost", "turn", "lift", "mirror", "lock", "place"], duration=18.0, size=(11, 9, 11), focus=[5.5, 3.2, 5.5])
    stage(L)
    cottage_keys(L)
    L.group("ghost", COTTAGE, pos=centred(3.5, 9.5), mode="ghost")
    # camera: a slow drift out and back, so the lesson loops
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.6, focus=[5.5, 3.2, 5.5])
    L.cam(9, ease="inOut", yaw=0.90)
    L.cam(18, ease="inOut", yaw=0.785)

    A, B = (3.5, 8.5), (7.5, 6.5)
    # the ghost comes when the card is clicked, follows the cursor, then is turned, lifted, mirrored and locked
    L.move("ghost", 0, visible=0, alpha=0, turn=0, mirror=0, pos=centred(*A))
    L.move("ghost", 1.9, visible=1)
    L.move("ghost", 2.3, ease="out", alpha=1)
    L.move("ghost", 2.6, pos=centred(*A))
    L.move("ghost", 4.4, ease="inOut", pos=centred(*B))
    L.move("ghost", 5.0, turn=0)
    L.move("ghost", 5.7, ease="inOut", turn=1)
    L.move("ghost", 6.7, turn=1)
    L.move("ghost", 7.4, ease="inOut", turn=2)
    lift = centred(*B)
    up = [lift[0], lift[1] + 1, lift[2]]
    L.move("ghost", 9.4, pos=centred(*B))
    L.move("ghost", 10.0, ease="inOut", pos=up)
    L.move("ghost", 10.6, pos=up)
    L.move("ghost", 11.1, ease="inOut", pos=centred(*B))
    L.move("ghost", 11.9, mirror=1)
    L.move("ghost", 13.2, pos=centred(*B))
    L.move("ghost", 13.9, ease="spring", pos=centred(B[0], B[1], TOP))
    L.move("ghost", 17.0, alpha=1)
    L.move("ghost", 17.6, ease="inOut", alpha=0)
    L.move("ghost", 17.7, ease="step", visible=0, turn=0)
    L.move("ghost", 17.7, mirror=0)

    # the cursor: the card in the Library, then the ground
    L.cursor(0, screen=[0.7, 0.62], alpha=0)
    L.cursor(0.4, ease="out", alpha=1)
    L.cursor(1.2, ease="inOut", panel=["library", 0])
    L.click(1.55)
    L.cursor(2.6, ease="inOut", at=(A[0], TOP, A[1]))
    L.cursor(4.4, ease="inOut", at=(B[0], TOP, B[1]))
    L.cursor(13.2, at=(B[0], TOP, B[1]))
    L.click(13.3)
    L.cursor(16.6, at=(B[0], TOP, B[1]))
    L.cursor(17.2, ease="inOut", alpha=0)
    L.cursor(18.0, ease="inOut", screen=[0.7, 0.62], alpha=0)

    L.panel("library", 0.0, 2.3, cards=["Cottage", "Barn", "Windmill"], keys=[{"t": 0, "hot": -1}, {"t": 1.2, "hot": 0}])

    L.caption(0.0, 4.6, "Pick a blueprint in the Library. It follows where you look.", "1  Pick it")
    L.caption(4.6, 8.8, "Scroll to turn it a quarter at a time.", "2  Turn it")
    L.caption(8.8, 12.6, "Shift and scroll lifts it, M mirrors it.", "3  Lift or mirror it")
    L.caption(12.6, 18.0, "Click to put it down. It settles and stays where you left it.", "4  Lock it")

    L.chips(2.6, 4.6, [["Click", "Lock it"]])
    L.chips(4.6, 8.8, [["Scroll", "Turn it"], ["Click", "Lock it"]], lit=[(0, 5.0, 5.7), (0, 6.7, 7.4)])
    L.chips(8.8, 12.6, [["Shift+Scroll", "Lift it"], ["M", "Mirror it"], ["Click", "Lock it"]], lit=[(0, 9.4, 10.0), (0, 10.6, 11.1), (1, 11.9, 12.5)])
    L.chips(12.6, 18.0, [["Click", "Lock it"], ["V", "Cancel"]], lit=[(0, 13.3, 13.9)])

    cx, cz = B
    L.overlay("dim", 9.4, 11.1, a=[cx + 3.2, TOP, cz + 3.2], b=[cx + 3.2, TOP + 1, cz + 3.2], text="+1", color="gold",
              keys=[{"t": 9.4, "b": [cx + 3.2, TOP, cz + 3.2]}, {"t": 10.0, "ease": "inOut", "b": [cx + 3.2, TOP + 1, cz + 3.2]}, {"t": 10.6, "b": [cx + 3.2, TOP + 1, cz + 3.2]},
                    {"t": 11.1, "ease": "inOut", "b": [cx + 3.2, TOP, cz + 3.2]}])
    L.overlay("label", 14.4, 17.0, at=[cx, TOP + 6.6, cz], text="Locked", color="green")
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
    L = Lesson("edit", "Move and turn a build", "Drag arrows or the ring, or carry the whole build, and undo anything.", action="edit",
               tags=["edit", "move", "turn", "carry", "arrows", "ring", "undo", "mirror", "delete"], duration=21.0, size=(12, 9, 12), focus=[6.0, 3.4, 6.0])
    stage(L, 12, 12)
    cottage_keys(L)
    P0, P1, P2 = [3, 2, 4], [6, 2, 4], [1, 2, 6]
    L.group("ghost", COTTAGE, pos=P0, mode="ghost")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.5, focus=[6.0, 3.4, 6.0])
    L.cam(10, ease="inOut", yaw=0.9)
    L.cam(21, ease="inOut", yaw=0.785)

    pos = Follow(L, "ghost", "pos")
    turn = Follow(L, "ghost", "turn")
    # step 2: drag the east arrow three blocks; step 4: carry; step 5: undo all three
    pos.key(0, P0)
    pos.key(5.3, P0)
    pos.key(6.8, P1)
    pos.key(13.6, P1)
    pos.key(14.7, [3.5, 3.2, 5.0])
    pos.key(15.6, P2)
    pos.key(17.7, P2)
    pos.key(18.3, P1)
    pos.key(19.8, P1)
    pos.key(20.4, P0)
    turn.key(0, 0)
    turn.key(10.0, 0, "linear")
    turn.key(11.4, 1, "linear")
    turn.key(18.8, 1)
    turn.key(19.4, 0)
    L.move("ghost", 0, mirror=0)

    hot = {"east": [(0, 0), (4.8, 1), (6.9, 0)], "ring": [(0, 0), (9.8, 1), (11.5, 0)]}
    # the handles are there from the start, go while the build is carried, and come back
    handles(L, pos, turn, 0.6, 13.4, hot=hot)
    handles(L, pos, turn, 16.4, 20.6)

    # the cursor
    def at_east(p):
        return add(p, (5.8, 3, 2.5))

    L.cursor(0, screen=[0.82, 0.55], alpha=0)
    L.cursor(4.2, ease="out", alpha=1)
    L.cursor(5.0, ease="inOut", at=at_east(P0))
    L.click(5.2, hold=1.7)
    L.cursor(6.8, ease="inOut", at=at_east(P1))
    L.cursor(9.6, ease="inOut", at=add(P1, (2.5 + 3.6, 0.05, 2.5)))
    L.click(9.9, hold=1.6)
    # round the ring a quarter turn clockwise seen from above: east to south
    import math
    ring_c = add(P1, (2.5, 0.05, 2.5))
    for i in range(5):
        a = math.radians(90 * i / 4)
        L.cursor(10.0 + 1.4 * i / 4, ease="linear", at=[ring_c[0] + 3.6 * math.cos(a), ring_c[1], ring_c[2] + 3.6 * math.sin(a)])
    L.cursor(12.6, ease="inOut", at=add(P1, (2.5, 2.2, 5.0)))
    L.click(13.6, hold=2.0)
    # carried: the cursor keeps its spot on the build
    for t, p in ((13.6, P1), (14.7, [3.5, 3.2, 5.0]), (15.6, P2)):
        L.cursor(t, ease="inOut", at=add(p, (2.5, 2.2, 5.0)))
    L.cursor(16.4, ease="inOut", alpha=0)
    L.cursor(21.0, alpha=0)

    # labels: how far, counting, and the angle and the carry
    for text, a, b in (("+1 east", 5.7, 6.1), ("+2 east", 6.1, 6.5), ("+3 east", 6.5, 7.9)):
        L.overlay("label", a, b, at=add(P0, (2.5, 7.4, 2.5)), text=text, color="x", keys=pos.derive("at", lambda v: add(v, (2.5, 7.4, 2.5)), a, b), fade=0)
    L.overlay("label", 10.7, 12.4, at=add(P1, (2.5, 7.4, 2.5)), text="90 degrees", color="ring", fade=0.2)
    L.overlay("label", 14.2, 15.9, at=[6.0, 8.4, 7.5], text="5 west, 2 south", color="gold", keys=pos.derive("at", lambda v: add(v, (2.5, 7.4, 2.5)), 14.2, 15.9), fade=0.2)

    L.caption(0.0, 4.2, "Tap V to edit. Arrows and a ring appear round the build.", "1  Handles")
    L.caption(4.2, 9.0, "Drag an arrow to move the build along that line, a whole block at a time.", "2  Drag an arrow")
    L.caption(9.0, 13.0, "Drag the ring for quarter turns. Ctrl and scroll turns from anywhere.", "3  Turn it")
    L.caption(13.0, 17.0, "Or press on the build itself and look where it should go. Let go to drop it.", "4  Carry it")
    L.caption(17.0, 21.0, "Ctrl and Z undoes your last move, Ctrl and Y does it again. Delete removes the build.", "5  Undo")

    L.chips(0.0, 4.2, [["Drag", "Carry the build"], ["Scroll", "Push east"], ["Ctrl+Scroll", "Turn"], ["M", "Mirror"], ["Ctrl+Z / Y", "Undo / Redo"], ["Delete", "Remove"], ["V", "Done"]])
    L.chips(4.2, 9.0, [["Drag", "Move east or west"], ["Scroll", "Push east"], ["V", "Done"]], lit=[(0, 5.2, 6.9)])
    L.chips(9.0, 13.0, [["Drag", "Turn in quarter turns"], ["Ctrl+Scroll", "Turn"], ["M", "Mirror"]], lit=[(0, 9.9, 11.5)])
    L.chips(13.0, 13.6, [["Drag", "Carry the build"]])
    L.chips(13.6, 15.7, [["Release", "Drop it here"], ["Scroll", "Lift it up / down"], ["Right click", "Put it back"]], lit=[(0, 15.5, 15.7)])
    L.chips(15.7, 17.0, [["Drag", "Carry the build"], ["V", "Done"]])
    L.chips(17.0, 21.0, [["Ctrl+Z / Y", "Undo / Redo"], ["Delete", "Remove"], ["V", "Done"]], lit=[(0, 17.7, 18.3), (0, 18.8, 19.4), (0, 19.8, 20.4)])
    return L


# ---- the other lessons ------------------------------------------------------------------------------------------------

def layers():
    L = Lesson("layers", "Show a few layers", "Scroll a window of layers up and down the build, make it thicker, show it all again.", action="layers",
               tags=["layers", "slice", "window", "scroll", "floor", "level"], duration=17.0, size=(11, 9, 11), focus=[5.5, 3.4, 5.5])
    stage(L)
    cottage_keys(L)
    C = [3, 2, 3]
    L.group("ghost", COTTAGE, pos=C, mode="ghost")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.65, focus=[5.5, 3.4, 5.5])
    L.cam(8.5, ease="inOut", yaw=0.95)
    L.cam(17, ease="inOut", yaw=0.785)
    # the window of layers: (from time, lowest, highest); the tool starts on the lowest layer
    steps = [(0, 0, 5), (1.6, 0, 0), (4.2, 1, 1), (5.0, 2, 2), (5.8, 3, 3), (6.6, 4, 4), (7.6, 3, 3), (9.0, 3, 4), (9.8, 3, 5), (10.8, 3, 4), (11.6, 2, 3), (12.4, 2, 3), (14.6, 0, 5)]
    for t, lo, hi in steps:
        L.move("ghost", t, ease="step", window=[lo, hi])
    # the border of the window while the tool is on (1.6 to 12.4, and again from 14.0 to 14.6)
    box_keys = []
    for t, lo, hi in steps:
        if 1.6 <= t < 12.4:
            box_keys.append({"t": t, "ease": "step", "from": [C[0], C[1] + lo, C[2]], "to": [C[0] + 5, C[1] + hi + 1, C[2] + 5]})
    L.overlay("box", 1.6, 12.4, from_=[C[0], C[1], C[2]], to=[C[0] + 5, C[1] + 1, C[2] + 5], keys=box_keys, fade=0.25)
    L.overlay("box", 14.0, 14.6, from_=[C[0], C[1] + 2, C[2]], to=[C[0] + 5, C[1] + 4, C[2] + 5], fade=0.1)
    # which layers, in words
    spans = [(t, nxt, lo, hi) for (t, lo, hi), (nxt, _, _) in zip(steps[1:], steps[2:] + [(12.4, 0, 0)]) if t < 12.4]
    for t, nxt, lo, hi in spans:
        text = f"Layer {lo + 1} of 6" if lo == hi else f"Layers {lo + 1} to {hi + 1} of 6"
        L.overlay("label", t, nxt, at=[C[0] + 2.5, C[1] + hi + 1.6, C[2] + 2.5], text=text, color="cyan", fade=0.1)

    L.caption(0.0, 3.8, "Layers shows a few layers of the build at a time. It starts at the lowest one that is not finished.", "1  A window")
    L.caption(3.8, 8.6, "Scroll moves the window up and down the build.", "2  Move it")
    L.caption(8.6, 12.4, "Shift and scroll makes the window thicker or thinner.", "3  Thicker, thinner")
    L.caption(12.4, 17.0, "Click ends the tool and keeps what you see. In the tool, right click shows every layer again.", "4  Done")
    rows = [["Scroll", "Move up / down"], ["Shift+Scroll", "Thicker / thinner"], ["Click", "Done"], ["Right click", "Show all layers"]]
    L.chips(1.6, 12.4, rows, lit=[(0, 4.2, 4.6), (0, 5.0, 5.4), (0, 5.8, 6.2), (0, 6.6, 7.0), (0, 7.6, 8.0), (1, 9.0, 9.4), (1, 9.8, 10.2), (1, 10.8, 11.2), (0, 11.6, 12.0), (2, 12.2, 12.4)])
    L.chips(14.0, 14.6, rows, lit=[(3, 14.2, 14.6)])
    return L


def build():
    L = Lesson("build", "Build and check", "The ghost lets go of every block you get right and turns red over a wrong one; the bar counts, a marker shows what is next.", action="build",
               tags=["build", "verify", "check", "progress", "wrong", "red", "next block", "marker"], duration=22.0, size=(11, 9, 11), focus=[5.5, 3.4, 5.5])
    stage(L)
    cottage_keys(L)
    L.key("x", "minecraft:stone")
    C = [3, 2, 3]
    real_layers = [list(l) for l in COTTAGE]
    real_layers[1][0] = "lpxpl"          # a stone where the planks belong: this one will be wrong
    L.group("ghost", COTTAGE, pos=C, mode="ghost", match=["real", "fix"])
    L.group("real", real_layers, pos=C, start="empty")
    L.group("fix", [["p"]], pos=[C[0] + 2, C[1] + 1, C[2]], start="empty")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.65, focus=[5.5, 3.4, 5.5])
    L.cam(11, ease="inOut", yaw=0.95)
    L.cam(22, ease="inOut", yaw=0.785)

    B = Builder(L, "real")
    row0 = [[x, 0, 0] for x in range(5)]
    t = B.go(4.6, row0, 0.4)
    t = B.go(6.9, cells_of(COTTAGE, lambda ch, x, y, z: y == 0), 0.05)
    # layer 1, with the wrong block in it
    B.go(9.6, [c for c in cells_of(real_layers, lambda ch, x, y, z: y == 1)], 0.045)
    L.clear("real", 11.8, anim="fade", dur=0.3, cells=[[2, 1, 0]])
    L.reveal("fix", 12.4, anim="drop", all=True)
    L.overlay("label", 10.5, 12.0, at=[C[0] + 2.5, C[1] + 3.0, C[2] + 0.5], text="Wrong block", color="red", fade=0.2)
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
    L.panel("stamp", 20.7, 21.0, text="DONE", color="green", size=2, fade=0.1)

    L.caption(0.0, 4.4, "The ghost shows what is still missing. The bar counts what you have built.", "1  Check")
    L.caption(4.4, 9.4, "Place a block where the ghost is and the ghost lets go of it.", "2  Build")
    L.caption(9.4, 13.2, "A block that does not match shows the ghost in red. Swap it for the right one.", "3  Wrong blocks go red")
    L.caption(13.2, 17.6, "Turn on Mark the next block in Build: a marker shows where to build next.", "4  The next block")
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
            buttons=["Keep it off", "Enable on this server"], focus=0, wait=3, lit=-1, states=[{"t": 17.7, "lit": 1}], rect=[0.12, 0.06, 0.76, 0.82], fade=0.35)
    L.cursor(16.4, ease="inOut", screen=[0.85, 0.85], alpha=0)
    L.cursor(16.8, ease="out", alpha=1)
    L.cursor(17.5, ease="inOut", panel=["warning", 1])
    L.click(17.7, hold=0.25)
    L.cursor(18.6, ease="inOut", alpha=0)
    L.clear("real", 20.3, anim="fade", dur=0.6, all=True)

    L.caption(0.0, 5.4, "Assist: hold use on a ghost block and it goes down at once. Nothing else can be placed.", "1  Assist")
    L.caption(5.4, 13.8, "Sweep builds what is in reach, lowest layer first, while you walk. It is sped up here: the real pace is four blocks a second.", "2  Sweep")
    L.caption(13.8, 21.0, "On a server it asks first. Enable stays dim for three seconds so the warning gets read.", "3  Servers")
    return L


def materials():
    L = Lesson("materials", "What you still need", "Materials lists what the build needs, what you carry and what is left to get; click a row to see where it goes.", action="materials",
               tags=["materials", "list", "shopping", "inventory", "need", "have", "gold", "copy"], duration=16.5, size=(11, 9, 11), focus=[6.4, 3.2, 4.6])
    stage(L)
    cottage_keys(L)
    C = [3, 2, 3]
    L.group("ghost", COTTAGE, pos=C, mode="ghost")
    L.cam(0, yaw=0.785, pitch=0.55, zoom=1.5, focus=[6.4, 3.2, 4.6])
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
    L.panel("list", 0.5, 16.0, title="Materials  Cottage", header=header, rows=rows0, hot=-1, rect=[0.47, 0.03, 0.52, 0.9], buttons=["Copy list", "Save file"], lit=-1,
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
    L.chips(0.4, 4.4, [["Click", "Fit a box round this build"], ["V", "Cancel"]], lit=[(0, 3.0, 3.4)])
    L.chips(4.4, 14.4, [["Drag", "Move the east side"], ["Scroll", "Grow / shrink the box"], ["Right click", "Save it..."], ["V", "Cancel"]], lit=[(0, 10.2, 11.6), (1, 12.5, 12.9)])
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
    L.chips(0.4, 4.2, [["Click", "First corner"], ["V", "Cancel"]], lit=[(0, 2.2, 2.6)])
    L.chips(4.2, 8.8, [["Click", "Second corner"], ["Scroll", "Raise / lower it"], ["Right click", "Back"], ["V", "Cancel"]], lit=[(0, 5.0, 5.4), (1, 6.0, 7.4)])
    L.chips(8.8, 14.4, [["Drag", "Move the west side"], ["Scroll", "Grow / shrink the box"], ["Right click", "Save it..."], ["V", "Cancel"]], lit=[(0, 9.6, 10.6), (0, 11.0, 12.0), (1, 12.5, 13.2)])
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
    L.panel("bar", 4.0, 6.8, label="Pasting into the world", value=0, keys=[{"t": 4.2, "value": 0}, {"t": 6.3, "ease": "linear", "value": 1.0}], fade=0.2)
    L.clear("real", 8.4, anim="fade", dur=0.3, stagger=0.015, order="given", cells=list(reversed(cells)))
    L.caption(0.0, 3.8, "In creative, in a world you host, aim the ghost where it should go and press P.", "1  Press P")
    L.caption(3.8, 7.6, "The whole build goes into the world at once, and the ghost steps aside.", "2  It is there")
    L.caption(7.6, 12.0, "Ctrl and Z puts everything back, the old blocks and chests too, and the ghost returns.", "3  Undo")
    L.chips(0.4, 3.8, [["P", "Paste it here"], ["Click", "Lock in place"], ["V", "Cancel"]], lit=[(0, 3.4, 3.8)])
    L.chips(7.6, 12.0, [["Ctrl+Z / Y", "Undo / Redo"]], lit=[(0, 8.2, 8.8)])
    return L


LESSONS = [place, edit, layers, build, auto, materials, pick, box, paste]
