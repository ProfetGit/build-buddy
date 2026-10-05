package io.github.profetgit.cyanotype.pick;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * The skin of a set of cells: every face that looks into open space, with neighbouring faces merged into rectangles
 * (a wall ten blocks long is one rectangle, not fifty squares), so the pick can be drawn over the world with a few hundred
 * shapes instead of thousands. Plain data out, no drawing.
 */
public final class FaceMesher {
    public static final int PICKED = 0, DOUBTFUL = 1, CONTEXT = 2;

    /** Rectangles, each four corners of three floats; {@code kind[i]} says what it covers. */
    public static final class Quads {
        public final float[] corners;
        public final byte[] kind;
        public final int count;

        Quads(float[] corners, byte[] kind, int count) {
            this.corners = corners;
            this.kind = kind;
            this.count = count;
        }
    }

    private static final float LIFT = 0.004f;
    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private FaceMesher() {
    }

    /**
     * @param picked   the cells that are picked (drawn in the picked tint, or the doubtful one for those also in {@code doubtful})
     * @param context  cells reached but not picked (drawn faintly); may be empty
     * @param cap      most rectangles to return; the faint ones go first when there are more
     */
    public static Quads build(LongOpenHashSet picked, LongOpenHashSet doubtful, LongOpenHashSet context, int cap) {
        // one group per (what, which way it faces, which layer)
        Long2ObjectOpenHashMap<LongArrayList> groups = new Long2ObjectOpenHashMap<>();
        collect(picked, picked, doubtful, groups, false);
        LongOpenHashSet hidden = new LongOpenHashSet(context);
        hidden.addAll(picked);
        collect(context, hidden, null, groups, true);
        LongArrayList order = new LongArrayList(groups.keySet());
        java.util.Arrays.sort(order.elements(), 0, order.size());
        int n = 0;
        float[] out = new float[Math.max(12, 12 * 256)];
        byte[] outKind = new byte[256];
        for (long gk : order) {
            LongArrayList faces = groups.get(gk);
            int kind = (int) (gk & 3);
            int dir = (int) ((gk >> 2) & 7);
            int layer = (int) (gk >> 5);
            int[] d = DIRS[dir];
            int axis = d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2;
            // u and v are the other two axes, in order
            int uAxis = axis == 0 ? 1 : 0, vAxis = axis == 2 ? 1 : 2;
            int u0 = Integer.MAX_VALUE, u1 = Integer.MIN_VALUE, v0 = Integer.MAX_VALUE, v1 = Integer.MIN_VALUE;
            for (int i = 0; i < faces.size(); i++) {
                long f = faces.getLong(i);
                int u = (int) (f >> 32), v = (int) f;
                if (u < u0) u0 = u;
                if (u > u1) u1 = u;
                if (v < v0) v0 = v;
                if (v > v1) v1 = v;
            }
            int w = u1 - u0 + 1, h = v1 - v0 + 1;
            boolean[] grid = new boolean[w * h];
            for (int i = 0; i < faces.size(); i++) {
                long f = faces.getLong(i);
                grid[((int) f - v0) * w + ((int) (f >> 32) - u0)] = true;
            }
            for (int v = 0; v < h; v++) {
                for (int u = 0; u < w; u++) {
                    if (!grid[v * w + u]) continue;
                    int rw = 1;
                    while (u + rw < w && grid[v * w + u + rw]) rw++;
                    int rh = 1;
                    outer:
                    while (v + rh < h) {
                        for (int k = 0; k < rw; k++) if (!grid[(v + rh) * w + u + k]) break outer;
                        rh++;
                    }
                    for (int dv = 0; dv < rh; dv++) for (int du = 0; du < rw; du++) grid[(v + dv) * w + u + du] = false;
                    if ((n + 1) * 12 > out.length) {
                        out = java.util.Arrays.copyOf(out, out.length * 2);
                        outKind = java.util.Arrays.copyOf(outKind, outKind.length * 2);
                    }
                    float plane = layer + (d[axis] > 0 ? 1 + LIFT : -LIFT);
                    float ua = u0 + u, ub = ua + rw, va = v0 + v, vb = va + rh;
                    put(out, n * 12, axis, uAxis, vAxis, plane, ua, va, ub, va, ub, vb, ua, vb);
                    outKind[n] = (byte) kind;
                    n++;
                }
            }
        }
        // the faint ones are the first to go when there are too many
        if (n > cap) {
            float[] keep = new float[cap * 12];
            byte[] keepKind = new byte[cap];
            int m = 0;
            for (int pass = 0; pass < 3 && m < cap; pass++) {
                for (int i = 0; i < n && m < cap; i++) {
                    if (outKind[i] != pass) continue;
                    System.arraycopy(out, i * 12, keep, m * 12, 12);
                    keepKind[m++] = outKind[i];
                }
            }
            return new Quads(keep, keepKind, m);
        }
        return new Quads(java.util.Arrays.copyOf(out, n * 12), java.util.Arrays.copyOf(outKind, n), n);
    }

    private static void put(float[] o, int at, int axis, int uAxis, int vAxis, float plane, float... uv) {
        for (int c = 0; c < 4; c++) {
            o[at + c * 3 + axis] = plane;
            o[at + c * 3 + uAxis] = uv[c * 2];
            o[at + c * 3 + vAxis] = uv[c * 2 + 1];
        }
    }

    /** Collects the exposed faces of {@code cells}: a face is exposed when the cell beside it is not in {@code solid}. */
    private static void collect(LongOpenHashSet cells, LongOpenHashSet solid, LongOpenHashSet doubtful, Long2ObjectOpenHashMap<LongArrayList> groups, boolean context) {
        for (long c : cells) {
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            int kind = context ? CONTEXT : doubtful != null && doubtful.contains(c) ? DOUBTFUL : PICKED;
            for (int dir = 0; dir < 6; dir++) {
                int[] d = DIRS[dir];
                long nb = BlockPos.asLong(x + d[0], y + d[1], z + d[2]);
                if (solid.contains(nb)) continue;
                int axis = d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2;
                int layer = axis == 0 ? x : axis == 1 ? y : z;
                int u = axis == 0 ? y : x, v = axis == 2 ? y : z;
                long key = ((long) layer << 5) | ((long) dir << 2) | kind;
                LongArrayList list = groups.get(key);
                if (list == null) {
                    list = new LongArrayList();
                    groups.put(key, list);
                }
                list.add(((long) u << 32) | (v & 0xFFFFFFFFL));
            }
        }
    }
}
