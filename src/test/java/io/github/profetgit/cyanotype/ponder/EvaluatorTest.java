package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EvaluatorTest {
    private static Snapshot.GroupView view(Snapshot s, String id) {
        for (Snapshot.GroupView g : s.groups) if (g.group.id.equals(id)) return g;
        throw new AssertionError("no group " + id);
    }

    private static Scene demo() {
        return SceneReader.read(SceneReaderTest.GOOD);
    }

    @Test
    void aFullGroupIsThereFromTheStartAndAnEmptyOneIsNot() {
        Snapshot s = Evaluator.at(demo(), 0);
        assertEquals(12, view(s, "ground").count());
        assertEquals(0, view(s, "real").count());
    }

    @Test
    void aRevealWithAStaggerBringsTheBlocksInOneByOneInOrder() {
        Scene sc = demo();
        Scene.Group real = sc.group("real");
        // layer 0 is 8 blocks ("ppp","psp","ppp" = 3 + 3 + 3 = 9, the middle is stone)
        Snapshot before = Evaluator.at(sc, 1.99);
        assertEquals(0, view(before, "real").count());
        Snapshot first = Evaluator.at(sc, 2.0);
        Snapshot.GroupView r = view(first, "real");
        assertEquals(1, r.count(), "only the first block has come at 2.0");
        int c0 = real.index(0, 0, 0);
        assertEquals(Snapshot.PRESENT, r.state[c0]);
        assertEquals(Snapshot.DROP_IN, r.anim[c0]);
        assertEquals(0f, r.progress[c0], 1e-6);
        // 0.1 s later the second one comes, the first is halfway through its 0.4 s drop
        Snapshot second = Evaluator.at(sc, 2.2);
        Snapshot.GroupView r2 = view(second, "real");
        assertEquals(3, r2.count());
        assertEquals(0.5f, r2.progress[c0], 1e-5);
        assertEquals(Snapshot.PRESENT, r2.state[real.index(1, 0, 0)]);
        assertEquals(Snapshot.ABSENT, r2.state[real.index(0, 0, 1)], "blocks come lowest row first (z, then x)");
        Snapshot done = Evaluator.at(sc, 5);
        Snapshot.GroupView r3 = view(done, "real");
        assertEquals(9, r3.count());
        assertEquals(Snapshot.NO_ANIM, r3.anim[c0]);
        assertEquals(1f, r3.progress[c0], 1e-6);
        assertEquals(Snapshot.ABSENT, r3.state[real.index(0, 1, 0)], "the layer above was not asked for");
    }

    @Test
    void aClearWithAFadeKeepsTheBlockUntilItHasFaded() {
        Scene sc = SceneReader.read("""
            {"format":1,"id":"c","title":"C","summary":"S","duration":6,
             "palette":{"p":"minecraft:oak_planks"},
             "groups":[{"id":"g","layers":[["pp"]]}],
             "ops":[{"t":2,"group":"g","do":"clear","all":true,"anim":"fade","dur":0.5}]}
            """);
        assertEquals(2, view(Evaluator.at(sc, 1.9), "g").count());
        Snapshot.GroupView mid = view(Evaluator.at(sc, 2.25), "g");
        assertEquals(2, mid.count());
        assertEquals(Snapshot.FADE_OUT, mid.anim[0]);
        assertEquals(0.5f, mid.progress[0], 1e-5);
        assertEquals(0, view(Evaluator.at(sc, 2.6), "g").count());
        assertEquals(0, view(Evaluator.at(sc, 5.9), "g").count());
    }

    @Test
    void aRevealAfterAClearBringsItBack() {
        Scene sc = SceneReader.read("""
            {"format":1,"id":"c","title":"C","summary":"S","duration":9,
             "palette":{"p":"minecraft:oak_planks"},
             "groups":[{"id":"g","layers":[["p"]]}],
             "ops":[{"t":2,"group":"g","do":"clear","all":true,"anim":"none"},{"t":5,"group":"g","do":"reveal","all":true,"anim":"pop"}]}
            """);
        assertEquals(1, view(Evaluator.at(sc, 1), "g").count());
        assertEquals(0, view(Evaluator.at(sc, 3), "g").count());
        Snapshot.GroupView back = view(Evaluator.at(sc, 5.1), "g");
        assertEquals(1, back.count());
        assertEquals(Snapshot.POP_IN, back.anim[0]);
    }

    @Test
    void theLayerWindowHidesTheOthers() {
        Scene sc = SceneReader.read("""
            {"format":1,"id":"c","title":"C","summary":"S","duration":9,
             "palette":{"p":"minecraft:oak_planks"},
             "groups":[{"id":"g","layers":[["pp"],["pp"],["pp"]]}],
             "tracks":{"groups":{"g":[{"t":0,"window":[0,2]},{"t":3,"window":[1,1],"ease":"step"}]}}}
            """);
        assertEquals(6, view(Evaluator.at(sc, 1), "g").count());
        Snapshot.GroupView w = view(Evaluator.at(sc, 4), "g");
        assertEquals(2, w.count());
        assertEquals(Snapshot.PRESENT, w.state[w.group.index(0, 1, 0)]);
        assertEquals(Snapshot.ABSENT, w.state[w.group.index(0, 0, 0)]);
        assertEquals(Snapshot.ABSENT, w.state[w.group.index(0, 2, 0)]);
    }

    @Test
    void anInvisibleGroupHasNoCells() {
        Scene sc = SceneReader.read("""
            {"format":1,"id":"c","title":"C","summary":"S","duration":9,
             "palette":{"p":"minecraft:oak_planks"},
             "groups":[{"id":"g","layers":[["pp"]]}],
             "tracks":{"groups":{"g":[{"t":0,"visible":1},{"t":4,"visible":0}]}}}
            """);
        assertEquals(2, view(Evaluator.at(sc, 3.9), "g").count());
        assertEquals(0, view(Evaluator.at(sc, 4.0), "g").count());
    }

    @Test
    void theTransformFollowsItsKeyframes() {
        Snapshot a = Evaluator.at(demo(), 0), mid = Evaluator.at(demo(), 2), b = Evaluator.at(demo(), 4.5);
        Snapshot.GroupView g0 = view(a, "ghost"), g1 = view(mid, "ghost"), g2 = view(b, "ghost");
        assertEquals(1, g0.pos[0], 1e-9);
        assertEquals(1.5, g1.pos[0], 1e-9, "halfway between the keys of an inOut ease is halfway");
        assertEquals(2, g2.pos[0], 1e-9);
        assertFalse(g1.mirror);
        assertTrue(view(Evaluator.at(demo(), 6.0), "ghost").mirror, "a mirror switches at its key");
    }

    @Test
    void aGhostMatchesWhatIsBuiltInTheRealGroup() {
        // ghost and real sit at the same place: the real group builds cell by cell
        Scene sc = SceneReader.read("""
            {"format":1,"id":"m","title":"M","summary":"S","duration":9,
             "palette":{"p":"minecraft:oak_planks","s":"minecraft:stone"},
             "groups":[
               {"id":"ghost","mode":"ghost","start":"full","match":"real","pos":[1,0,1],"layers":[["pp","pp"]]},
               {"id":"real","mode":"solid","start":"empty","pos":[1,0,1],"layers":[["ps","pp"]]}],
             "ops":[{"t":1,"group":"real","do":"reveal","cells":[[0,0,0],[1,0,0]],"anim":"none"}]}
            """);
        Snapshot.GroupView ghost0 = view(Evaluator.at(sc, 0.5), "ghost");
        assertEquals(4, ghost0.count());
        Snapshot.GroupView ghost = view(Evaluator.at(sc, 1.5), "ghost");
        Scene.Group g = ghost.group;
        assertEquals(Snapshot.ABSENT, ghost.state[g.index(0, 0, 0)], "the right block is built: the ghost lets go of it");
        assertEquals(Snapshot.WRONG, ghost.state[g.index(1, 0, 0)], "stone where planks belong is wrong");
        assertEquals(Snapshot.PRESENT, ghost.state[g.index(0, 0, 1)], "not built yet: still the ghost");
        assertEquals(Snapshot.PRESENT, ghost.state[g.index(1, 0, 1)]);
    }

    @Test
    void aFallingBlockDoesNotHideItsGhostUntilItHasLanded() {
        Scene sc = SceneReader.read("""
            {"format":1,"id":"m","title":"M","summary":"S","duration":9,
             "palette":{"p":"minecraft:oak_planks"},
             "groups":[
               {"id":"ghost","mode":"ghost","start":"full","match":"real","layers":[["p"]]},
               {"id":"real","mode":"solid","start":"empty","layers":[["p"]]}],
             "ops":[{"t":1,"group":"real","do":"reveal","all":true,"anim":"drop","dur":0.5}]}
            """);
        assertEquals(Snapshot.PRESENT, view(Evaluator.at(sc, 1.2), "ghost").state[0], "still falling");
        assertEquals(Snapshot.ABSENT, view(Evaluator.at(sc, 1.5), "ghost").state[0], "landed");
    }

    @Test
    void aTurnedGhostIsMatchedWhereItReallyStands() {
        // a 3x3 ghost with one block in its north-west corner, turned a quarter clockwise: the block goes to the north-east corner
        Scene sc = SceneReader.read("""
            {"format":1,"id":"m","title":"M","summary":"S","duration":9,
             "palette":{"p":"minecraft:oak_planks"},
             "groups":[
               {"id":"ghost","mode":"ghost","start":"full","match":"real","pos":[0,0,0],"layers":[["p..","...","..."]]},
               {"id":"real","mode":"solid","start":"empty","pos":[0,0,0],"layers":[["..p","...","..."]]}],
             "tracks":{"groups":{"ghost":[{"t":0,"turn":1}]}},
             "ops":[{"t":1,"group":"real","do":"reveal","all":true,"anim":"none"}]}
            """);
        Snapshot s = Evaluator.at(sc, 2);
        Snapshot.GroupView ghost = view(s, "ghost");
        double[] w = new double[3];
        ghost.toWorld(0.5, 0.5, 0.5, w);
        assertEquals(2.5, w[0], 1e-9);
        assertEquals(0.5, w[2], 1e-9);
        assertEquals(Snapshot.ABSENT, ghost.state[0], "after the turn the ghost block sits on the real one");
        assertEquals(ghost.group.index(0, 0, 0), ghost.cellAt(w[0], w[1], w[2]));
    }

    @Test
    void toWorldAndCellAtAreInverseUnderTurnMirrorAndScale() {
        Scene sc = SceneReader.read("""
            {"format":1,"id":"m","title":"M","summary":"S","duration":9,
             "palette":{"p":"minecraft:oak_planks"},
             "groups":[{"id":"g","pos":[3,1,-2],"layers":[["ppp","ppp"],["ppp","ppp"]]}],
             "tracks":{"groups":{"g":[{"t":0,"turn":0.37,"mirror":1,"scale":1.5}]}}}
            """);
        Snapshot.GroupView g = view(Evaluator.at(sc, 1), "g");
        double[] w = new double[3];
        for (int c = 0; c < g.group.volume(); c++) {
            int x = c % g.group.sx, z = (c / g.group.sx) % g.group.sz, y = c / (g.group.sx * g.group.sz);
            g.toWorld(x + 0.5, y + 0.5, z + 0.5, w);
            assertEquals(c, g.cellAt(w[0], w[1], w[2]), "cell " + x + "," + y + "," + z);
        }
    }

    private static double[] at(Snapshot.CursorView c) {
        double[] a = c.from().at(), b = c.to().at();
        double[] out = new double[a.length];
        for (int i = 0; i < out.length; i++) out[i] = a[i] + (b[i] - a[i]) * c.k();
        return out;
    }

    @Test
    void theCursorGlidesAndPressesAndRipples() {
        Scene sc = demo();
        Snapshot.CursorView c0 = Evaluator.at(sc, 0).cursor;
        assertNotNull(c0);
        assertEquals(Scene.Pt.WORLD, c0.from().kind());
        assertEquals(1, at(c0)[0], 1e-9);
        assertFalse(c0.down());
        Snapshot.CursorView mid = Evaluator.at(sc, 1.5).cursor;
        assertEquals(2, at(mid)[0], 1e-9, "inOut halfway");
        Snapshot.CursorView down = Evaluator.at(sc, 3.2).cursor;
        assertTrue(down.down());
        assertEquals(0.2, down.clickAge(), 1e-9);
        Snapshot.CursorView up = Evaluator.at(sc, 3.7).cursor;
        assertFalse(up.down());
        assertEquals(0.7, up.clickAge(), 1e-9, "the ripple goes on after the release");
        assertTrue(Evaluator.at(sc, 0).cursor.clickAge() > 1e6, "never pressed yet");
    }

    @Test
    void aCursorGlidesBetweenAPanelAndAWorldPoint() {
        Scene sc = SceneReader.read("""
            {"format":1,"id":"c","title":"C","summary":"S","duration":9,
             "panels":[{"type":"library","t0":0,"t1":5,"cards":["A","B"]}],
             "tracks":{"cursor":[{"t":0,"panel":["library",1]},{"t":4,"at":[3,2,3]}]}}
            """);
        Snapshot.CursorView start = Evaluator.at(sc, 0).cursor, mid = Evaluator.at(sc, 2).cursor, end = Evaluator.at(sc, 4).cursor;
        assertEquals(Scene.Pt.PANEL, start.from().kind());
        assertEquals("library", start.from().panel());
        assertEquals(1, start.from().row());
        assertEquals(0.5, mid.k(), 1e-9, "halfway, for the screen to blend the two places");
        assertEquals(Scene.Pt.WORLD, mid.to().kind());
        assertEquals(1.0, end.k(), 1e-9);
    }

    @Test
    void captionStepsChipsAndItemsFollowTime() {
        Scene sc = demo();
        assertEquals("One", Evaluator.at(sc, 1).caption.text());
        assertEquals("Second step", Evaluator.at(sc, 5).caption.text());
        assertEquals(1, Evaluator.at(sc, 5).step);
        assertEquals(2, Evaluator.at(sc, 5).steps);
        assertTrue(Evaluator.at(sc, 0.5).chips.isEmpty());
        assertEquals(2, Evaluator.at(sc, 1.5).chips.size());
        assertFalse(Evaluator.at(sc, 1.5).chips.get(0).lit());
        assertTrue(Evaluator.at(sc, 2.5).chips.get(0).lit());
        assertFalse(Evaluator.at(sc, 2.5).chips.get(1).lit());
        assertEquals(0, Evaluator.at(sc, 0).overlays.size());
        assertEquals(1, Evaluator.at(sc, 1.5).overlays.size());
        assertEquals(2, Evaluator.at(sc, 3).overlays.size());
        assertEquals(1, Evaluator.at(sc, 3).panels.size());
    }

    @Test
    void itemsFadeInAndOutAtTheirEnds() {
        Scene sc = demo();
        Snapshot.ItemView box = Evaluator.at(sc, 1.1).overlays.get(0);
        assertEquals(0.4, box.alpha, 1e-9, "a quarter second fade: 0.1 of it");
        assertEquals(1.0, Evaluator.at(sc, 3).overlays.get(0).alpha, 1e-9);
        assertEquals(0.4, Evaluator.at(sc, 8.9).overlays.get(0).alpha, 1e-9);
    }

    @Test
    void keyframedAndStatefulPropertiesFollowTime() {
        Scene sc = demo();
        Snapshot.ItemView box = Evaluator.at(sc, 3).overlays.get(0);
        assertEquals(4.5, box.vec("to", new double[3])[0], 1e-9, "halfway through the keys of the box");
        assertEquals(1, box.vec("from", new double[3])[0], 1e-9, "from is not keyed: the static value");
        Snapshot.ItemView bar = Evaluator.at(sc, 3).panels.get(0);
        assertEquals(0.5, bar.num("value", 0), 1e-9);
        assertEquals(0.9, Evaluator.at(sc, 6).panels.get(0).num("value", 0), 1e-9, "a state replaces it from its time on");
        assertEquals("Progress", Evaluator.at(sc, 6).panels.get(0).str("label", ""), "and keeps what it does not name");
    }

    @Test
    void theCameraDefaultsAndFollowsItsKeys() {
        Scene plain = SceneReader.read("{\"format\":1,\"id\":\"c\",\"title\":\"C\",\"summary\":\"S\",\"duration\":4}");
        Snapshot s = Evaluator.at(plain, 1);
        assertEquals(Evaluator.DEFAULT_YAW, s.yaw, 1e-9);
        assertEquals(1.0, s.zoom, 1e-9);
        assertNull(s.cursor);
        assertNull(s.caption);
        Snapshot d = Evaluator.at(demo(), 5);
        assertEquals(1.2, d.yaw, 1e-9);
        assertEquals(0.5, d.pitch, 1e-9, "a field not keyed again keeps its last value");
    }

    @Test
    void timeIsClampedToTheLesson() {
        assertEquals(0, Evaluator.at(demo(), -5).t, 1e-9);
        assertEquals(10, Evaluator.at(demo(), 99).t, 1e-9);
    }

    @Test
    void theSameTimeGivesTheSameSnapshot() {
        Scene sc = demo();
        Snapshot a = Evaluator.at(sc, 3.3), b = Evaluator.at(sc, 3.3);
        for (int i = 0; i < a.groups.size(); i++) {
            assertEquals(java.util.Arrays.toString(a.groups.get(i).state), java.util.Arrays.toString(b.groups.get(i).state));
            assertEquals(java.util.Arrays.toString(a.groups.get(i).pos), java.util.Arrays.toString(b.groups.get(i).pos));
        }
        assertEquals(a.yaw, b.yaw, 0);
    }
}
