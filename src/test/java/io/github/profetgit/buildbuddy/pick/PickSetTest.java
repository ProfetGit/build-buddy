package io.github.profetgit.buildbuddy.pick;

import static io.github.profetgit.buildbuddy.pick.TestWorld.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.buildbuddy.TestBootstrap;
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
    void theOtherPartsOfTheStructureAreContextAndNotPicked() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        house(w, 24, 10);
        for (int x = 17; x <= 23; x++) w.set(x, 1, 12, Blocks.OAK_FENCE);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        s.start(key(12, 3, 10), null);
        run(s);
        assertFalse(s.picked().contains(key(26, 3, 10)), "the second house is not the build clicked");
        assertTrue(s.context().contains(key(26, 3, 10)), "but it was seen: it is what 'leave out the other buildings' leaves out");
        assertFalse(s.context().contains(key(12, 3, 10)));
        assertTrue(s.otherParts() >= 1);
        int[] b = s.bounds();
        assertEquals(10, b[0]);
        assertTrue(b[3] <= 16, "the box is round the first house only: " + b[3]);
    }

    @Test
    void aFailedPickSaysWhyAndPicksNothing() {
        TestWorld w = new TestWorld();
        w.hut(40, 40, 79, 79, 30, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS);
        PickSet s = new PickSet(w, 400);
        s.start(key(50, 10, 40), null);
        run(s);
        assertTrue(s.picked().isEmpty());
        String notice = s.takeNotice();
        assertTrue(notice.contains("more than one build"), notice);
        assertEquals("", s.takeNotice(), "a notice is read once");
    }

    @Test
    void aFreshStartReplacesThePick() {
        TestWorld w = new TestWorld();
        house(w, 10, 10);
        house(w, 60, 10);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        s.start(key(12, 3, 10), null);
        run(s);
        assertTrue(s.picked().contains(key(12, 3, 10)));
        s.start(key(62, 3, 10), null);
        run(s);
        assertTrue(s.picked().contains(key(62, 3, 10)));
        assertFalse(s.picked().contains(key(12, 3, 10)));
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
        assertEquals(0, s.otherParts());
        assertEquals(null, s.bounds());
    }
}
