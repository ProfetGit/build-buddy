package io.github.profetgit.cyanotype.pick;

import static io.github.profetgit.cyanotype.pick.TestWorld.key;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.TestBootstrap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoxFilterTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static TestWorld world() {
        TestWorld w = new TestWorld();
        w.hut(10, 10, 16, 14, 3, Blocks.OAK_PLANKS, Blocks.STONE_BRICKS, Blocks.OAK_SLAB);
        // a tree: a trunk with leaves round it
        w.sceneryFill(12, 2, 12, 12, 6, 12, Blocks.OAK_LOG);
        w.sceneryFill(10, 5, 10, 14, 7, 14, Blocks.OAK_LEAVES);
        w.set(12, 5, 12, Blocks.OAK_LOG.defaultBlockState(), false);
        w.scenery(11, 2, 11, Blocks.SHORT_GRASS);
        w.scenery(13, 2, 13, Blocks.SUGAR_CANE);
        return w;
    }

    @Test
    void aBoxThatKeepsEverythingNeedsNoMask() {
        BoxFilter f = new BoxFilter(world(), true, true, null);
        assertFalse(f.filters());
        assertTrue(f.keep(12, 3, 12) && f.keep(11, 0, 11) && f.keep(13, 2, 13));
    }

    @Test
    void groundGoesWhenAskedAndTheBuildStays() {
        TestWorld w = world();
        BoxFilter f = new BoxFilter(w, false, true, null);
        assertTrue(f.filters());
        assertFalse(f.keep(11, 0, 11), "the grass block");
        assertFalse(f.keep(11, -1, 11), "the dirt under it");
        assertTrue(f.keep(10, 3, 10), "a wall");
        assertTrue(f.keep(12, 3, 12), "trees are kept: only ground was switched off");
        assertTrue(f.keep(13, 2, 13), "and plants");
    }

    @Test
    void treesAndPlantsGoWhenAskedButABeamStays() {
        TestWorld w = world();
        BoxFilter f = new BoxFilter(w, true, false, null);
        assertFalse(f.keep(12, 4, 12), "a trunk (leaves round it)");
        assertFalse(f.keep(11, 6, 11), "leaves");
        assertFalse(f.keep(11, 2, 11), "grass");
        assertFalse(f.keep(13, 2, 13), "sugar cane");
        assertTrue(f.keep(10, 3, 10), "a wall");
        // a log far from any leaves is a beam
        w.set(30, 2, 30, Blocks.OAK_LOG);
        assertTrue(f.keep(30, 2, 30));
        assertTrue(f.keep(11, 0, 11), "the ground is kept: only trees and plants were switched off");
    }

    @Test
    void theOtherPartsOfASmartPickGoAndCellsThePickerNeverSawStay() {
        TestWorld w = world();
        LongOpenHashSet others = new LongOpenHashSet();
        others.add(key(15, 3, 10));
        BoxFilter f = new BoxFilter(w, true, true, others);
        assertTrue(f.filters());
        assertFalse(f.keep(15, 3, 10), "a cell of another part");
        assertTrue(f.keep(14, 3, 10), "the build's own wall");
        assertTrue(f.keep(40, 3, 40), "never seen: kept");
    }
}
