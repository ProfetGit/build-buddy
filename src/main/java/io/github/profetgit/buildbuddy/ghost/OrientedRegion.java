package io.github.profetgit.buildbuddy.ghost;

import io.github.profetgit.buildbuddy.blueprint.Blueprint;
import io.github.profetgit.buildbuddy.blueprint.PaletteEntry;
import io.github.profetgit.buildbuddy.blueprint.Region;
import io.github.profetgit.buildbuddy.placement.Orientation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A region after the placement's mirror and rotation: its block array permuted into the turned box, its palette states
 * turned with it. {@code ox,oy,oz} is the region's min corner in placement space (the turned enclosing box starts at 0).
 */
public final class OrientedRegion {
    public final Region source;
    public final int ox, oy, oz;
    public final int sx, sy, sz;
    public final short[] blocks;
    public final BlockState[] states;
    /** Per palette slot: a block the game does not know (it stands in as a red block and can never be "correct"). */
    public final boolean[] unknown;
    private final Orientation orientation;
    private final int ex, ez, rx, rz;

    private OrientedRegion(Region source, int ox, int oy, int oz, int sx, int sy, int sz, short[] blocks, BlockState[] states, boolean[] unknown,
                           Orientation orientation, int ex, int ez, int rx, int rz) {
        this.orientation = orientation;
        this.ex = ex;
        this.ez = ez;
        this.rx = rx;
        this.rz = rz;
        this.source = source;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.blocks = blocks;
        this.states = states;
        this.unknown = unknown;
    }

    public static OrientedRegion of(Blueprint bp, Region r, Orientation o) {
        int ex = bp.sizeX, ez = bp.sizeZ;
        int rx = r.x - bp.minX, ry = r.y - bp.minY, rz = r.z - bp.minZ;
        int osx = o.sizeX(r.sx, r.sz), osz = o.sizeZ(r.sx, r.sz);
        int ax = o.mapX(rx, rz, ex, ez), bx = o.mapX(rx + r.sx - 1, rz + r.sz - 1, ex, ez);
        int az = o.mapZ(rx, rz, ex, ez), bz = o.mapZ(rx + r.sx - 1, rz + r.sz - 1, ex, ez);
        int ox = Math.min(ax, bx), oz = Math.min(az, bz);

        BlockState[] states = new BlockState[r.palette.length];
        boolean[] unknown = new boolean[r.palette.length];
        for (int i = 0; i < states.length; i++) {
            PaletteEntry e = r.palette[i];
            states[i] = e.isAir() ? Blocks.AIR.defaultBlockState() : o.state(e.state());
            unknown[i] = e.unknown();
        }
        short[] blocks = new short[r.blocks.length];
        for (int y = 0; y < r.sy; y++) {
            for (int z = 0; z < r.sz; z++) {
                for (int x = 0; x < r.sx; x++) {
                    int cx = o.mapX(rx + x, rz + z, ex, ez) - ox;
                    int cz = o.mapZ(rx + x, rz + z, ex, ez) - oz;
                    blocks[(y * osz + cz) * osx + cx] = r.blocks[(y * r.sz + z) * r.sx + x];
                }
            }
        }
        return new OrientedRegion(r, ox, ry, oz, osx, r.sy, osz, blocks, states, unknown, o, ex, ez, rx, rz);
    }

    /**
     * Where a cell of the source region (the position a block entity of the region is stored at) is in this turned box, as an index
     * into {@link #blocks}, or -1 when it is outside the region.
     */
    public int indexOfSource(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= source.sx || y >= source.sy || z >= source.sz) return -1;
        int cx = orientation.mapX(rx + x, rz + z, ex, ez) - ox;
        int cz = orientation.mapZ(rx + x, rz + z, ex, ez) - oz;
        if (cx < 0 || cz < 0 || cx >= sx || cz >= sz) return -1;
        return (y * sz + cz) * sx + cx;
    }

    /** The palette slot at a local position, or -1 outside the box. */
    public int paletteIndex(int lx, int ly, int lz) {
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return -1;
        return blocks[(ly * sz + lz) * sx + lx] & 0xFFFF;
    }

    /** The state at a position local to this region's turned box; air outside it. */
    public BlockState state(int lx, int ly, int lz) {
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return Blocks.AIR.defaultBlockState();
        return states[blocks[(ly * sz + lz) * sx + lx] & 0xFFFF];
    }
}
