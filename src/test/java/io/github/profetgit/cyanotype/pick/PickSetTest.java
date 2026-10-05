package io.github.profetgit.cyanotype.pick;

import static io.github.profetgit.cyanotype.pick.TestWorld.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.TestBootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PickSetTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static void house(TestWorld w, int x0, int z0) {
        w.hut(x0, z0, x0 + 6, z0 + 4, 3, Blocks.OAK_PLANKS, Blocks.STONE_BRICKS, Blocks.OAK_SLAB);
        w.remove(x0 + 3, 2, z0);
        w.remove(x0 + 3, 3, z0);
    }

    private static void run(PickSet s) {
        while (!s.step(50_000_000L)) {
            // run
        }
    }

    @Test
    void startPicksTheBuildAndNothingElse() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        assertTrue(s.empty());
        s.start(key(12, 3, 10), null);
        assertTrue(s.working());
        run(s);
        assertFalse(s.working());
        assertFalse(s.empty());
        assertEquals(w.built, s.picked());
        int[] b = s.bounds();
        assertEquals(10, b[0]);
        assertEquals(16, b[3]);
        assertEquals("", s.takeNotice());
    }

    @Test
    void anotherPartCanBeAddedAndTakenOutAgain() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        house(w, 24, 10);
        for (int x = 17; x <= 23; x++) w.set(x, 1, 12, Blocks.OAK_FENCE);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        s.start(key(12, 3, 10), null);
        run(s);
        assertFalse(s.picked().contains(key(26, 3, 10)));
        assertTrue(s.isContext(key(26, 3, 10)), "offered");
        assertFalse(s.isContext(key(12, 3, 10)));
        int before = s.picked().size();
        s.addPart(key(26, 3, 10), null);
        assertFalse(s.working(), "a part already reached needs no new work");
        assertTrue(s.picked().contains(key(26, 3, 10)));
        assertTrue(s.picked().size() > before * 2 - 20);
        assertTrue(s.removePart(key(26, 3, 10)));
        assertFalse(s.picked().contains(key(26, 3, 10)));
        assertEquals(before, s.picked().size());
        assertTrue(s.isContext(key(26, 3, 10)), "still offered after it was taken out");
        assertFalse(s.removePart(key(26, 3, 10)), "it is not picked any more");
        s.addPart(key(26, 3, 10), null);
        assertTrue(s.picked().contains(key(26, 3, 10)), "and it comes back");
    }

    @Test
    void aBuildFarAwayJoinsAsAFreshFlood() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        house(w, 60, 10);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        s.start(key(12, 3, 10), null);
        run(s);
        assertFalse(s.picked().contains(key(62, 3, 10)));
        s.addPart(key(62, 3, 10), null);
        assertTrue(s.working());
        run(s);
        assertTrue(s.picked().contains(key(62, 3, 10)));
        assertTrue(s.picked().contains(key(12, 3, 10)));
        assertEquals(2, s.floods().size());
    }

    @Test
    void theReachChangesWhatIsPickedAndTheOldPickStaysUntilTheNewOneIsReady() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        w.set(13, 7, 12, Blocks.LANTERN);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        s.start(key(12, 3, 10), null);
        run(s);
        assertTrue(s.picked().contains(key(13, 7, 12)));
        s.setReach(0);
        assertTrue(s.working());
        assertEquals(0, s.reachWanted());
        assertEquals(1, s.reach());
        assertTrue(s.picked().contains(key(13, 7, 12)), "still the old pick");
        run(s);
        assertEquals(0, s.reach());
        assertFalse(s.picked().contains(key(13, 7, 12)));
        s.setReach(9);
        run(s);
        assertEquals(Picker.MAX_REACH, s.reach());
    }

    @Test
    void whatWasTakenOutStaysOutWhenTheReachChanges() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        house(w, 24, 10);
        for (int x = 17; x <= 23; x++) w.set(x, 1, 12, Blocks.OAK_FENCE);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        s.start(key(12, 3, 10), null);
        run(s);
        s.addPart(key(26, 3, 10), null);
        s.removePart(key(26, 3, 10));
        s.setReach(2);
        run(s);
        assertFalse(s.picked().contains(key(26, 3, 10)));
        assertTrue(s.picked().contains(key(12, 3, 10)));
    }

    @Test
    void aFailedPickKeepsTheOldOneAndSaysWhy() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        w.hut(40, 40, 79, 79, 30, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS);
        PickSet s = new PickSet(w, 400);
        s.start(key(12, 3, 10), null);
        run(s);
        int n = s.picked().size();
        assertTrue(n > 0);
        s.start(key(50, 10, 40), null);
        run(s);
        assertEquals(n, s.picked().size(), "the earlier pick is still there");
        assertTrue(s.picked().contains(key(12, 3, 10)));
        String notice = s.takeNotice();
        assertTrue(notice.contains("more than one build"), notice);
        assertEquals("", s.takeNotice(), "a notice is read once");
    }

    @Test
    void aFreshStartForgetsWhatWasTakenOut() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        house(w, 24, 10);
        for (int x = 17; x <= 23; x++) w.set(x, 1, 12, Blocks.OAK_FENCE);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        s.start(key(12, 3, 10), null);
        run(s);
        s.addPart(key(26, 3, 10), null);
        s.removePart(key(26, 3, 10));
        s.start(key(26, 3, 10), null);
        run(s);
        assertTrue(s.picked().contains(key(26, 3, 10)));
        assertFalse(s.picked().contains(key(12, 3, 10)));
        assertNotNull(s.bounds());
    }

    @Test
    void clearEmptiesEverything() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        s.start(key(12, 3, 10), null);
        run(s);
        s.clear();
        assertTrue(s.empty());
        assertTrue(s.picked().isEmpty());
        assertEquals(null, s.bounds());
        assertFalse(s.working());
    }
}
