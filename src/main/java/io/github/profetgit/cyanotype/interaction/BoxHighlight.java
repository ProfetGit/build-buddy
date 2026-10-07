package io.github.profetgit.cyanotype.interaction;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Lights up what a side of the Save area box has just gained or lost, so it is plain what moving it did: blocks the box now
 * holds that it did not hold when the move began are lit cyan, blocks it let go of are lit red. Nothing is lit while no side
 * is being moved. Full blocks are lit as a skin (only the faces that can be seen, joined into big rectangles); blocks that are
 * not full (slabs, stairs, torches, fences) by their own shape. Nothing is dropped or sampled, only what is farther than
 * {@link #RANGE} from the camera. The lists are made once per box change, from the cells of the change alone.
 */
final class BoxHighlight {
    /** Changes with more cells than this are not lit (reading them would stall the game while a side is dragged). */
    static final long MAX_CELLS = 400_000L;
    /** The most shapes of blocks that are not full: the nearest ones win. */
    static final int MAX_SHAPES = 4000;
    /** The most rectangles of the skin. Joined faces make this very hard to reach. */
    static final int MAX_QUADS = 20_000;
    /** Blocks farther than this from the camera are not lit. */
    private static final double RANGE = 112;
    private static final int ADDED = 0xFF7FE3FF, REMOVED = 0xFFFF8A9A;
    /** How far a lit face stands off the block, so it never fights the block's own face for the same depth. */
    private static final double LIFT = 0.012;

    private record Quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
    }

    /** What is lit for one kind of change. */
    private static final class Lit {
        final int color;
        final List<AABB> shapes = new ArrayList<>();
        final List<Quad> quads = new ArrayList<>();

        Lit(int color) {
            this.color = color;
        }
    }

    private static final Lit added = new Lit(ADDED), removed = new Lit(REMOVED);
    private static @Nullable SelectionBox builtFor, builtFrom;
    private static boolean tooBig;

    private BoxHighlight() {
    }

    static void clear() {
        for (Lit l : List.of(added, removed)) {
            l.shapes.clear();
            l.quads.clear();
        }
        builtFor = null;
        builtFrom = null;
        tooBig = false;
    }

    /** What is lit now, as boxes (for the demo): the shapes of the blocks that are not full, and each face rectangle as a thin slab. */
    static List<AABB> shapes() {
        List<AABB> all = new ArrayList<>();
        for (Lit l : List.of(added, removed)) {
            all.addAll(l.shapes);
            for (Quad q : l.quads) {
                double x0 = Math.min(Math.min(q.a.x, q.b.x), Math.min(q.c.x, q.d.x)), x1 = Math.max(Math.max(q.a.x, q.b.x), Math.max(q.c.x, q.d.x));
                double y0 = Math.min(Math.min(q.a.y, q.b.y), Math.min(q.c.y, q.d.y)), y1 = Math.max(Math.max(q.a.y, q.b.y), Math.max(q.c.y, q.d.y));
                double z0 = Math.min(Math.min(q.a.z, q.b.z), Math.min(q.c.z, q.d.z)), z1 = Math.max(Math.max(q.a.z, q.b.z), Math.max(q.c.z, q.d.z));
                all.add(new AABB(x0, y0, z0, x1, y1, z1));
            }
        }
        return all;
    }

    /** Dev demo: how many of the shapes are for what the box lost. */
    static int removedCount() {
        return removed.shapes.size() + removed.quads.size();
    }

    static boolean tooBig() {
        return tooBig;
    }

    private static boolean in(SelectionBox b, int x, int y, int z) {
        return x >= b.x0() && x <= b.x1() && y >= b.y0() && y <= b.y1() && z >= b.z0() && z <= b.z1();
    }

    /** Makes the lists for a box that moved from {@code from}, when they are not already made for this pair. */
    static void refresh(ClientLevel level, SelectionBox box, SelectionBox from, Vec3 camera) {
        if (box.equals(builtFor) && from.equals(builtFrom)) return;
        builtFor = box;
        builtFrom = from;
        clear2();
        int x0 = Math.min(box.x0(), from.x0()), y0 = Math.min(box.y0(), from.y0()), z0 = Math.min(box.z0(), from.z0());
        int x1 = Math.max(box.x1(), from.x1()), y1 = Math.max(box.y1(), from.y1()), z1 = Math.max(box.z1(), from.z1());
        int[] n = {x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1};
        int[] o = {x0, y0, z0};
        tooBig = (long) n[0] * n[1] * n[2] > MAX_CELLS;
        if (tooBig) return;
        // what the world is made of here, one read per cell: full blocks (a face against one cannot be seen) and the changed cells' kinds
        boolean[] solid = new boolean[n[0] * n[1] * n[2]];
        boolean[] fullAdded = new boolean[solid.length], fullRemoved = new boolean[solid.length];
        List<AABB> partAdded = new ArrayList<>(), partRemoved = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < n[1]; y++) for (int z = 0; z < n[2]; z++) for (int x = 0; x < n[0]; x++) {
            int wx = o[0] + x, wy = o[1] + y, wz = o[2] + z;
            boolean now = in(box, wx, wy, wz), was = in(from, wx, wy, wz);
            int i = (y * n[2] + z) * n[0] + x;
            pos.set(wx, wy, wz);
            BlockState s = level.getBlockState(pos);
            solid[i] = s.isSolidRender();
            if (now == was) continue;
            if (camera.distanceToSqr(wx + 0.5, wy + 0.5, wz + 0.5) > RANGE * RANGE) continue;
            if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
            if (solid[i]) {
                (now ? fullAdded : fullRemoved)[i] = true;
                continue;
            }
            VoxelShape shape = s.getShape(level, pos);
            if (shape.isEmpty()) shape = net.minecraft.world.phys.shapes.Shapes.block();
            for (AABB part : shape.toAabbs()) (now ? partAdded : partRemoved).add(part.move(pos).inflate(LIFT));
        }
        fill(added, partAdded, fullAdded, solid, level, n, o, camera);
        fill(removed, partRemoved, fullRemoved, solid, level, n, o, camera);
    }

    private static void clear2() {
        for (Lit l : List.of(added, removed)) {
            l.shapes.clear();
            l.quads.clear();
        }
        tooBig = false;
    }

    private static void fill(Lit lit, List<AABB> partial, boolean[] full, boolean[] solid, ClientLevel level, int[] n, int[] o, Vec3 camera) {
        if (partial.size() > MAX_SHAPES) {
            partial.sort((p, q) -> Double.compare(p.getCenter().distanceToSqr(camera), q.getCenter().distanceToSqr(camera)));
            partial = partial.subList(0, MAX_SHAPES);
        }
        lit.shapes.addAll(partial);
        skin(lit, full, solid, level, n, o);
    }

    /** The seen faces of the full blocks, joined into rectangles one plane at a time. A face against any full block of the world is not seen. */
    private static void skin(Lit lit, boolean[] full, boolean[] solid, ClientLevel level, int[] n, int[] o) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int a = 0; a < 3; a++) {
            int u = (a + 1) % 3, v = (a + 2) % 3;
            for (int sign = -1; sign <= 1; sign += 2) {
                for (int plane = 0; plane < n[a]; plane++) {
                    boolean[] mask = new boolean[n[u] * n[v]];
                    boolean any = false;
                    for (int j = 0; j < n[v]; j++) for (int i = 0; i < n[u]; i++) {
                        int[] c = new int[3];
                        c[a] = plane;
                        c[u] = i;
                        c[v] = j;
                        if (!full[(c[1] * n[2] + c[2]) * n[0] + c[0]]) continue;
                        int nb = plane + sign;
                        boolean hidden;
                        if (nb >= 0 && nb < n[a]) {
                            int[] d = c.clone();
                            d[a] = nb;
                            hidden = solid[(d[1] * n[2] + d[2]) * n[0] + d[0]];
                        } else {
                            pos.set(o[0] + c[0], o[1] + c[1], o[2] + c[2]).move(a == 0 ? sign : 0, a == 1 ? sign : 0, a == 2 ? sign : 0);
                            hidden = level.getBlockState(pos).isSolidRender();
                        }
                        if (!hidden) {
                            mask[j * n[u] + i] = true;
                            any = true;
                        }
                    }
                    if (!any) continue;
                    double at = o[a] + plane + (sign > 0 ? 1 + LIFT : -LIFT);
                    for (int j = 0; j < n[v]; j++) for (int i = 0; i < n[u]; i++) {
                        if (!mask[j * n[u] + i]) continue;
                        int w = 1;
                        while (i + w < n[u] && mask[j * n[u] + i + w]) w++;
                        int h = 1;
                        grow:
                        while (j + h < n[v]) {
                            for (int k = 0; k < w; k++) if (!mask[(j + h) * n[u] + i + k]) break grow;
                            h++;
                        }
                        for (int jj = 0; jj < h; jj++) for (int ii = 0; ii < w; ii++) mask[(j + jj) * n[u] + i + ii] = false;
                        if (lit.quads.size() >= MAX_QUADS) return;
                        double u0 = o[u] + i, u1 = o[u] + i + w, v0 = o[v] + j, v1 = o[v] + j + h;
                        lit.quads.add(new Quad(point(a, u, v, at, u0, v0), point(a, u, v, at, u1, v0), point(a, u, v, at, u1, v1), point(a, u, v, at, u0, v1)));
                    }
                }
            }
        }
    }

    private static Vec3 point(int a, int u, int v, double at, double uc, double vc) {
        double[] p = new double[3];
        p[a] = at;
        p[u] = uc;
        p[v] = vc;
        return new Vec3(p[0], p[1], p[2]);
    }

    static void draw() {
        for (Lit l : List.of(added, removed)) {
            GizmoStyle style = GizmoStyle.fill(((l == added ? 0x52 : 0x66) << 24) | (l.color & 0xFFFFFF));
            for (Quad q : l.quads) Gizmos.rect(q.a, q.b, q.c, q.d, style);
            for (AABB a : l.shapes) Gizmos.cuboid(a, style);
        }
    }
}
