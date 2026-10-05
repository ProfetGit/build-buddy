package io.github.profetgit.cyanotype.blueprint;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * A box of blocks inside a blueprint. {@code x,y,z} is the min corner in blueprint space; blocks are palette indices,
 * x fastest, then z, then y: {@code index = (y * sz + z) * sx + x}.
 */
public final class Region {
    public final String name;
    public final int x, y, z;
    public final int sx, sy, sz;
    public final PaletteEntry[] palette;
    public final short[] blocks;
    /** Block entity data, each tag with local int x, y, z beside the vanilla fields. Kept as read; not interpreted yet. */
    public final List<CompoundTag> blockEntities;

    public Region(String name, int x, int y, int z, int sx, int sy, int sz, PaletteEntry[] palette, short[] blocks, List<CompoundTag> blockEntities) {
        if ((long) sx * sy * sz != blocks.length) throw new IllegalArgumentException("size " + sx + "x" + sy + "x" + sz + " does not match " + blocks.length + " blocks");
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.palette = palette;
        this.blocks = blocks;
        this.blockEntities = blockEntities;
    }

    public int volume() {
        return blocks.length;
    }

    public int index(int lx, int ly, int lz) {
        return (ly * sz + lz) * sx + lx;
    }

    public boolean contains(int lx, int ly, int lz) {
        return lx >= 0 && ly >= 0 && lz >= 0 && lx < sx && ly < sy && lz < sz;
    }

    /** The palette slot at a local position; air outside the region. */
    public PaletteEntry at(int lx, int ly, int lz) {
        return contains(lx, ly, lz) ? palette[blocks[index(lx, ly, lz)] & 0xFFFF] : PaletteEntry.AIR;
    }

    public int nonAir() {
        boolean[] air = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) air[i] = palette[i].isAir();
        int n = 0;
        for (short b : blocks) if (!air[b & 0xFFFF]) n++;
        return n;
    }
}
