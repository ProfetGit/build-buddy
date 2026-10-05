package io.github.profetgit.cyanotype.blueprint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Turns a box of the world into a {@link Blueprint} (PRD 7.7). The world is read through {@link Source}, so the game's
 * level and a test's fake are the same to it, and the work is a {@link Job} that can be stepped for a few milliseconds
 * a tick: a big box then costs no hitch on the client thread.
 */
public final class Capture {
    /** The most cells one save may read; a bigger box asks for a smaller one. */
    public static final long MAX_VOLUME = 8_000_000L;

    private Capture() {
    }

    /** What the capture reads: blocks, whether a column is loaded, and a block entity's saved data. */
    public interface Source {
        BlockState state(int x, int y, int z);

        /** Whether the chunk holding this column is loaded; cells of unloaded chunks cannot be read and count as air. */
        boolean loaded(int x, int z);

        /** The block entity at a position as a tag with its id (no coordinates needed), or null. */
        @Nullable CompoundTag blockEntity(int x, int y, int z);
    }

    /**
     * @param trim      cut the empty space off the box, so the blueprint is as small as the build
     * @param blockData keep the data blocks carry (sign text, banner patterns, head owners); container contents are never kept
     */
    /** Which cells of the box belong to the capture; the others are saved as air (Smart Pick keeps a build and leaves what stands around it). */
    public interface Mask {
        boolean keep(int x, int y, int z);
    }

    public record Options(boolean trim, boolean blockData) {
        public static final Options DEFAULT = new Options(true, true);
    }

    public static long volume(int x0, int y0, int z0, int x1, int y1, int z1) {
        return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
    }

    public static final class Job {
        private final Source source;
        private final int x0, y0, z0, sx, sy, sz;
        private final Options options;
        private final Blueprint.Metadata meta;
        private final @Nullable Mask mask;
        private final short[] cells;
        private final List<PaletteEntry> palette = new ArrayList<>();
        private final Map<BlockState, Integer> index = new HashMap<>();
        private final List<CompoundTag> blockEntities = new ArrayList<>();
        private long next;
        private long unloaded;
        private Blueprint result;
        private String failure;
        private int trimX, trimY, trimZ;

        /** The box is given by two opposite corners, inclusive, in any order. */
        public Job(Source source, int ax, int ay, int az, int bx, int by, int bz, Options options, Blueprint.Metadata meta) {
            this(source, ax, ay, az, bx, by, bz, options, meta, null);
        }

        /** As above, but only the cells the mask keeps are read; the rest are air. */
        public Job(Source source, int ax, int ay, int az, int bx, int by, int bz, Options options, Blueprint.Metadata meta, @Nullable Mask mask) {
            this.source = source;
            this.mask = mask;
            this.x0 = Math.min(ax, bx);
            this.y0 = Math.min(ay, by);
            this.z0 = Math.min(az, bz);
            this.sx = Math.abs(ax - bx) + 1;
            this.sy = Math.abs(ay - by) + 1;
            this.sz = Math.abs(az - bz) + 1;
            this.options = options;
            this.meta = meta;
            if ((long) sx * sy * sz > MAX_VOLUME) throw new IllegalArgumentException("too big");
            this.cells = new short[sx * sy * sz];
            palette.add(PaletteEntry.AIR);
        }

        public boolean done() {
            return result != null || failure != null;
        }

        public double progress() {
            return done() ? 1 : cells.length == 0 ? 1 : (double) next / cells.length;
        }

        /** Cells in chunks that were not loaded: read as air, so the save is missing them. */
        public long unloadedCells() {
            return unloaded;
        }

        /** Why nothing was made, in words for the player; null while running or after success. */
        public @Nullable String failure() {
            return failure;
        }

        public @Nullable Blueprint result() {
            return result;
        }

        /** Reads cells for about this long. @return true once everything is read and the result (or failure) is ready */
        public boolean step(long budgetNs) {
            if (done()) return true;
            long end = System.nanoTime() + budgetNs;
            int plane = sx * sz;
            while (next < cells.length) {
                int y = (int) (next / plane), rem = (int) (next % plane), z = rem / sx, x = rem % sx;
                int wx = x0 + x, wy = y0 + y, wz = z0 + z;
                // a row is read in one go; the clock is looked at between rows
                int rowEnd = sx;
                boolean loaded = true;
                int chunkX = Integer.MIN_VALUE;
                for (; x < rowEnd; x++, wx++) {
                    if (mask != null && !mask.keep(wx, wy, wz)) {
                        next++;
                        continue;
                    }
                    if ((wx >> 4) != chunkX) {
                        chunkX = wx >> 4;
                        loaded = source.loaded(wx, wz);
                    }
                    if (!loaded) {
                        unloaded++;
                        next++;
                        continue;
                    }
                    BlockState state = source.state(wx, wy, wz);
                    int slot;
                    if (state.isAir()) {
                        slot = 0;
                    } else {
                        Integer known = index.get(state);
                        if (known == null) {
                            known = palette.size();
                            if (known > 0xFFFF) {
                                failure = "This box holds more different blocks than a blueprint can list.";
                                return true;
                            }
                            palette.add(PaletteEntry.of(state));
                            index.put(state, known);
                        }
                        slot = known;
                        if (options.blockData() && state.hasBlockEntity()) {
                            CompoundTag tag = source.blockEntity(wx, wy, wz);
                            if (tag != null) {
                                tag.remove("Items");
                                tag.putInt("x", x);
                                tag.putInt("y", y);
                                tag.putInt("z", z);
                                blockEntities.add(tag);
                            }
                        }
                    }
                    cells[(int) next++] = (short) slot;
                }
                if (System.nanoTime() >= end && next < cells.length) return false;
            }
            finish();
            return true;
        }

        private void finish() {
            boolean[] air = new boolean[palette.size()];
            for (int i = 0; i < air.length; i++) air[i] = palette.get(i).isAir();
            int lx = sx, ly = sy, lz = sz, hx = -1, hy = -1, hz = -1;
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        if (air[cells[(y * sz + z) * sx + x] & 0xFFFF]) continue;
                        if (x < lx) lx = x;
                        if (y < ly) ly = y;
                        if (z < lz) lz = z;
                        if (x > hx) hx = x;
                        if (y > hy) hy = y;
                        if (z > hz) hz = z;
                    }
                }
            }
            if (hx < 0) {
                failure = unloaded > 0 ? "Nothing could be read here: the chunks are not loaded. Walk closer and try again." : "There is nothing but air in this box.";
                return;
            }
            if (!options.trim()) {
                lx = ly = lz = 0;
                hx = sx - 1;
                hy = sy - 1;
                hz = sz - 1;
            }
            trimX = lx;
            trimY = ly;
            trimZ = lz;
            int nx = hx - lx + 1, ny = hy - ly + 1, nz = hz - lz + 1;
            short[] out = new short[nx * ny * nz];
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    System.arraycopy(cells, ((y + ly) * sz + (z + lz)) * sx + lx, out, (y * nz + z) * nx, nx);
                }
            }
            List<CompoundTag> tes = new ArrayList<>();
            for (CompoundTag te : blockEntities) {
                int x = te.getIntOr("x", 0) - lx, y = te.getIntOr("y", 0) - ly, z = te.getIntOr("z", 0) - lz;
                if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) continue;
                te.putInt("x", x);
                te.putInt("y", y);
                te.putInt("z", z);
                tes.add(te);
            }
            String region = meta.name().isBlank() ? "main" : meta.name();
            Region r = new Region(region, 0, 0, 0, nx, ny, nz, palette.toArray(new PaletteEntry[0]), out, tes);
            result = new Blueprint(meta, List.of(r));
        }

        /** Where the result sits in the world: the min corner of what was kept (after trimming), as x, y, z. */
        public int[] origin() {
            return new int[]{x0 + trimX, y0 + trimY, z0 + trimZ};
        }
    }
}
