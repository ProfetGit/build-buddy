package io.github.profetgit.buildbuddy.blueprint;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * Takes single blocks out of a captured blueprint (the Save preview's remove mode): the blueprint stays as it was read, and the
 * removed blocks are given as world positions, so they stay valid when the box is read again with other switches. The blocks
 * become air and their block entity data goes with them; sizes and the palette stay (an air entry is added when there was none).
 */
public final class CellEdits {
    private CellEdits() {
    }

    /**
     * @param bp      a blueprint with one region, as {@link Capture} makes
     * @param ox      the world position of the region's min corner
     * @param removed world positions ({@link BlockPos#asLong}) to make air; those outside the region are ignored
     * @return the blueprint itself when nothing in it is removed, else a copy
     */
    public static Blueprint without(Blueprint bp, int ox, int oy, int oz, LongSet removed) {
        if (removed.isEmpty() || bp.regions.isEmpty()) return bp;
        Region r = bp.regions.get(0);
        int air = -1;
        for (int i = 0; i < r.palette.length; i++) if (r.palette[i].isAir()) {
            air = i;
            break;
        }
        PaletteEntry[] palette = r.palette;
        if (air < 0) {
            palette = Arrays.copyOf(r.palette, r.palette.length + 1);
            palette[palette.length - 1] = PaletteEntry.AIR;
            air = palette.length - 1;
        }
        short[] blocks = r.blocks.clone();
        List<CompoundTag> entities = new ArrayList<>(r.blockEntities);
        boolean any = false;
        for (long pos : removed) {
            int x = BlockPos.getX(pos) - ox, y = BlockPos.getY(pos) - oy, z = BlockPos.getZ(pos) - oz;
            if (x < 0 || y < 0 || z < 0 || x >= r.sx || y >= r.sy || z >= r.sz) continue;
            blocks[(y * r.sz + z) * r.sx + x] = (short) air;
            any = true;
            final int fx = x, fy = y, fz = z;
            entities.removeIf(te -> te.getIntOr("x", -1) == fx && te.getIntOr("y", -1) == fy && te.getIntOr("z", -1) == fz);
        }
        if (!any) return bp;
        Region copy = new Region(r.name, r.x, r.y, r.z, r.sx, r.sy, r.sz, palette, blocks, entities);
        return new Blueprint(bp.meta, List.of(copy));
    }
}
