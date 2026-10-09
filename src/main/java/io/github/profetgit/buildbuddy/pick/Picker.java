package io.github.profetgit.buildbuddy.pick;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Smart Pick (PRD 7.11): from one block, finds the whole build it belongs to. Pure logic over a {@link Field}, so it is
 * tested on made-up worlds and runs in the game a few milliseconds a tick.
 *
 * <ol>
 *   <li><b>Flood.</b> From the seed, through every {@link Kind#BUILT} block within one block (all 26 neighbours) or across
 *   a gap of up to {@code reach} blocks in a straight line (a window frame, a hanging lantern, a chain). Terrain never
 *   joins, except a small separate lump of it (sod on a roof); a log joins unless leaves grew near it (a trunk).</li>
 *   <li><b>Water.</b> A pool inside the build joins; a pond that touches the ground does not.</li>
 *   <li><b>Parts.</b> What was reached can be several buildings tied together by fences. Cells that have plenty of
 *   neighbours (walls, floors, roofs) form bodies; thin stuff (fences, posts, chains) belongs to the one body it touches,
 *   and a thin link that touches two bodies is a part of its own. Clicking picks one part; the others are offered.</li>
 * </ol>
 */
public final class Picker {
    public static final int DEFAULT_LIMIT = 250_000, MAX_REACH = 3, DEFAULT_REACH = 1;
    /** A terrain lump of fewer cells than this, not touching anything bigger, is taken for building material. */
    static final int TERRAIN_MASS = 600;
    static final int FLUID_CAP = 1500;
    /** A cell with this many cells of the build in its 3 x 3 x 3 neighbourhood (itself included) is part of a body, not a thin link. */
    static final int DENSE = 7;
    /** A body smaller than this is not a building of its own. */
    static final int MIN_BODY = 12;

    /** The longest a single call into each phase has run (milliseconds, FLOOD..CLAIM): for tuning, shown by the demo. */
    private static final long[] WORST_NS = new long[7];

    public static String worstPhases() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(Phase.values()[i]).append(' ').append(String.format("%.1f", WORST_NS[i] / 1e6)).append("ms ");
        return sb.toString().trim();
    }

    public static void resetWorst() {
        java.util.Arrays.fill(WORST_NS, 0);
    }

    private Picker() {
    }

    /** What the picker may look at: the blocks, and whether the chunk is loaded. */
    public interface Field {
        BlockState state(int x, int y, int z);

        boolean loaded(int x, int z);
    }

    /** The outcome of a flood. Cells are {@link BlockPos#asLong} keys. */
    public static final class Result {
        public final long seed;
        public final int reach;
        /** Everything reached, in every part. */
        public final LongOpenHashSet cells;
        /** Cells that are probably not part of the build (terrain taken for material): shown in another tint. */
        public final LongOpenHashSet unsure;
        public final Long2IntOpenHashMap part;
        public final int[] partSize;
        private final LongArrayList[] memberLists;
        /** True for a part that is only a thin link between two bodies. */
        public final boolean[] partLink;
        public final int seedPart;
        public final boolean touchedUnloaded;
        public final int minX, minY, minZ, maxX, maxY, maxZ;

        Result(long seed, int reach, LongOpenHashSet cells, LongOpenHashSet unsure, Long2IntOpenHashMap part, int[] partSize, LongArrayList[] memberLists, boolean[] partLink, boolean touchedUnloaded) {
            this.seed = seed;
            this.reach = reach;
            this.cells = cells;
            this.unsure = unsure;
            this.part = part;
            this.partSize = partSize;
            this.memberLists = memberLists;
            this.partLink = partLink;
            this.touchedUnloaded = touchedUnloaded;
            this.seedPart = part.get(seed);
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (long c : cells) {
                int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
                if (x < x0) x0 = x;
                if (y < y0) y0 = y;
                if (z < z0) z0 = z;
                if (x > x1) x1 = x;
                if (y > y1) y1 = y;
                if (z > z1) z1 = z;
            }
            minX = x0;
            minY = y0;
            minZ = z0;
            maxX = x1;
            maxY = y1;
            maxZ = z1;
        }

        public int partCount() {
            return partSize.length;
        }

        /** The cells of one part. */
        public LongArrayList members(int partId) {
            return memberLists[partId];
        }

        /** Part id of a cell, or -1 when the flood did not reach it. */
        public int partOf(long cell) {
            return part.containsKey(cell) ? part.get(cell) : -1;
        }
    }

    // ---- looking at single cells (the hover, and choosing a seed)

    /** What the cell at a position is taken for, with trunks told from beams. */
    public static Kind kindOf(Field f, int x, int y, int z) {
        return new Job(f, BlockPos.asLong(x, y, z), 0, 1, null).kindAt(BlockPos.asLong(x, y, z));
    }

    /** The BUILT cell nearest to a position within {@code radius} blocks (Chebyshev, ties broken by distance), or null: a click on the grass beside a wall means the wall. */
    public static @Nullable BlockPos nearestBuilt(Field f, BlockPos at, int radius) {
        Job j = new Job(f, at.asLong(), 0, 1, null);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    if (j.kindAt(p.asLong()) != Kind.BUILT) continue;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /**
     * The ground a pick stands on, one layer: under each column of the pick, the first terrain block within {@code depth}
     * blocks below its lowest cell. What the "include the ground under it" choice adds.
     */
    public static LongOpenHashSet groundUnder(Field f, LongOpenHashSet picked, int depth) {
        Long2IntOpenHashMap lowest = new Long2IntOpenHashMap();
        lowest.defaultReturnValue(Integer.MAX_VALUE);
        for (long c : picked) {
            long col = ((long) BlockPos.getX(c) << 32) | (BlockPos.getZ(c) & 0xFFFFFFFFL);
            int y = BlockPos.getY(c);
            if (y < lowest.get(col)) lowest.put(col, y);
        }
        LongOpenHashSet out = new LongOpenHashSet();
        for (var e : lowest.long2IntEntrySet()) {
            int x = (int) (e.getLongKey() >> 32), z = (int) e.getLongKey();
            if (!f.loaded(x, z)) continue;
            for (int d = 1; d <= depth; d++) {
                int y = e.getIntValue() - d;
                long cell = BlockPos.asLong(x, y, z);
                if (picked.contains(cell)) break;
                Kind k = BlockKinds.classify(f.state(x, y, z));
                if (k == Kind.AIR) continue;
                if (k == Kind.TERRAIN) out.add(cell);
                break;
            }
        }
        return out;
    }

    // ---- the job

    private enum Phase {
        FLOOD, FLUIDS, DENSITY, BODIES, CLAIM, DONE, FAILED
    }

    private static final Kind[] KINDS = Kind.values();

    private static int[][] offsets(int reach) {
        int n = 26 + 6 * reach;
        int[][] o = new int[n][];
        int i = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) if (dx != 0 || dy != 0 || dz != 0) o[i++] = new int[]{dx, dy, dz};
        for (int d = 2; d <= reach + 1; d++) {
            o[i++] = new int[]{d, 0, 0};
            o[i++] = new int[]{-d, 0, 0};
            o[i++] = new int[]{0, d, 0};
            o[i++] = new int[]{0, -d, 0};
            o[i++] = new int[]{0, 0, d};
            o[i++] = new int[]{0, 0, -d};
        }
        return o;
    }

    private static final int[][] NEAR = offsets(0), FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    public static final class Job {
        private final Field field;
        private final long seed;
        private final int reach, limit;
        private final @Nullable Block forced;
        private final int[][] links;
        private final Map<BlockState, Kind> stateKinds = new HashMap<>();
        private final Long2ByteOpenHashMap kinds = new Long2ByteOpenHashMap();
        private final Long2ByteOpenHashMap lumps = new Long2ByteOpenHashMap();
        private final LongOpenHashSet core = new LongOpenHashSet(), unsure = new LongOpenHashSet();
        private final LongArrayList queue = new LongArrayList();
        private int head;
        private boolean touchedUnloaded;
        private Phase phase = Phase.FLOOD;
        private String failure;
        private Result result;

        // later phases
        private long[] sorted;
        private int cursor;
        private LongOpenHashSet fluidSeen;
        private LongOpenHashSet dense;
        private Long2IntOpenHashMap body;
        private IntArrayList bodySize;
        private LongArrayList bfs;
        private int bfsHead;
        private int bodiesKept;
        private Long2IntOpenHashMap partOf;
        private IntArrayList partSizes;
        private final java.util.ArrayList<LongArrayList> partCells = new java.util.ArrayList<>();
        private final java.util.ArrayList<Boolean> partLinks = new java.util.ArrayList<>();
        private LongOpenHashSet thinSeen;

        /**
         * @param forced a block that counts as built whatever it is (the player said "this is the build": a hut of plain stone)
         */
        public Job(Field field, long seed, int reach, int limit, @Nullable Block forced) {
            this.field = field;
            this.seed = seed;
            this.reach = Math.max(0, Math.min(MAX_REACH, reach));
            this.limit = limit;
            this.forced = forced;
            this.links = offsets(this.reach);
            kinds.defaultReturnValue((byte) -1);
            lumps.defaultReturnValue((byte) -1);
            core.add(seed);
            queue.add(seed);
        }

        public boolean done() {
            return phase == Phase.DONE || phase == Phase.FAILED;
        }

        /** Why there is no result, in words for the player; null while running or after success. */
        public @Nullable String failure() {
            return failure;
        }

        public @Nullable Result result() {
            return result;
        }

        public double progress() {
            return switch (phase) {
                case FLOOD -> 0.55 * head / Math.max(1, queue.size());
                case FLUIDS -> 0.55 + 0.05 * frac();
                case DENSITY -> 0.6 + 0.15 * frac();
                case BODIES -> 0.75 + 0.1 * frac();
                case CLAIM -> 0.85 + 0.15 * frac();
                default -> 1;
            };
        }

        private double frac() {
            return sorted == null || sorted.length == 0 ? 1 : Math.min(1.0, (double) cursor / sorted.length);
        }

        public int cellsSoFar() {
            return core.size();
        }

        // ---- cells

        Kind kindAt(long pos) {
            byte c = kinds.get(pos);
            if (c >= 0) return KINDS[c];
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            Kind k;
            if (!field.loaded(x, z)) {
                k = Kind.UNLOADED;
            } else {
                BlockState st = field.state(x, y, z);
                if (forced != null && !st.isAir() && st.getBlock() == forced) {
                    k = Kind.BUILT;
                } else {
                    k = stateKinds.computeIfAbsent(st, BlockKinds::classify);
                    if (k == Kind.LOG) k = grewHere(x, y, z) ? Kind.VEGETATION : Kind.BUILT;
                }
            }
            kinds.put(pos, (byte) k.ordinal());
            return k;
        }

        /** Whether a tree's leaves are around this log: then it is a trunk, not a beam. */
        private boolean grewHere(int x, int y, int z) {
            return BoxFilter.grewHere(field, x, y, z);
        }

        /** Whether a terrain cell belongs to a small lump (fewer than {@link #TERRAIN_MASS} cells, nothing unloaded) rather than to the ground. */
        private boolean smallLump(long pos) {
            byte c = lumps.get(pos);
            if (c >= 0) return c == 1;
            LongOpenHashSet seen = new LongOpenHashSet();
            LongArrayList q = new LongArrayList();
            seen.add(pos);
            q.add(pos);
            boolean small = true;
            for (int h = 0; h < q.size() && small; h++) {
                long p = q.getLong(h);
                for (int[] f : FACES) {
                    long n = BlockPos.offset(p, f[0], f[1], f[2]);
                    if (seen.contains(n)) continue;
                    byte known = lumps.get(n);
                    if (known == 0) {
                        small = false;
                        break;
                    }
                    Kind k = kindAt(n);
                    if (k == Kind.UNLOADED) {
                        small = false;
                        break;
                    }
                    if (k != Kind.TERRAIN) continue;
                    seen.add(n);
                    q.add(n);
                    if (seen.size() >= TERRAIN_MASS) {
                        small = false;
                        break;
                    }
                }
            }
            byte v = (byte) (small ? 1 : 0);
            for (long p : seen) lumps.put(p, v);
            return small;
        }

        // ---- stepping

        /** Works for about this long. @return true once the result (or the failure) is ready */
        public boolean step(long budgetNs) {
            long end = System.nanoTime() + budgetNs;
            while (!done()) {
                Phase before = phase;
                long t0 = System.nanoTime();
                switch (phase) {
                    case FLOOD -> flood(end);
                    case FLUIDS -> fluids(end);
                    case DENSITY -> density(end);
                    case BODIES -> bodies(end);
                    case CLAIM -> claim(end);
                    default -> {
                    }
                }
                long took = System.nanoTime() - t0;
                if (took > WORST_NS[before.ordinal()]) WORST_NS[before.ordinal()] = took;
                if (!done() && System.nanoTime() >= end) return false;
            }
            return true;
        }

        private void fail(String why) {
            failure = why;
            phase = Phase.FAILED;
        }

        private void flood(long end) {
            int n = 0;
            while (head < queue.size()) {
                long c = queue.getLong(head++);
                for (int[] o : links) {
                    long p = BlockPos.offset(c, o[0], o[1], o[2]);
                    if (core.contains(p)) continue;
                    boolean jump = Math.abs(o[0]) > 1 || Math.abs(o[1]) > 1 || Math.abs(o[2]) > 1;
                    Kind k = kindAt(p);
                    boolean add = false, doubt = false;
                    if (k == Kind.BUILT) {
                        add = true;
                    } else if (k == Kind.TERRAIN && !jump) {
                        if (smallLump(p)) {
                            add = true;
                            doubt = true;
                        }
                    } else if (k == Kind.UNLOADED && !jump) {
                        touchedUnloaded = true;
                    }
                    if (!add) continue;
                    core.add(p);
                    if (doubt) unsure.add(p);
                    queue.add(p);
                    if (core.size() > limit) {
                        fail("Stopped at " + String.format("%,d", limit) + " blocks: this is more than one build. Aim at a single part, or scroll to lower the reach.");
                        return;
                    }
                }
                if ((++n & 63) == 0 && System.nanoTime() >= end) return;
            }
            phase = Phase.FLUIDS;
            sorted = core.toLongArray();
            Arrays.sort(sorted);
            cursor = 0;
            fluidSeen = new LongOpenHashSet();
        }

        /** A pool inside the build is part of it; a pond touching the ground is not. */
        private void fluids(long end) {
            int n = 0;
            int size = sorted.length;
            while (cursor < size) {
                long c = sorted[cursor++];
                for (int[] f : FACES) {
                    long p = BlockPos.offset(c, f[0], f[1], f[2]);
                    if (core.contains(p) || fluidSeen.contains(p) || kindAt(p) != Kind.FLUID) continue;
                    absorbFluid(p);
                }
                if ((++n & 63) == 0 && System.nanoTime() >= end) return;
            }
            phase = Phase.DENSITY;
            // the order only changes if a pool joined
            if (core.size() != sorted.length) {
                sorted = core.toLongArray();
                Arrays.sort(sorted);
            }
            cursor = 0;
            dense = new LongOpenHashSet();
        }

        private void absorbFluid(long start) {
            LongArrayList comp = new LongArrayList();
            LongOpenHashSet in = new LongOpenHashSet();
            comp.add(start);
            in.add(start);
            boolean ok = true;
            for (int h = 0; h < comp.size() && ok; h++) {
                long p = comp.getLong(h);
                for (int[] f : FACES) {
                    long q = BlockPos.offset(p, f[0], f[1], f[2]);
                    if (in.contains(q)) continue;
                    Kind k = kindAt(q);
                    if (k == Kind.FLUID) {
                        if (in.size() >= FLUID_CAP) {
                            ok = false;
                            break;
                        }
                        in.add(q);
                        comp.add(q);
                    } else if (!core.contains(q) && k != Kind.AIR) {
                        // it touches the ground, a plant, or somebody else's block: it is not held by this build alone
                        ok = false;
                        break;
                    }
                }
            }
            fluidSeen.addAll(in);
            if (ok) core.addAll(in);
        }

        /**
         * Whether the cell's face neighbours lie along at least two axes, as they do on a wall, a floor or a roof. A fence
         * running up to a wall has plenty of the wall around its end cell, but only one line through it, so it stays thin.
         */
        private boolean spansAPlane(long c) {
            int axes = 0;
            for (int a = 0; a < 3; a++) {
                int dx = a == 0 ? 1 : 0, dy = a == 1 ? 1 : 0, dz = a == 2 ? 1 : 0;
                if (core.contains(BlockPos.offset(c, dx, dy, dz)) || core.contains(BlockPos.offset(c, -dx, -dy, -dz))) axes++;
            }
            return axes >= 2;
        }

        private void density(long end) {
            int n = 0;
            while (cursor < sorted.length) {
                long c = sorted[cursor++];
                int count = 1;
                for (int[] o : NEAR) if (core.contains(BlockPos.offset(c, o[0], o[1], o[2]))) count++;
                if (count >= DENSE && spansAPlane(c)) dense.add(c);
                if ((++n & 255) == 0 && System.nanoTime() >= end) return;
            }
            phase = Phase.BODIES;
            body = new Long2IntOpenHashMap();
            body.defaultReturnValue(-1);
            bodySize = new IntArrayList();
            bfs = new LongArrayList();
            bfsHead = 0;
            cursor = 0;
        }

        /** Dense cells joined into bodies, through the same links the flood used. */
        private void bodies(long end) {
            int n = 0;
            while (true) {
                if (bfsHead < bfs.size()) {
                    long c = bfs.getLong(bfsHead++);
                    int id = bodySize.size() - 1;
                    for (int[] o : links) {
                        long p = BlockPos.offset(c, o[0], o[1], o[2]);
                        if (dense.contains(p) && body.get(p) < 0) {
                            body.put(p, id);
                            bodySize.set(id, bodySize.getInt(id) + 1);
                            bfs.add(p);
                        }
                    }
                    if ((++n & 127) == 0 && System.nanoTime() >= end) return;
                    continue;
                }
                bfs.clear();
                bfsHead = 0;
                boolean started = false;
                while (cursor < sorted.length) {
                    long c = sorted[cursor++];
                    if (!dense.contains(c) || body.get(c) >= 0) continue;
                    bodySize.add(1);
                    body.put(c, bodySize.size() - 1);
                    bfs.add(c);
                    started = true;
                    break;
                }
                if (!started) break;
            }
            // a body too small to be a building is thin stuff after all: the rest are renumbered 0, 1, 2 ...
            int[] renumber = new int[bodySize.size()];
            int kept = 0;
            for (int i = 0; i < renumber.length; i++) renumber[i] = bodySize.getInt(i) >= MIN_BODY ? kept++ : -1;
            bodiesKept = kept;
            partSizes = new IntArrayList();
            for (int i = 0; i < renumber.length; i++) if (renumber[i] >= 0) partSizes.add(bodySize.getInt(i));
            for (int i = 0; i < kept; i++) partLinks.add(false);
            partOf = new Long2IntOpenHashMap(core.size());
            partOf.defaultReturnValue(-1);
            for (int i = 0; i < kept; i++) partCells.add(new LongArrayList(partSizes.getInt(i)));
            for (long c : sorted) {
                int b = body.get(c);
                int r = b < 0 ? -1 : renumber[b];
                if (r >= 0) {
                    partOf.put(c, r);
                    partCells.get(r).add(c);
                }
            }
            phase = Phase.CLAIM;
            cursor = 0;
            thinSeen = new LongOpenHashSet();
            bfs.clear();
            bfsHead = 0;
        }

        /** Thin cells: connected groups of them go to the body they touch; a group touching two bodies is a link of its own. */
        private void claim(long end) {
            int n = 0;
            while (cursor < sorted.length) {
                long start = sorted[cursor++];
                if (partOf.containsKey(start) || thinSeen.contains(start)) continue;
                LongArrayList comp = new LongArrayList();
                Int2IntOpenHashMap contacts = new Int2IntOpenHashMap();
                comp.add(start);
                thinSeen.add(start);
                for (int h = 0; h < comp.size(); h++) {
                    long c = comp.getLong(h);
                    for (int[] o : links) {
                        long p = BlockPos.offset(c, o[0], o[1], o[2]);
                        if (!core.contains(p)) continue;
                        int b = partOf.get(p);
                        if (b >= 0) {
                            contacts.addTo(b, 1);
                        } else if (!thinSeen.contains(p)) {
                            thinSeen.add(p);
                            comp.add(p);
                        }
                    }
                }
                int best = -1, bestN = 0, secondN = 0;
                for (var e : contacts.int2IntEntrySet()) {
                    int cnt = e.getIntValue();
                    if (cnt > bestN || (cnt == bestN && e.getIntKey() < best)) {
                        secondN = bestN;
                        bestN = cnt;
                        best = e.getIntKey();
                    } else if (cnt > secondN) {
                        secondN = cnt;
                    }
                }
                int id;
                if (best >= 0 && (secondN == 0 || bestN >= 4 * secondN)) {
                    id = best;
                    partSizes.set(id, partSizes.getInt(id) + comp.size());
                } else {
                    id = partSizes.size();
                    partSizes.add(comp.size());
                    partLinks.add(best >= 0);
                    partCells.add(new LongArrayList(comp.size()));
                }
                LongArrayList into = partCells.get(id);
                for (int i = 0; i < comp.size(); i++) {
                    partOf.put(comp.getLong(i), id);
                    into.add(comp.getLong(i));
                }
                n += comp.size();
                if (n > 512 && System.nanoTime() >= end) return;
            }
            int[] sizes = partSizes.toIntArray();
            boolean[] link = new boolean[sizes.length];
            for (int i = 0; i < link.length; i++) link[i] = partLinks.get(i);
            result = new Result(seed, reach, core, unsure, partOf, sizes, partCells.toArray(new LongArrayList[0]), link, touchedUnloaded);
            phase = Phase.DONE;
        }
    }
}
