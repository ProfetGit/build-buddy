package io.github.profetgit.cyanotype.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.TestBootstrap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CellEditsTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static Blueprint box(boolean withAirEntry) {
        List<PaletteEntry> palette = new ArrayList<>();
        if (withAirEntry) palette.add(PaletteEntry.AIR);
        palette.add(PaletteEntry.of(Blocks.STONE.defaultBlockState()));
        int stone = palette.size() - 1;
        short[] blocks = new short[3 * 2 * 3];
        java.util.Arrays.fill(blocks, (short) stone);
        List<CompoundTag> te = new ArrayList<>();
        CompoundTag sign = new CompoundTag();
        sign.putInt("x", 1);
        sign.putInt("y", 0);
        sign.putInt("z", 1);
        te.add(sign);
        return new Blueprint(Blueprint.Metadata.of("t"), List.of(new Region("main", 0, 0, 0, 3, 2, 3, palette.toArray(new PaletteEntry[0]), blocks, te)));
    }

    @Test
    void aBlockBecomesAirAndTheRestStays() {
        Blueprint bp = box(true);
        LongOpenHashSet gone = new LongOpenHashSet();
        gone.add(BlockPos.asLong(100 + 1, 64 + 1, -5 + 2));
        Blueprint out = CellEdits.without(bp, 100, 64, -5, gone);
        assertEquals(bp.totalBlocks() - 1, out.totalBlocks());
        Region r = out.regions.get(0);
        assertTrue(r.palette[r.blocks[(1 * 3 + 2) * 3 + 1] & 0xFFFF].isAir());
        assertEquals(bp.totalBlocks(), bp.totalBlocks(), "the original is untouched");
        assertEquals(17, out.totalBlocks());
    }

    @Test
    void anAirEntryIsAddedWhenThePaletteHadNone() {
        Blueprint out = CellEdits.without(box(false), 0, 0, 0, new LongOpenHashSet(new long[]{BlockPos.asLong(0, 0, 0)}));
        assertEquals(17, out.totalBlocks());
        assertEquals(2, out.regions.get(0).palette.length);
    }

    @Test
    void theBlockEntityOfARemovedBlockGoesWithIt() {
        Blueprint bp = box(true);
        assertEquals(1, bp.regions.get(0).blockEntities.size());
        Blueprint out = CellEdits.without(bp, 0, 0, 0, new LongOpenHashSet(new long[]{BlockPos.asLong(1, 0, 1)}));
        assertEquals(0, out.regions.get(0).blockEntities.size());
        assertEquals(1, bp.regions.get(0).blockEntities.size(), "the original keeps it");
    }

    @Test
    void nothingToRemoveOrOutsideTheBoxGivesTheSameBlueprint() {
        Blueprint bp = box(true);
        assertSame(bp, CellEdits.without(bp, 0, 0, 0, new LongOpenHashSet()));
        assertSame(bp, CellEdits.without(bp, 0, 0, 0, new LongOpenHashSet(new long[]{BlockPos.asLong(50, 50, 50)})));
    }
}
