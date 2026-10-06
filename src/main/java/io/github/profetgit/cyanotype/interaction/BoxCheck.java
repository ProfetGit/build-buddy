package io.github.profetgit.cyanotype.interaction;

import io.github.profetgit.cyanotype.pick.BlockKinds;
import io.github.profetgit.cyanotype.pick.Kind;
import io.github.profetgit.cyanotype.pick.Picker;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Tells whether a Save area box cuts a build in two: on each of the box's six faces, the cells of the outermost layer where a
 * built block inside has a built block touching it on the other side of the face (straight on, or slanting by one: a stair or slab roof climbs a step sideways at a time). Those are the places where the build goes on past the box. Pure
 * apart from the block classification ({@link BlockKinds}), so it is tested on made-up worlds. {@link #fit} grows the box
 * where it cuts until it no longer does.
 */
public final class BoxCheck {
    /** A box with more face cells than this is not checked (the check would take too long to run as the box is dragged). */
    public static final long MAX_FACE_CELLS = 600_000L;
    /** How many cut cells per face are kept to draw. */
    public static final int MAX_SAMPLES = 1500;
    /** A log with a natural leaf this close is a tree's trunk, not a beam of a build. */
    private static final int TRUNK_REACH = 3;
    /** A face is cut only when at least this many different blocks outside it go on from the box: one or two (a fence post, a torch on a wall) are not a building going on. */
    public static final int MIN_CUT = 3;
    /** The most cut cells collected per face before they are thinned to {@link #MAX_SAMPLES}. */
    private static final int KEEP = 30_000;

    private BoxCheck() {
    }

    /** Where the box cuts a build, face by face (indexed by {@link Direction#ordinal()}). */
    public static final class Result {
        /** The cut cells of each face (inside the box, {@link BlockPos#asLong}), thinned to at most {@link #MAX_SAMPLES} each. */
        public final long[][] cells = new long[6][];
        /** How many cut cells each face really has. */
        public final int[] count = new int[6];
        /** True when the box was too big to check. */
        public final boolean skipped;

        Result(boolean skipped) {
            this.skipped = skipped;
            for (int i = 0; i < 6; i++) cells[i] = new long[0];
        }

        public boolean cut(Direction face) {
            return count[face.ordinal()] > 0;
        }

        public boolean any() {
            for (int c : count) if (c > 0) return true;
            return false;
        }

        public int total() {
            int n = 0;
            for (int c : count) n += c;
            return n;
        }
    }

    /** Whether a cell is part of a build: a built block, or a log that is a beam and not a trunk (no natural leaf near it). Unloaded cells are nothing ({@code loaded} takes block coordinates, not chunk ones). */
    static boolean built(Picker.Field f, int x, int y, int z) {
        if (!f.loaded(x, z)) return false;
        BlockState s = f.state(x, y, z);
        Kind k = BlockKinds.classify(s);
        if (k == Kind.BUILT) return true;
        if (k != Kind.LOG) return false;
        for (int dy = -TRUNK_REACH; dy <= TRUNK_REACH; dy++) {
            for (int dz = -TRUNK_REACH; dz <= TRUNK_REACH; dz++) {
                for (int dx = -TRUNK_REACH; dx <= TRUNK_REACH; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if (f.loaded(nx, nz) && BlockKinds.isNaturalLeaf(f.state(nx, y + dy, nz))) return false;
                }
            }
        }
        return true;
    }

    /** The cells of one face of the box that are cut, as {@code asLong} of the cell inside the box; {@code ignore} says which outside cells to leave out (other buildings). */
    private static void scanFace(Picker.Field f, SelectionBox b, Direction face, @Nullable LongPredicate ignore, int stopAt, LongArrayList out, int[] count, LongOpenHashSet outside) {
        Direction.Axis axis = face.getAxis();
        int sign = face.getStepX() + face.getStepY() + face.getStepZ();
        int inside = switch (axis) {
            case X -> sign > 0 ? b.x1() : b.x0();
            case Y -> sign > 0 ? b.y1() : b.y0();
            case Z -> sign > 0 ? b.z1() : b.z0();
        };
        int aLo, aHi, bLo, bHi;
        switch (axis) {
            case X -> { aLo = b.y0(); aHi = b.y1(); bLo = b.z0(); bHi = b.z1(); }
            case Y -> { aLo = b.x0(); aHi = b.x1(); bLo = b.z0(); bHi = b.z1(); }
            default -> { aLo = b.x0(); aHi = b.x1(); bLo = b.y0(); bHi = b.y1(); }
        }
        for (int p = aLo; p <= aHi; p++) {
            for (int q = bLo; q <= bHi; q++) {
                int x, y, z;
                switch (axis) {
                    case X -> { x = inside; y = p; z = q; }
                    case Y -> { x = p; y = inside; z = q; }
                    default -> { x = p; y = q; z = inside; }
                }
                if (!built(f, x, y, z)) continue;
                // the build goes on if a built block touches this one on the far side of the face, straight on or slanting (a stair or slab roof climbs one step sideways at a time)
                boolean goesOn = false;
                for (int da = -1; da <= 1; da++) {
                    for (int db = -1; db <= 1; db++) {
                        int ox, oy, oz;
                        switch (axis) {
                            case X -> { ox = x + face.getStepX(); oy = y + da; oz = z + db; }
                            case Y -> { ox = x + da; oy = y + face.getStepY(); oz = z + db; }
                            default -> { ox = x + da; oy = y + db; oz = z + face.getStepZ(); }
                        }
                        if (built(f, ox, oy, oz) && (ignore == null || !ignore.test(BlockPos.asLong(ox, oy, oz)))) {
                            goesOn = true;
                            outside.add(BlockPos.asLong(ox, oy, oz));
                        }
                    }
                }
                if (!goesOn) continue;
                count[0]++;
                if (outside.size() >= stopAt) return;
                if (out != null && out.size() < KEEP) out.add(BlockPos.asLong(x, y, z));
            }
        }
    }

    private static long faceCells(SelectionBox b) {
        return 2L * (b.sizeX() * (long) b.sizeY() + b.sizeY() * (long) b.sizeZ() + b.sizeX() * (long) b.sizeZ());
    }

    /** Where the box cuts a build. */
    public static Result check(Picker.Field f, SelectionBox box, @Nullable LongPredicate ignore) {
        if (faceCells(box) > MAX_FACE_CELLS) return new Result(true);
        Result r = new Result(false);
        for (Direction d : Direction.values()) {
            LongArrayList list = new LongArrayList();
            int[] n = {0};
            LongOpenHashSet outside = new LongOpenHashSet();
            scanFace(f, box, d, ignore, Integer.MAX_VALUE, list, n, outside);
            // real only when at least MIN_CUT different blocks outside go on from it: one fence post touching three wall blocks is not a building
            boolean real = outside.size() >= MIN_CUT;
            r.cells[d.ordinal()] = real ? thin(list) : new long[0];
            r.count[d.ordinal()] = real ? n[0] : 0;
        }
        return r;
    }

    /** Every n-th cell, so that at most {@link #MAX_SAMPLES} are left and they still cover the whole face. */
    private static long[] thin(LongArrayList list) {
        if (list.size() <= MAX_SAMPLES) return list.toLongArray();
        int step = (list.size() + MAX_SAMPLES - 1) / MAX_SAMPLES;
        LongArrayList out = new LongArrayList();
        for (int i = 0; i < list.size(); i += step) out.add(list.getLong(i));
        return out.toLongArray();
    }

    /** Whether one face of the box cuts a build (stops once it is sure). */
    private static boolean cuts(Picker.Field f, SelectionBox box, Direction d, @Nullable LongPredicate ignore) {
        int[] n = {0};
        LongOpenHashSet outside = new LongOpenHashSet();
        scanFace(f, box, d, ignore, MIN_CUT, null, n, outside);
        return outside.size() >= MIN_CUT;
    }

    /** What {@link #fit} made: the new box, how many layers it added, and whether it stopped at the limit with a face still cut. */
    public record Fit(SelectionBox box, int layers, boolean stopped) {
    }

    /**
     * Grows the box one layer at a time on every face that cuts a build, until none does or {@code maxLayers} layers have been
     * added in all. Never shrinks it, so what the player chose to include (ground, a margin) stays.
     */
    public static Fit fit(Picker.Field f, SelectionBox box, @Nullable LongPredicate ignore, int maxLayers, long maxVolume) {
        SelectionBox b = box;
        int layers = 0;
        boolean[] done = new boolean[6];
        while (layers < maxLayers) {
            boolean grew = false;
            for (Direction d : Direction.values()) {
                if (done[d.ordinal()]) continue;
                if (faceCells(b) > MAX_FACE_CELLS * 4 || !cuts(f, b, d, ignore)) {
                    done[d.ordinal()] = true;
                    continue;
                }
                SelectionBox grown = b.moved(d, 1);
                if (grown.volume() > maxVolume) {
                    done[d.ordinal()] = true;
                    continue;
                }
                b = grown;
                layers++;
                grew = true;
                if (layers >= maxLayers) break;
            }
            if (!grew) break;
        }
        boolean stopped = false;
        if (layers >= maxLayers) {
            for (Direction d : Direction.values()) if (cuts(f, b, d, ignore)) stopped = true;
        }
        return new Fit(b, layers, stopped);
    }
}
