package io.github.profetgit.cyanotype.verify;

import io.github.profetgit.cyanotype.ghost.OrientedRegion;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Compares the world with a placed blueprint, block by block, and keeps the answer up to date at a cost that follows
 * the edits, not the size of the build. Every cell of the placement has a state: not part of the build (air in the
 * blueprint), correct, missing, wrong, unknown (a block this game version does not have) or unloaded (its chunk is not
 * here, so nothing can be said). Cells are checked section by section at first, nearest the player first, within a time
 * budget per call; after that only the cells the world reports as changed are rechecked, ahead of anything else, so a
 * block placed shows up at once even while a huge build is still being scanned.
 *
 * <p>All of it runs on one thread (the client's); a section's {@linkplain #version version} goes up whenever any of its
 * cells change, which is how the ghost knows to bake it again.
 */
public final class Verifier {
    public static final byte NONE = 0, CORRECT = 1, MISSING = 2, WRONG = 3, UNKNOWN = 4, UNLOADED = 5;
    public static final long NO_TARGET = Long.MIN_VALUE;
    static final int SEC = 16;
    private static final int STATES = 6;
    /** How many dirty cells are rechecked per slice of work before the clock is looked at again. */
    private static final int DIRTY_BATCH = 256;

    /** One turned region of the blueprint and what is known about each of its cells. */
    public static final class Part {
        public final OrientedRegion region;
        /** World position of the region's min corner. */
        public final int wx, wy, wz;
        public final byte[] status;
        public final int nx, ny, nz;
        final int[] version;
        final boolean[] checked, queued, hasUnloaded;
        /** Cells per palette slot and state: {@code [slot * 6 + state]}. */
        final int[] paletteCounts;
        final boolean[] air;

        Part(OrientedRegion r, int wx, int wy, int wz) {
            this.region = r;
            this.wx = wx;
            this.wy = wy;
            this.wz = wz;
            this.status = new byte[r.blocks.length];
            this.nx = (r.sx + SEC - 1) / SEC;
            this.ny = (r.sy + SEC - 1) / SEC;
            this.nz = (r.sz + SEC - 1) / SEC;
            int n = nx * ny * nz;
            this.version = new int[n];
            this.checked = new boolean[n];
            this.queued = new boolean[n];
            this.hasUnloaded = new boolean[n];
            this.paletteCounts = new int[r.states.length * STATES];
            this.air = new boolean[r.states.length];
            for (int i = 0; i < air.length; i++) air[i] = r.states[i].isAir();
        }

        public int sectionIndex(int lx, int ly, int lz) {
            return ((ly / SEC) * nz + (lz / SEC)) * nx + (lx / SEC);
        }

        public int sections() {
            return nx * ny * nz;
        }
    }

    public final List<Part> parts = new ArrayList<>();
    public final int originX, originY, originZ;
    /** Height of the whole placement in blocks. */
    public final int height;
    private final Matcher matcher;
    private final int[] counts = new int[STATES];
    private final int[][] layers;
    private final ArrayDeque<int[]> queue = new ArrayDeque<>();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final Long2BooleanOpenHashMap columns = new Long2BooleanOpenHashMap();
    private boolean enqueued;
    private int pollTick;
    private long changes;

    public Verifier(List<OrientedRegion> regions, int originX, int originY, int originZ, Matcher matcher) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.matcher = matcher;
        int h = 0;
        for (OrientedRegion r : regions) {
            parts.add(new Part(r, originX + r.ox, originY + r.oy, originZ + r.oz));
            h = Math.max(h, r.oy + r.sy);
        }
        this.height = h;
        this.layers = new int[h][STATES];
        for (Part p : parts) {
            int x0 = Math.floorDiv(p.wx, 16), x1 = Math.floorDiv(p.wx + p.region.sx - 1, 16);
            int z0 = Math.floorDiv(p.wz, 16), z1 = Math.floorDiv(p.wz + p.region.sz - 1, 16);
            for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) columns.put(ChunkPosKey.of(cx, cz), false);
        }
    }

    /** Packs a chunk column as a map key. */
    private static final class ChunkPosKey {
        static long of(int cx, int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }
    }

    // ---- reading

    public Counts counts() {
        return new Counts(counts[CORRECT], counts[MISSING], counts[WRONG], counts[UNKNOWN], counts[UNLOADED]);
    }

    /** Cells in a state in one layer (height above the placement's base). */
    public int layerCount(int layer, int state) {
        return layer < 0 || layer >= height ? 0 : layers[layer][state];
    }

    public int paletteCount(int part, int slot, int state) {
        return parts.get(part).paletteCounts[slot * STATES + state];
    }

    /**
     * Blocks still to place or fix per palette slot of a part, within a range of layers (counted up from the placement's
     * base; a negative end leaves that side open). With no limits it is read from the counters, otherwise the cells of the
     * layers are looked at.
     */
    public int[] todoBySlot(int part, int layerLo, int layerHi) {
        Part p = parts.get(part);
        int[] out = new int[p.region.states.length];
        if (layerLo < 0 && layerHi < 0) {
            for (int s = 0; s < out.length; s++) out[s] = p.paletteCounts[s * STATES + MISSING] + p.paletteCounts[s * STATES + WRONG];
            return out;
        }
        OrientedRegion r = p.region;
        int lo = Math.max(0, layerLo), hi = layerHi < 0 ? height - 1 : Math.min(height - 1, layerHi);
        int y0 = Math.max(0, lo - r.oy), y1 = Math.min(r.sy - 1, hi - r.oy);
        for (int y = y0; y <= y1; y++) {
            int base = y * r.sz * r.sx;
            for (int i = 0, n = r.sz * r.sx; i < n; i++) {
                byte st = p.status[base + i];
                if (st == MISSING || st == WRONG) out[r.blocks[base + i] & 0xFFFF]++;
            }
        }
        return out;
    }

    /** Bumps whenever anything about the verified cells changed; cheap to compare. */
    public long changes() {
        return changes;
    }

    public boolean ready(int part, int section) {
        return parts.get(part).checked[section];
    }

    public int version(int part, int section) {
        return parts.get(part).version[section];
    }

    /** True when every section has been looked at once and nothing is waiting. */
    public boolean settled() {
        return enqueued && queue.isEmpty() && dirty.isEmpty();
    }

    public byte statusAt(int x, int y, int z) {
        for (Part p : parts) {
            int lx = x - p.wx, ly = y - p.wy, lz = z - p.wz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= p.region.sx || ly >= p.region.sy || lz >= p.region.sz) continue;
            return p.status[(ly * p.region.sz + lz) * p.region.sx + lx];
        }
        return NONE;
    }

    /** The block the blueprint wants at a world position, or air outside the build. */
    public BlockState expectedAt(int x, int y, int z) {
        for (Part p : parts) {
            int lx = x - p.wx, ly = y - p.wy, lz = z - p.wz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= p.region.sx || ly >= p.region.sy || lz >= p.region.sz) continue;
            return p.region.state(lx, ly, lz);
        }
        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    /** Copies the states of a box of one part, laid out y, then z, then x, for a bake running on another thread. */
    public byte[] snapshot(int part, int x0, int y0, int z0, int w, int h, int d) {
        Part p = parts.get(part);
        byte[] out = new byte[w * h * d];
        int i = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int z = z0; z < z0 + d; z++) {
                System.arraycopy(p.status, (y * p.region.sz + z) * p.region.sx + x0, out, i, w);
                i += w;
            }
        }
        return out;
    }

    // ---- telling it about changes

    /** A block at this world position changed. Cheap: it only notes the position. */
    public void markDirty(long packedPos) {
        int x = BlockPos.getX(packedPos), y = BlockPos.getY(packedPos), z = BlockPos.getZ(packedPos);
        for (Part p : parts) {
            if (x >= p.wx && y >= p.wy && z >= p.wz && x < p.wx + p.region.sx && y < p.wy + p.region.sy && z < p.wz + p.region.sz) {
                dirty.add(packedPos);
                return;
            }
        }
    }

    /** A chunk column was loaded or unloaded: every section over it is looked at again. */
    public void markColumn(int cx, int cz) {
        for (int pi = 0; pi < parts.size(); pi++) {
            Part p = parts.get(pi);
            int x0 = Math.max(0, cx * 16 - p.wx), x1 = Math.min(p.region.sx - 1, cx * 16 + 15 - p.wx);
            int z0 = Math.max(0, cz * 16 - p.wz), z1 = Math.min(p.region.sz - 1, cz * 16 + 15 - p.wz);
            if (x0 > x1 || z0 > z1) continue;
            for (int sy = 0; sy < p.ny; sy++) {
                for (int sz = z0 / SEC; sz <= z1 / SEC; sz++) {
                    for (int sx = x0 / SEC; sx <= x1 / SEC; sx++) enqueue(pi, p, (sy * p.nz + sz) * p.nx + sx);
                }
            }
        }
    }

    private void enqueue(int pi, Part p, int sec) {
        if (p.queued[sec]) return;
        p.queued[sec] = true;
        queue.add(new int[]{pi, sec});
    }

    // ---- doing the work

    /**
     * Does some of the pending work, for at most about {@code budgetNanos}.
     *
     * @param near where the player is: the first scan goes outward from here
     * @return whether work is left
     */
    public boolean process(WorldView world, long budgetNanos, double nearX, double nearY, double nearZ) {
        long deadline = System.nanoTime() + budgetNanos;
        if (!enqueued) {
            enqueued = true;
            enqueueAll(nearX, nearY, nearZ);
        }
        if (++pollTick % 10 == 0) pollColumns(world);
        // block changes first: a block just placed must show at once, however big the scan still ahead is
        while (!dirty.isEmpty()) {
            LongArrayList batch = new LongArrayList(DIRTY_BATCH);
            var it = dirty.iterator();
            while (it.hasNext() && batch.size() < DIRTY_BATCH) {
                batch.add(it.nextLong());
                it.remove();
            }
            for (int i = 0; i < batch.size(); i++) recheck(world, batch.getLong(i));
            if (System.nanoTime() >= deadline) return true;
        }
        while (!queue.isEmpty()) {
            int[] job = queue.poll();
            Part p = parts.get(job[0]);
            p.queued[job[1]] = false;
            checkSection(world, p, job[1]);
            if (System.nanoTime() >= deadline) break;
        }
        return !queue.isEmpty() || !dirty.isEmpty();
    }

    private void enqueueAll(double nx, double ny, double nz) {
        List<int[]> all = new ArrayList<>();
        List<Double> dist = new ArrayList<>();
        for (int pi = 0; pi < parts.size(); pi++) {
            Part p = parts.get(pi);
            for (int sy = 0; sy < p.ny; sy++) {
                for (int sz = 0; sz < p.nz; sz++) {
                    for (int sx = 0; sx < p.nx; sx++) {
                        int sec = (sy * p.nz + sz) * p.nx + sx;
                        double dx = p.wx + sx * SEC + SEC / 2.0 - nx, dy = p.wy + sy * SEC + SEC / 2.0 - ny, dz = p.wz + sz * SEC + SEC / 2.0 - nz;
                        all.add(new int[]{pi, sec});
                        dist.add(dx * dx + dy * dy + dz * dz);
                    }
                }
            }
        }
        Integer[] order = new Integer[all.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingDouble(dist::get));
        for (int i : order) {
            int[] job = all.get(i);
            Part p = parts.get(job[0]);
            p.queued[job[1]] = true;
            queue.add(job);
        }
    }

    /** Notices chunks that came or went since the last look. */
    private void pollColumns(WorldView world) {
        var it = columns.long2BooleanEntrySet().iterator();
        List<long[]> changed = new ArrayList<>();
        while (it.hasNext()) {
            var e = it.next();
            long key = e.getLongKey();
            int cx = (int) (key >> 32), cz = (int) key;
            boolean now = world.loaded(cx, cz);
            if (now != e.getBooleanValue()) {
                e.setValue(now);
                changed.add(new long[]{cx, cz});
            }
        }
        for (long[] c : changed) markColumn((int) c[0], (int) c[1]);
    }

    private void checkSection(WorldView world, Part p, int sec) {
        OrientedRegion r = p.region;
        int sx = sec % p.nx, sz = (sec / p.nx) % p.nz, sy = sec / (p.nx * p.nz);
        int x0 = sx * SEC, y0 = sy * SEC, z0 = sz * SEC;
        int x1 = Math.min(r.sx, x0 + SEC), y1 = Math.min(r.sy, y0 + SEC), z1 = Math.min(r.sz, z0 + SEC);
        boolean unloaded = false;
        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                int row = (y * r.sz + z) * r.sx;
                for (int x = x0; x < x1; x++) {
                    if (cell(world, p, row + x, x, y, z)) unloaded = true;
                }
            }
        }
        p.hasUnloaded[sec] = unloaded;
        if (!p.checked[sec]) {
            p.checked[sec] = true;
            p.version[sec]++;
            changes++;
        }
    }

    private void recheck(WorldView world, long packed) {
        int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
        for (Part p : parts) {
            int lx = x - p.wx, ly = y - p.wy, lz = z - p.wz;
            OrientedRegion r = p.region;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= r.sx || ly >= r.sy || lz >= r.sz) continue;
            cell(world, p, (ly * r.sz + lz) * r.sx + lx, lx, ly, lz);
        }
    }

    /** Judges one cell and records it. @return whether it could not be judged because its chunk is not loaded */
    private boolean cell(WorldView world, Part p, int idx, int lx, int ly, int lz) {
        OrientedRegion r = p.region;
        int pal = r.blocks[idx] & 0xFFFF;
        byte now;
        boolean unloaded = false;
        if (p.air[pal]) {
            now = NONE;
        } else if (r.unknown[pal]) {
            now = UNKNOWN;
        } else {
            int wx = p.wx + lx, wy = p.wy + ly, wz = p.wz + lz;
            if (!world.loaded(wx >> 4, wz >> 4)) {
                now = UNLOADED;
                unloaded = true;
            } else {
                BlockState actual = world.get(wx, wy, wz);
                BlockState expected = r.states[pal];
                if (matcher.matches(expected, actual)) now = CORRECT;
                else if (actual.isAir() || actual.canBeReplaced()) now = MISSING;
                else now = WRONG;
            }
        }
        byte before = p.status[idx];
        if (before != now) {
            p.status[idx] = now;
            counts[before]--;
            counts[now]++;
            int layer = r.oy + ly;
            layers[layer][before]--;
            layers[layer][now]++;
            p.paletteCounts[pal * STATES + before]--;
            p.paletteCounts[pal * STATES + now]++;
            p.version[p.sectionIndex(lx, ly, lz)]++;
            changes++;
        }
        return unloaded;
    }

    // ---- where to build next

    /**
     * The nearest block still to do in the lowest layer that is not finished (building bottom-up), within a layer range.
     *
     * @param layerLo lowest layer allowed, or negative for the bottom
     * @param layerHi highest layer allowed, or negative for the top
     * @return the world position packed as a BlockPos, or {@link #NO_TARGET}
     */
    public long nextTarget(double px, double py, double pz, int layerLo, int layerHi) {
        int lo = Math.max(0, layerLo), hi = layerHi < 0 ? height - 1 : Math.min(height - 1, layerHi);
        int layer = -1;
        for (int l = lo; l <= hi; l++) {
            if (layers[l][MISSING] + layers[l][WRONG] > 0) {
                layer = l;
                break;
            }
        }
        if (layer < 0) return NO_TARGET;
        double best = Double.POSITIVE_INFINITY;
        long target = NO_TARGET;
        for (Part p : parts) {
            OrientedRegion r = p.region;
            int ly = layer - r.oy;
            if (ly < 0 || ly >= r.sy) continue;
            for (int z = 0; z < r.sz; z++) {
                int row = (ly * r.sz + z) * r.sx;
                for (int x = 0; x < r.sx; x++) {
                    byte st = p.status[row + x];
                    if (st != MISSING && st != WRONG) continue;
                    double dx = p.wx + x + 0.5 - px, dy = p.wy + ly + 0.5 - py, dz = p.wz + z + 0.5 - pz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < best) {
                        best = d;
                        target = BlockPos.asLong(p.wx + x, p.wy + ly, p.wz + z);
                    }
                }
            }
        }
        return target;
    }
}
