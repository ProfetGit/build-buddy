package io.github.profetgit.cyanotype.interaction;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * The grabbable parts of the placement being edited: six arrows that move it along an axis, a ring at its base that turns
 * it, and two flip arrows above it. Built from where the placement is drawn, sized with the distance to the camera so
 * they stay easy to hit on a big build and not huge on a small one. Only geometry here: Interaction decides what a grab does.
 */
final class Handles {
    enum Kind {
        MOVE, RING, FLIP
    }

    static final int X_COLOR = 0xFFFF8A8A, Y_COLOR = 0xFF9BF59B, Z_COLOR = 0xFF8AC3FF, RING_COLOR = 0xFFFFFFFF, HOVER = 0xFFFFFFFF;

    /** One handle: what it does, where it is drawn, and the boxes a ray is tested against. */
    static final class Handle {
        final Kind kind;
        final Direction dir;
        final Direction.Axis axis;
        final int color;
        final Vec3 from, to;
        final double[][] boxes;

        Handle(Kind kind, Direction dir, Direction.Axis axis, int color, Vec3 from, Vec3 to, double[][] boxes) {
            this.kind = kind;
            this.dir = dir;
            this.axis = axis;
            this.color = color;
            this.from = from;
            this.to = to;
            this.boxes = boxes;
        }
    }

    /** Ring geometry, kept beside the list because a ray meets it on a plane rather than in boxes. */
    record Ring(double cx, double cz, double y, double radius, double tolerance) {
    }

    final List<Handle> handles = new ArrayList<>();
    Ring ring;
    /** Visual box of the placement: min corner and size. */
    final double x0, y0, z0, sx, sy, sz;
    final double scale;

    Handles(double x0, double y0, double z0, double sx, double sy, double sz, Vec3 camera) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        double cx = x0 + sx / 2, cy = y0 + sy / 2, cz = z0 + sz / 2;
        double dist = camera.distanceTo(new Vec3(cx, cy, cz));
        this.scale = Math.max(1.0, Math.min(12.0, dist / 14.0));
        double gap = 0.35 * scale, len = 2.2 * scale, pick = 0.5 * scale;

        double[] half = {sx / 2, sy / 2, sz / 2};
        for (Direction d : Direction.values()) {
            int a = d.getAxis().ordinal();
            Vec3 face = new Vec3(cx + d.getStepX() * half[0], cy + d.getStepY() * half[1], cz + d.getStepZ() * half[2]);
            Vec3 from = face.add(d.getStepX() * gap, d.getStepY() * gap, d.getStepZ() * gap);
            Vec3 to = from.add(d.getStepX() * len, d.getStepY() * len, d.getStepZ() * len);
            int color = d.getAxis() == Direction.Axis.X ? X_COLOR : d.getAxis() == Direction.Axis.Y ? Y_COLOR : Z_COLOR;
            handles.add(new Handle(Kind.MOVE, d, d.getAxis(), color, from, to, new double[][]{box(from, to, pick)}));
        }

        double radius = Math.hypot(sx, sz) / 2 + 0.6 * scale;
        ring = new Ring(cx, cz, y0 + 0.05, radius, 0.5 * scale);
        handles.add(new Handle(Kind.RING, null, Direction.Axis.Y, RING_COLOR, Vec3.ZERO, Vec3.ZERO, new double[0][]));

        double fy = y0 + sy + 1.4 * scale, span = 1.1 * scale, off = 2.2 * scale;
        Vec3 xa = new Vec3(cx - off - span, fy, cz), xb = new Vec3(cx - off + span, fy, cz);
        Vec3 za = new Vec3(cx + off, fy, cz - span), zb = new Vec3(cx + off, fy, cz + span);
        handles.add(new Handle(Kind.FLIP, null, Direction.Axis.X, X_COLOR, xa, xb, new double[][]{box(xa, xb, pick)}));
        handles.add(new Handle(Kind.FLIP, null, Direction.Axis.Z, Z_COLOR, za, zb, new double[][]{box(za, zb, pick)}));
    }

    private static double[] box(Vec3 a, Vec3 b, double r) {
        return new double[]{
            Math.min(a.x, b.x) - r, Math.min(a.y, b.y) - r, Math.min(a.z, b.z) - r,
            Math.max(a.x, b.x) + r, Math.max(a.y, b.y) + r, Math.max(a.z, b.z) + r};
    }

    /** Nearest handle a ray hits, or null. The ring is hit where the ray crosses the plane of its base. */
    Handle pick(Vec3 o, Vec3 d) {
        Handle best = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (Handle h : handles) {
            double t = Double.NaN;
            if (h.kind == Kind.RING) {
                double plane = HandleMath.rayPlaneY(o.y, d.y, ring.y);
                if (!Double.isNaN(plane)) {
                    double px = o.x + d.x * plane, pz = o.z + d.z * plane;
                    if (Math.abs(Math.hypot(px - ring.cx, pz - ring.cz) - ring.radius) <= ring.tolerance) t = plane;
                }
            } else {
                for (double[] b : h.boxes) {
                    double tb = HandleMath.rayBox(o.x, o.y, o.z, d.x, d.y, d.z, b[0], b[1], b[2], b[3], b[4], b[5]);
                    if (!Double.isNaN(tb) && (Double.isNaN(t) || tb < t)) t = tb;
                }
            }
            if (!Double.isNaN(t) && t < bestT) {
                bestT = t;
                best = h;
            }
        }
        return best;
    }

    /** Draws every handle; the one under the mouse (or being dragged) in white and thicker. */
    void emit(Handle hot) {
        for (Handle h : handles) {
            boolean on = h == hot;
            int color = on ? HOVER : h.color;
            float width = on ? 5.0f : 3.0f;
            switch (h.kind) {
                case MOVE -> Gizmos.arrow(h.from, h.to, color, width).setAlwaysOnTop();
                case FLIP -> {
                    Vec3 mid = h.from.add(h.to).scale(0.5);
                    Gizmos.arrow(mid, h.from, color, width).setAlwaysOnTop();
                    Gizmos.arrow(mid, h.to, color, width).setAlwaysOnTop();
                }
                case RING -> emitRing(on ? HOVER : 0xCCFFFFFF, on ? 5.0f : 3.0f);
            }
        }
    }

    private void emitRing(int color, float width) {
        int n = 72;
        Vec3 prev = null;
        for (int i = 0; i <= n; i++) {
            double a = i * 2 * Math.PI / n;
            Vec3 p = new Vec3(ring.cx + Math.cos(a) * ring.radius, ring.y, ring.cz + Math.sin(a) * ring.radius);
            if (prev != null && (i / 2) % 2 == 0) Gizmos.line(prev, p, color, width).setAlwaysOnTop();
            prev = p;
        }
        // four arrowheads show which way is clockwise
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2 + Math.PI / 4;
            double step = 0.18 / ring.radius * Math.max(1, scale);
            Vec3 p0 = new Vec3(ring.cx + Math.cos(a) * ring.radius, ring.y, ring.cz + Math.sin(a) * ring.radius);
            Vec3 p1 = new Vec3(ring.cx + Math.cos(a + step * 4) * ring.radius, ring.y, ring.cz + Math.sin(a + step * 4) * ring.radius);
            Gizmos.arrow(p0, p1, color, width).setAlwaysOnTop();
        }
    }
}
