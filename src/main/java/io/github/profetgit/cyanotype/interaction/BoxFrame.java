package io.github.profetgit.cyanotype.interaction;

import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * How the Save area box is drawn so that it can be read at a glance, on a tall build too: edges on the near side solid and the
 * far ones thin and dashed (so the box has depth), the top edges brighter than the bottom ones, the floor lightly filled, the
 * side being pointed at or dragged filled.
 */
final class BoxFrame {
    static final int CYAN = 0xFF7FE3FF, AMBER = 0xFFFFC857, WHITE = 0xFFFFFFFF;

    private BoxFrame() {
    }

    private static int alpha(int argb, double a) {
        return ((int) Math.max(0, Math.min(255, a * 255)) << 24) | (argb & 0xFFFFFF);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static Vec3 corner(AABB a, int c) {
        return new Vec3((c & 1) != 0 ? a.maxX : a.minX, (c & 2) != 0 ? a.maxY : a.minY, (c & 4) != 0 ? a.maxZ : a.minZ);
    }

    /** The four corners of one face of the box. */
    static Vec3[] face(AABB a, Direction d, double out) {
        double x0 = a.minX, x1 = a.maxX, y0 = a.minY, y1 = a.maxY, z0 = a.minZ, z1 = a.maxZ;
        return switch (d) {
            case EAST -> new Vec3[]{new Vec3(x1 + out, y0, z0), new Vec3(x1 + out, y1, z0), new Vec3(x1 + out, y1, z1), new Vec3(x1 + out, y0, z1)};
            case WEST -> new Vec3[]{new Vec3(x0 - out, y0, z0), new Vec3(x0 - out, y1, z0), new Vec3(x0 - out, y1, z1), new Vec3(x0 - out, y0, z1)};
            case UP -> new Vec3[]{new Vec3(x0, y1 + out, z0), new Vec3(x1, y1 + out, z0), new Vec3(x1, y1 + out, z1), new Vec3(x0, y1 + out, z1)};
            case DOWN -> new Vec3[]{new Vec3(x0, y0 - out, z0), new Vec3(x1, y0 - out, z0), new Vec3(x1, y0 - out, z1), new Vec3(x0, y0 - out, z1)};
            case SOUTH -> new Vec3[]{new Vec3(x0, y0, z1 + out), new Vec3(x1, y0, z1 + out), new Vec3(x1, y1, z1 + out), new Vec3(x0, y1, z1 + out)};
            case NORTH -> new Vec3[]{new Vec3(x0, y0, z0 - out), new Vec3(x1, y0, z0 - out), new Vec3(x1, y1, z0 - out), new Vec3(x0, y1, z0 - out)};
        };
    }

    private static void fill(Vec3[] q, int color, double a) {
        Gizmos.rect(q[0], q[1], q[2], q[3], GizmoStyle.fill(alpha(color, a))).setAlwaysOnTop();
    }

    /** A line as dashes (a dash of about a block, a gap a little shorter), never more than about 40 of them. */
    private static void dashed(Vec3 p, Vec3 q, int color, float width) {
        double len = p.distanceTo(q);
        double dash = Math.max(0.6, len / 40.0), gap = dash * 0.8;
        Vec3 dir = q.subtract(p).normalize();
        for (double t = 0; t < len; t += dash + gap) {
            Gizmos.line(p.add(dir.scale(t)), p.add(dir.scale(Math.min(len, t + dash))), color, width).setAlwaysOnTop();
        }
    }

    /**
     * The box itself.
     *
     * @param pointed the side under the pointer or being dragged (its arrow), or null
     * @param dragging whether that side is being dragged
     */
    static void box(AABB a, Vec3 camera, boolean following, @Nullable Direction pointed, boolean dragging) {
        Vec3 centre = a.getCenter(), toCamera = camera.subtract(centre);
        for (int c = 0; c < 8; c++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((c & bit) != 0) continue;
                Vec3 p = corner(a, c), q = corner(a, c | bit);
                boolean far = p.add(q).scale(0.5).subtract(centre).dot(toCamera) < 0;
                // the top edges are brighter, so which way is up is never in doubt
                boolean top = bit != 2 && (c & 2) != 0;
                int color = top ? mix(CYAN, WHITE, 0.45) : CYAN;
                if (far) dashed(p, q, alpha(color, 0.6), 1.8f);
                else Gizmos.line(p, q, alpha(color, following ? 0.8 : 0.97), following ? 2.6f : 3.4f).setAlwaysOnTop();
            }
        }
        // the floor is lightly filled: where the box stands
        fill(face(a, Direction.DOWN, 0), CYAN, 0.10);
        if (pointed != null) fill(face(a, pointed, 0.01), CYAN, dragging ? 0.30 : 0.20);
        Handles.brackets(a, CYAN, true);
    }
}
