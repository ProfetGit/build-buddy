package io.github.profetgit.buildbuddy.blueprint;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;

/**
 * Writes .litematic (format version 6, the one Litematica has written longest), stamped with the running game's data
 * version and block-state spelling. Each region's palette is rebuilt with air first, as Litematica writes it.
 * Entities are not written (v1 has none).
 */
public final class LitematicWriter {
    public static final int VERSION = 6;
    public static final int SUB_VERSION = 1;

    private LitematicWriter() {
    }

    public static void write(Blueprint bp, Path file) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            write(bp, out);
        }
    }

    public static void write(Blueprint bp, OutputStream out) throws IOException {
        NbtIo.writeCompressed(toTag(bp), out);
    }

    public static CompoundTag toTag(Blueprint bp) {
        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putInt("Version", VERSION);
        root.putInt("SubVersion", SUB_VERSION);

        CompoundTag meta = new CompoundTag();
        meta.putString("Name", bp.meta.name());
        meta.putString("Author", bp.meta.author());
        meta.putString("Description", bp.meta.description());
        meta.putInt("RegionCount", bp.regions.size());
        meta.putLong("TimeCreated", bp.meta.timeCreated());
        meta.putLong("TimeModified", bp.meta.timeModified());
        meta.put("EnclosingSize", vec(bp.sizeX, bp.sizeY, bp.sizeZ));
        meta.putInt("TotalVolume", (int) bp.volume());
        meta.putInt("TotalBlocks", (int) bp.totalBlocks());
        root.put("Metadata", meta);

        CompoundTag regions = new CompoundTag();
        for (Region r : bp.regions) regions.put(r.name, regionTag(r));
        root.put("Regions", regions);
        return root;
    }

    private static CompoundTag regionTag(Region r) {
        List<PaletteEntry> palette = new ArrayList<>();
        Map<Integer, Integer> remap = new HashMap<>();
        palette.add(PaletteEntry.AIR);
        for (int i = 0; i < r.palette.length; i++) {
            if (r.palette[i].isAir() && r.palette[i].state().is(Blocks.AIR)) {
                remap.put(i, 0);
            }
        }
        short[] out = new short[r.blocks.length];
        for (int i = 0; i < r.blocks.length; i++) {
            int old = r.blocks[i] & 0xFFFF;
            Integer mapped = remap.get(old);
            if (mapped == null) {
                mapped = palette.size();
                palette.add(r.palette[old]);
                remap.put(old, mapped);
            }
            out[i] = (short) (int) mapped;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Position", vec(r.x, r.y, r.z));
        tag.put("Size", vec(r.sx, r.sy, r.sz));
        ListTag paletteTag = new ListTag();
        for (PaletteEntry e : palette) paletteTag.add(e.toTag());
        tag.put("BlockStatePalette", paletteTag);
        tag.putLongArray("BlockStates", BitPacking.pack(out, BitPacking.bitsFor(palette.size())));
        ListTag tes = new ListTag();
        for (CompoundTag te : r.blockEntities) tes.add(te.copy());
        tag.put("TileEntities", tes);
        tag.put("Entities", new ListTag());
        tag.put("PendingBlockTicks", new ListTag());
        tag.put("PendingFluidTicks", new ListTag());
        return tag;
    }

    private static CompoundTag vec(int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        return t;
    }
}
