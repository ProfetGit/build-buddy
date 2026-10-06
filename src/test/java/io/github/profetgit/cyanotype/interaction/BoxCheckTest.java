package io.github.profetgit.cyanotype.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.TestBootstrap;
import io.github.profetgit.cyanotype.pick.Picker;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoxCheckTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static class W implements Picker.Field {
        final Map<Long, BlockState> m = new HashMap<>();

        @Override
        public BlockState state(int x, int y, int z) {
            return m.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean loaded(int x, int z) {
            return true;
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) m.put(BlockPos.asLong(x, y, z), b.defaultBlockState());
        }
    }

    /** A tower of planks 3 x 3 and 20 tall standing at 10..12. */
    private static W tower() {
        W w = new W();
        w.fill(10, 1, 10, 12, 20, 12, Blocks.OAK_PLANKS);
        return w;
    }

    @Test
    void aBoxThatStopsHalfWayUpATowerIsCutOnTop() {
        W w = tower();
        BoxCheck.Result r = BoxCheck.check(w, SelectionBox.of(9, 1, 9, 13, 10, 13), null);
        assertTrue(r.cut(Direction.UP));
        assertEquals(9, r.count[Direction.UP.ordinal()]);
        assertEquals(9, r.cells[Direction.UP.ordinal()].length);
        assertFalse(r.cut(Direction.DOWN) || r.cut(Direction.EAST) || r.cut(Direction.WEST) || r.cut(Direction.NORTH) || r.cut(Direction.SOUTH));
        assertEquals(9, r.total());
    }

    @Test
    void aBoxThatHoldsTheWholeTowerIsNotCut() {
        BoxCheck.Result r = BoxCheck.check(tower(), SelectionBox.of(9, 1, 9, 13, 21, 13), null);
        assertFalse(r.any());
    }

    @Test
    void aBoxCuttingTheSideOfAWallIsCutOnThatSideOnly() {
        W w = tower();
        BoxCheck.Result r = BoxCheck.check(w, SelectionBox.of(11, 1, 9, 14, 21, 13), null);
        assertTrue(r.cut(Direction.WEST));
        assertEquals(3 * 20, r.count[Direction.WEST.ordinal()]);
        assertFalse(r.cut(Direction.EAST) || r.cut(Direction.UP));
    }

    @Test
    void groundAndPlantsAreNotABuildSoTheyAreNeverCut() {
        W w = new W();
        w.fill(0, 0, 0, 30, 0, 30, Blocks.GRASS_BLOCK);
        w.fill(5, 1, 5, 9, 1, 9, Blocks.SHORT_GRASS);
        w.fill(5, -3, 5, 9, -1, 9, Blocks.DIRT);
        assertFalse(BoxCheck.check(w, SelectionBox.of(6, -2, 6, 8, 1, 8), null).any());
    }

    @Test
    void logBeamsOfABuildCountButATreesTrunkDoesNot() {
        W w = new W();
        w.fill(10, 1, 10, 12, 8, 10, Blocks.OAK_LOG);
        assertTrue(BoxCheck.check(w, SelectionBox.of(9, 1, 9, 13, 4, 11), null).cut(Direction.UP), "a bare log is a beam");
        W tree = new W();
        tree.fill(10, 1, 10, 12, 8, 10, Blocks.OAK_LOG);
        tree.fill(8, 6, 8, 14, 9, 12, Blocks.OAK_LEAVES);
        tree.fill(10, 6, 10, 12, 8, 10, Blocks.OAK_LOG);
        assertFalse(BoxCheck.check(tree, SelectionBox.of(9, 1, 9, 13, 4, 11), null).cut(Direction.UP), "a trunk with leaves near it is a tree");
    }

    @Test
    void cellsOfAnotherBuildingCanBeLeftOut() {
        W w = tower();
        long outside = BlockPos.asLong(11, 11, 11);
        // the other building: everything above the box
        it.unimi.dsi.fastutil.longs.LongOpenHashSet other = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int y = 11; y <= 20; y++) for (int z = 10; z <= 12; z++) for (int x = 10; x <= 12; x++) other.add(BlockPos.asLong(x, y, z));
        assertTrue(other.contains(outside));
        assertFalse(BoxCheck.check(w, SelectionBox.of(9, 1, 9, 13, 10, 13), other::contains).any());
    }

    @Test
    void fitGrowsTheBoxUntilTheBuildIsInsideAndNeverShrinksIt() {
        W w = tower();
        BoxCheck.Fit f = BoxCheck.fit(w, SelectionBox.of(9, 1, 9, 13, 10, 13), null, 96, 8_000_000L);
        assertEquals(20, f.box().y1());
        assertEquals(9, f.box().x0());
        assertEquals(13, f.box().x1());
        assertEquals(10, f.layers());
        assertFalse(f.stopped());
        assertFalse(BoxCheck.check(w, f.box(), null).any());
        // a box that is not cut stays as it is, margins and all
        BoxCheck.Fit again = BoxCheck.fit(w, SelectionBox.of(5, 0, 5, 20, 30, 20), null, 96, 8_000_000L);
        assertEquals(SelectionBox.of(5, 0, 5, 20, 30, 20), again.box());
        assertEquals(0, again.layers());
    }

    @Test
    void fitStopsAtTheLimitAndSaysSo() {
        W w = new W();
        w.fill(10, 1, 10, 12, 400, 12, Blocks.OAK_PLANKS);
        BoxCheck.Fit f = BoxCheck.fit(w, SelectionBox.of(9, 1, 9, 13, 10, 13), null, 50, 8_000_000L);
        assertEquals(50, f.layers());
        assertTrue(f.stopped());
        assertEquals(60, f.box().y1());
    }

    @Test
    void aFencePostOrTwoTouchingTheBoxIsNotABuildGoingOn() {
        W w = new W();
        w.fill(10, 1, 10, 10, 1, 10, Blocks.OAK_FENCE);
        w.fill(11, 1, 10, 11, 1, 10, Blocks.OAK_FENCE);
        assertFalse(BoxCheck.check(w, SelectionBox.of(9, 1, 9, 10, 3, 11), null).any(), "two cells are under the minimum");
        // fit leaves such a box alone
        assertEquals(0, BoxCheck.fit(w, SelectionBox.of(9, 1, 9, 10, 3, 11), null, 96, 8_000_000L).layers());
    }

    /** A world that only knows the chunks it holds, and is asked in block coordinates like the game's: loaded(x, z) is "is the chunk of block x, z there". */
    private static final class Chunked extends W {
        final java.util.Set<Long> chunks = new java.util.HashSet<>();

        @Override
        public boolean loaded(int x, int z) {
            return chunks.contains(net.minecraft.world.level.ChunkPos.pack(x >> 4, z >> 4));
        }

        void hold(int x0, int z0, int x1, int z1) {
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) chunks.add(net.minecraft.world.level.ChunkPos.pack(cx, cz));
        }
    }

    @Test
    void itWorksFarFromTheOriginWhereOnlySomeChunksAreLoaded() {
        Chunked w = new Chunked();
        w.fill(5000, 1, -7000, 5002, 20, -6998, Blocks.OAK_PLANKS);
        w.hold(4990, -7010, 5010, -6990);
        BoxCheck.Result r = BoxCheck.check(w, SelectionBox.of(4999, 1, -7001, 5003, 10, -6997), null);
        assertTrue(r.cut(Direction.UP), "cut on top, far from the origin");
        assertEquals(9, r.count[Direction.UP.ordinal()]);
        assertEquals(10, BoxCheck.fit(w, SelectionBox.of(4999, 1, -7001, 5003, 10, -6997), null, 96, 8_000_000L).layers());
        // chunks that are not loaded count as nothing, never as a build
        Chunked empty = new Chunked();
        empty.fill(5000, 1, -7000, 5002, 20, -6998, Blocks.OAK_PLANKS);
        assertFalse(BoxCheck.check(empty, SelectionBox.of(4999, 1, -7001, 5003, 10, -6997), null).any());
    }

    @Test
    void oneFencePostTouchingAWholeWallOfTheBoxIsStillNotABuildGoingOn() {
        W w = new W();
        w.fill(10, 1, 10, 10, 4, 14, Blocks.OAK_PLANKS);
        w.fill(11, 1, 12, 11, 1, 12, Blocks.OAK_FENCE);
        // the post touches several wall blocks (straight and slanting), but it is one block
        assertFalse(BoxCheck.check(w, SelectionBox.of(8, 1, 9, 10, 4, 15), null).any());
        assertEquals(0, BoxCheck.fit(w, SelectionBox.of(8, 1, 9, 10, 4, 15), null, 96, 8_000_000L).layers());
    }

    /** A roof of {@code block} climbing one block up for every block east, 5 wide (z 10..14), from x 10 to x 25. */
    private static W slope(Block block) {
        W w = new W();
        for (int i = 0; i < 16; i++) w.fill(10 + i, 1 + i, 10, 10 + i, 1 + i, 14, block);
        return w;
    }

    @Test
    void aStairRoofCutByTheBoxIsFoundEvenThoughTheNextStepIsDiagonal() {
        W w = slope(Blocks.OAK_STAIRS);
        BoxCheck.Result r = BoxCheck.check(w, SelectionBox.of(9, 1, 9, 13, 20, 15), null);
        assertTrue(r.cut(Direction.EAST), "the stairs go on to the east");
        assertEquals(5, r.count[Direction.EAST.ordinal()]);
        BoxCheck.Fit f = BoxCheck.fit(w, SelectionBox.of(9, 1, 9, 13, 20, 15), null, 96, 8_000_000L);
        assertEquals(25, f.box().x1());
        assertFalse(BoxCheck.check(w, f.box(), null).any());
    }

    @Test
    void aSlabRoofIsTheSame() {
        W w = slope(Blocks.OAK_SLAB);
        BoxCheck.Result r = BoxCheck.check(w, SelectionBox.of(9, 1, 9, 13, 20, 15), null);
        assertTrue(r.cut(Direction.EAST));
        // and cut from the top, where the slope climbs out of the box
        assertTrue(BoxCheck.check(w, SelectionBox.of(9, 1, 9, 25, 8, 15), null).cut(Direction.UP));
    }

    @Test
    void anOverBigBoxIsNotChecked() {
        BoxCheck.Result r = BoxCheck.check(tower(), SelectionBox.of(0, 0, 0, 900, 200, 900), null);
        assertTrue(r.skipped);
        assertFalse(r.any());
    }
}
