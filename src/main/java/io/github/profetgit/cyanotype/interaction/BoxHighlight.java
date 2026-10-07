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
 * Lights up the blocks that are inside the Save area box, so it is plain what the box holds and what it leaves out. Shown only
 * while a side of the box is being moved. Full blocks are lit as a skin: only the faces that can be seen (not the ones against
 * another full block), joined into big rectangles, so a whole wall is a handful of shapes however many blocks it has. Blocks
 * that are not full (slabs, stairs, torches, fences) are lit by their own shape. Nothing is dropped or sampled: a big box looks
 * the same as a small one, only the far parts (beyond {@link #RANGE}) are left out. The list is made once for a box, not every
 * frame.
 */
final class BoxHighlight {
    /** Boxes with more cells than this are not lit (reading them would stall the game while a side is dragged). */
    static final long MAX_CELLS = 400_000L;
    /** The most shapes of blocks that are not full: the nearest ones win. */
    static final int MAX_SHAPES = 4000;
    /** The most rectangles of the skin. Joined faces make this very hard to reach. */
    static final int MAX_QUADS = 20_000;
    /** Blocks farther than this from the camera are not lit. */
    private static final double RANGE = 112;
    private static final int COLOR = 0xFF7FE3FF;
    /** How far a lit face stands off the block, so it never fights the block's own face for the same depth. */
    private static final double LIFT = 0.012;

    private record Quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
    }

    private static final List<AABB> shapes = new ArrayList<>();
    private static final List<Quad> quads = new ArrayList<>();
    private static @Nullable SelectionBox builtFor;
    private static boolean tooBig;

    private BoxHighlight() {
    }

    static void clear() {
        shapes.clear();
        quads.clear();
        builtFor = null;
        tooBig = false;
    }

    /** What is lit now, as boxes (for the demo): the shapes of the blocks that are not full, and each face rectangle as a thin slab. */
    static List<AABB> shapes() {
        List<AABB> all = new ArrayList<>(shapes);
        for (Quad q : quads) {
            double x0 = Math.min(Math.min(q.a.x, q.b.x), Math.min(q.c.x, q.d.x)), x1 = Math.max(Math.max(q.a.x, q.b.x), Math.max(q.c.x, q.d.x));
            double y0 = Math.min(Math.min(q.a.y, q.b.y), Math.min(q.c.y, q.d.y)), y1 = Math.max(Math.max(q.a.y, q.b.y), Math.max(q.c.y, q.d.y));
            double z0 = Math.min(Math.min(q.a.z, q.b.z), Math.min(q.c.z, q.d.z)), z1 = Math.max(Math.max(q.a.z, q.b.z), Math.max(q.c.z, q.d.z));
            all.add(new AABB(x0, y0, z0, x1, y1, z1));
        }
        return all;
    }

    static boolean tooBig() {
        return tooBig;
    }

    /** Makes the list for a box, when it is not already made for this one. */
    static void refresh(ClientLevel level, SelectionBox box, Vec3 camera) {
        if (box.equals(builtFor)) return;
        builtFor = box;
        shapes.clear();
        quads.clear();
        tooBig = box.volume() > MAX_CELLS;
        if (tooBig) return;
        int[] n = {box.x1() - box.x0() + 1, box.y1() - box.y0() + 1, box.z1() - box.z0() + 1};
        int[] o = {box.x0(), box.y0(), box.z0()};
        boolean[] full = new boolean[n[0] * n[1] * n[2]];
        List<AABB> partial = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < n[1]; y++) for (int z = 0; z < n[2]; z++) for (int x = 0; x < n[0]; x++) {
            int wx = o[0] + x, wy = o[1] + y, wz = o[2] + z;
            if (camera.distanceToSqr(wx + 0.5, wy + 0.5, wz + 0.5) > RANGE * RANGE) continue;
            pos.set(wx, wy, wz);
            BlockState s = level.getBlockState(pos);
            if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
            if (s.isSolidRender()) {
                full[(y * n[2] + z) * n[0] + x] = true;
                continue;
            }
            VoxelShape shape = s.getShape(level, pos);
            if (shape.isEmpty()) shape = net.minecraft.world.phys.shapes.Shapes.block();
            for (AABB part : shape.toAabbs()) partial.add(part.move(pos).inflate(LIFT));
        }
        if (partial.size() > MAX_SHAPES) {
            partial.sort((p, q) -> Double.compare(p.getCenter().distanceToSqr(camera), q.getCenter().distanceToSqr(camera)));
            partial = partial.subList(0, MAX_SHAPES);
        }
        shapes.addAll(partial);
        skin(level, full, n, o);
    }

    /** The seen faces of the full blocks, joined into rectangles one plane at a time. */
    private static void skin(ClientLevel level, boolean[] full, int[] n, int[] o) {
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
                            hidden = full[(d[1] * n[2] + d[2]) * n[0] + d[0]];
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
                        if (quads.size() >= MAX_QUADS) return;
                        double u0 = o[u] + i, u1 = o[u] + i + w, v0 = o[v] + j, v1 = o[v] + j + h;
                        quads.add(new Quad(point(a, u, v, at, u0, v0), point(a, u, v, at, u1, v0), point(a, u, v, at, u1, v1), point(a, u, v, at, u0, v1)));
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
        int fill = (0x4A << 24) | (COLOR & 0xFFFFFF);
        GizmoStyle style = GizmoStyle.fill(fill);
        for (Quad q : quads) Gizmos.rect(q.a, q.b, q.c, q.d, style);
        for (AABB a : shapes) Gizmos.cuboid(a, style);
    }
}
