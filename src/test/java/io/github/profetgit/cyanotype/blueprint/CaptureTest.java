package io.github.profetgit.cyanotype.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.TestBootstrap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CaptureTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    /** A world of a few blocks in a map; everything else is air, and chunk columns can be declared unloaded. */
    private static final class Fake implements Capture.Source {
        final Map<Long, BlockState> blocks = new HashMap<>();
        final Map<Long, CompoundTag> tiles = new HashMap<>();
        int unloadedChunkX = Integer.MIN_VALUE;

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean loaded(int x, int z) {
            return (x >> 4) != unloadedChunkX;
        }

        @Override
        public @Nullable CompoundTag blockEntity(int x, int y, int z) {
            CompoundTag t = tiles.get(BlockPos.asLong(x, y, z));
            return t == null ? null : t.copy();
        }
    }

    private static Blueprint run(Fake w, int ax, int ay, int az, int bx, int by, int bz, Capture.Options o) {
        Capture.Job job = new Capture.Job(w, ax, ay, az, bx, by, bz, o, Blueprint.Metadata.of("test"));
        while (!job.step(1_000_000)) {
            // read in slices
        }
        return job.result();
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    @Test
    void trimsTheEmptySpaceAndKeepsTheShape() {
        Fake w = new Fake();
        w.set(12, 5, -3, stone());
        w.set(14, 7, -3, Blocks.OAK_PLANKS.defaultBlockState());
        Capture.Job job = new Capture.Job(w, 10, 0, -10, 20, 10, 0, Capture.Options.DEFAULT, Blueprint.Metadata.of("t"));
        while (!job.step(1_000_000)) {
            // slices
        }
        Blueprint bp = job.result();
        assertNotNull(bp);
        assertEquals(3, bp.sizeX);
        assertEquals(3, bp.sizeY);
        assertEquals(1, bp.sizeZ);
        assertEquals(2, bp.totalBlocks());
        assertEquals(12, job.origin()[0]);
        assertEquals(5, job.origin()[1]);
        assertEquals(-3, job.origin()[2]);
        Region r = bp.regions.get(0);
        assertEquals(Blocks.STONE, r.at(0, 0, 0).state().getBlock());
        assertEquals(Blocks.OAK_PLANKS, r.at(2, 2, 0).state().getBlock());
        assertTrue(r.at(1, 1, 0).isAir());
    }

    @Test
    void withoutTrimTheWholeBoxIsKept() {
        Fake w = new Fake();
        w.set(1, 1, 1, stone());
        Blueprint bp = run(w, 0, 0, 0, 3, 2, 1, new Capture.Options(false, true));
        assertEquals(4, bp.sizeX);
        assertEquals(3, bp.sizeY);
        assertEquals(2, bp.sizeZ);
        assertEquals(1, bp.totalBlocks());
    }

    @Test
    void cornersGivenInAnyOrderMakeTheSameBox() {
        Fake w = new Fake();
        w.set(2, 2, 2, stone());
        w.set(0, 0, 0, stone());
        Blueprint a = run(w, 0, 0, 0, 2, 2, 2, new Capture.Options(false, true));
        Blueprint b = run(w, 2, 2, 2, 0, 0, 0, new Capture.Options(false, true));
        assertEquals(a.regions.get(0).nonAir(), b.regions.get(0).nonAir());
        assertEquals(a.sizeX, b.sizeX);
        assertEquals(Blocks.STONE, b.regions.get(0).at(2, 2, 2).state().getBlock());
    }

    @Test
    void sameStateSharesOnePaletteSlotAndAirIsOneSlot() {
        Fake w = new Fake();
        for (int i = 0; i < 6; i++) w.set(i, 0, 0, stone());
        w.set(3, 0, 0, Blocks.CAVE_AIR.defaultBlockState());
        Region r = run(w, 0, 0, 0, 5, 0, 0, new Capture.Options(false, true)).regions.get(0);
        // air, stone
        assertEquals(2, r.palette.length);
        assertTrue(r.at(3, 0, 0).isAir());
    }

    @Test
    void anEmptyBoxSaysSoAndAnUnloadedOneSaysWhy() {
        Fake w = new Fake();
        Capture.Job empty = new Capture.Job(w, 0, 0, 0, 4, 4, 4, Capture.Options.DEFAULT, Blueprint.Metadata.of("t"));
        assertTrue(empty.step(10_000_000));
        assertNull(empty.result());
        assertTrue(empty.failure().contains("air"));

        w.unloadedChunkX = 0;
        Capture.Job far = new Capture.Job(w, 0, 0, 0, 4, 4, 4, Capture.Options.DEFAULT, Blueprint.Metadata.of("t"));
        assertTrue(far.step(10_000_000));
        assertNull(far.result());
        assertTrue(far.failure().contains("loaded"));
        assertEquals(125, far.unloadedCells());
    }

    @Test
    void cellsInUnloadedChunksAreCountedAndTheRestStillSaved() {
        Fake w = new Fake();
        w.unloadedChunkX = 1;
        w.set(15, 0, 0, stone());
        w.set(16, 0, 0, stone());
        Capture.Job job = new Capture.Job(w, 14, 0, 0, 17, 0, 0, Capture.Options.DEFAULT, Blueprint.Metadata.of("t"));
        assertTrue(job.step(10_000_000));
        assertEquals(1, job.result().totalBlocks());
        assertEquals(2, job.unloadedCells());
    }

    @Test
    void slicingTheWorkDoesNotChangeTheResult() {
        Fake w = new Fake();
        for (int x = 0; x < 20; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 7; z++) if ((x + y * 3 + z) % 4 != 0) w.set(x, y, z, (x + z) % 3 == 0 ? stone() : Blocks.OAK_LOG.defaultBlockState());
        Capture.Job whole = new Capture.Job(w, 0, 0, 0, 19, 4, 6, Capture.Options.DEFAULT, Blueprint.Metadata.of("t"));
        assertTrue(whole.step(Long.MAX_VALUE / 2));
        Capture.Job sliced = new Capture.Job(w, 0, 0, 0, 19, 4, 6, Capture.Options.DEFAULT, Blueprint.Metadata.of("t"));
        int steps = 0;
        // a budget of one nanosecond does one row at a time
        while (!sliced.step(1)) steps++;
        assertTrue(steps > 10, "sliced in " + steps);
        Region a = whole.result().regions.get(0), b = sliced.result().regions.get(0);
        for (int i = 0; i < a.blocks.length; i++) assertEquals(a.palette[a.blocks[i]].state(), b.palette[b.blocks[i]].state());
    }

    @Test
    void blockEntitiesMoveWithTheTrimAndLoseTheirContents() {
        Fake w = new Fake();
        w.set(10, 4, 10, Blocks.OAK_SIGN.defaultBlockState());
        w.set(12, 4, 10, Blocks.CHEST.defaultBlockState());
        CompoundTag sign = new CompoundTag();
        sign.putString("id", "minecraft:sign");
        sign.putString("front_text", "hello");
        w.tiles.put(BlockPos.asLong(10, 4, 10), sign);
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.put("Items", new net.minecraft.nbt.ListTag());
        w.tiles.put(BlockPos.asLong(12, 4, 10), chest);
        Region r = run(w, 5, 0, 5, 20, 8, 20, Capture.Options.DEFAULT).regions.get(0);
        assertEquals(2, r.blockEntities.size());
        CompoundTag first = r.blockEntities.get(0);
        assertEquals("minecraft:sign", first.getStringOr("id", ""));
        assertEquals(0, first.getIntOr("x", -1));
        assertEquals(0, first.getIntOr("y", -1));
        assertEquals(0, first.getIntOr("z", -1));
        assertFalse(r.blockEntities.get(1).contains("Items"));
        assertEquals(2, r.blockEntities.get(1).getIntOr("x", -1));

        Region none = run(w, 5, 0, 5, 20, 8, 20, new Capture.Options(true, false)).regions.get(0);
        assertTrue(none.blockEntities.isEmpty());
    }

    @Test
    void aCaptureSurvivesWritingAndReadingTheFile() throws IOException {
        Fake w = new Fake();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 4; z++) w.set(x, 0, z, stone());
        w.set(2, 1, 1, Blocks.OAK_STAIRS.defaultBlockState());
        w.set(0, 2, 0, Blocks.GLASS.defaultBlockState());
        Blueprint bp = run(w, -2, -1, -2, 8, 6, 8, Capture.Options.DEFAULT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LitematicWriter.write(bp, out);
        Blueprint back = LitematicReader.read(new ByteArrayInputStream(out.toByteArray()), "back", false);
        assertEquals(bp.sizeX, back.sizeX);
        assertEquals(bp.sizeY, back.sizeY);
        assertEquals(bp.sizeZ, back.sizeZ);
        assertEquals(bp.totalBlocks(), back.totalBlocks());
        Region a = bp.regions.get(0), b = back.regions.get(0);
        for (int y = 0; y < a.sy; y++) for (int z = 0; z < a.sz; z++) for (int x = 0; x < a.sx; x++) assertEquals(a.at(x, y, z).state(), b.at(x, y, z).state());
    }

    @Test
    void aBoxOverTheLimitIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Capture.Job(new Fake(), 0, 0, 0, 1000, 1000, 1000, Capture.Options.DEFAULT, Blueprint.Metadata.of("t")));
    }
}
