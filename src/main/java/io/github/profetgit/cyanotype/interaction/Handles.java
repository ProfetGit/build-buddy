package io.github.profetgit.cyanotype.interaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The grabbable parts of the placement being edited: six arrows that move it along an axis, a ring at its base that turns
 * it, and two flip arrows above it. Built from where the placement is drawn, sized with the distance to the camera so
 * they stay easy to hit on a big build and not huge on a small one. Drawn as solid translucent 3D shapes with crisp
 * outlines (the blueprint look): a hovered handle swells and brightens, a grabbed one presses in. Only geometry and
 * drawing here: Interaction decides what a grab does.
 */
final class Handles {
    enum Kind {
        MOVE, RING, FLIP
    }

    static final int X_COLOR = 0xFFFF6B6B, Y_COLOR = 0xFF6BE58F, Z_COLOR = 0xFF6BB8FF, RING_COLOR = 0xFFE8F6FF, WHITE = 0xFFFFFFFF;

    /** One handle: what it does, where it is drawn, and the boxes a ray is tested against. */
    static final class Handle {
        final Kind kind;
        final Direction dir;
        final Direction.Axis axis;
        final int color;
        final Vec3 from, to;
        final double[][] boxes;
        final String id;

        Handle(Kind kind, Direction dir, Direction.Axis axis, int color, Vec3 from, Vec3 to, double[][] boxes) {
            this.kind = kind;
            this.dir = dir;
            this.axis = axis;
            this.color = color;
            this.from = from;
            this.to = to;
            this.boxes = boxes;
            this.id = kind == Kind.FLIP ? "FLIP" : kind + ":" + (axis == null ? "" : axis.getName()) + ":" + (dir == null ? "" : dir.getName());
        }
    }

    /** Ring geometry, kept beside the list because a ray meets it on a plane rather than in boxes. */
    record Ring(double cx, double cz, double y, double radius, double tolerance) {
    }

    /** How strongly each handle is hovered, 0 to 1, eased between frames so hover grows in and out instead of switching. */
    private static final Map<String, Float> HOVER = new HashMap<>(), PRESS = new HashMap<>();
    private static final double HOVER_SECONDS = 0.07;

    final List<Handle> handles = new ArrayList<>();
    Ring ring;
    /** Visual box of the placement: min corner and size. */
    final double x0, y0, z0, sx, sy, sz;
    final double scale;
    /** The view direction, so arrows that point along it can be faded. */
    final Vec3 look;
    /** Distance to the camera, for text that has to stay readable. */
    final double distance;
    private static Direction.Axis flipAxis = Direction.Axis.X;

    Handles(double x0, double y0, double z0, double sx, double sy, double sz, Vec3 camera, Vec3 look) {
        this.look = look;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        double cx = x0 + sx / 2, cy = y0 + sy / 2, cz = z0 + sz / 2;
        double dist = camera.distanceTo(new Vec3(cx, cy, cz));
        this.distance = dist;
        this.scale = Math.max(1.0, Math.min(12.0, dist / 14.0));
        double gap = 0.45 * scale, len = 2.8 * scale, pick = 0.45 * scale;

        double[] half = {sx / 2, sy / 2, sz / 2};
        for (Direction d : Direction.values()) {
            Vec3 face = new Vec3(cx + d.getStepX() * half[0], cy + d.getStepY() * half[1], cz + d.getStepZ() * half[2]);
            Vec3 from = face.add(d.getStepX() * gap, d.getStepY() * gap, d.getStepZ() * gap);
            Vec3 to = from.add(d.getStepX() * len, d.getStepY() * len, d.getStepZ() * len);
            handles.add(new Handle(Kind.MOVE, d, d.getAxis(), axisColor(d.getAxis()), from, to, new double[][]{box(from, to, pick)}));
        }

        double radius = Math.hypot(sx, sz) / 2 + 0.9 * scale;
        ring = new Ring(cx, cz, y0 + 0.05, radius, 0.45 * scale);
        handles.add(new Handle(Kind.RING, null, Direction.Axis.Y, RING_COLOR, Vec3.ZERO, Vec3.ZERO, new double[0][]));

        // one flip arrow, across the view: its axis follows where the player looks (like ctrl+scroll), with some
        // hysteresis so it does not jump back and forth at 45 degrees
        double ax = Math.abs(look.x), az = Math.abs(look.z);
        if (ax > az * 1.15) flipAxis = Direction.Axis.Z;
        else if (az > ax * 1.15) flipAxis = Direction.Axis.X;
        double fy = y0 + sy + 1.4 * scale, span = 1.5 * scale, off = 3.4 * scale;
        Vec3 fa = flipAxis == Direction.Axis.X ? new Vec3(cx + off - span, fy, cz) : new Vec3(cx, fy, cz + off - span);
        Vec3 fb = flipAxis == Direction.Axis.X ? new Vec3(cx + off + span, fy, cz) : new Vec3(cx, fy, cz + off + span);
        handles.add(new Handle(Kind.FLIP, null, flipAxis, axisColor(flipAxis), fa, fb, new double[][]{box(fa, fb, pick)}));
    }

    static int axisColor(Direction.Axis a) {
        return a == Direction.Axis.X ? X_COLOR : a == Direction.Axis.Y ? Y_COLOR : Z_COLOR;
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
            if (endOn(h)) continue;
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

    // ---- drawing

    /** Eases every handle's hover and press toward where the mouse is now. {@code grabbed} is the handle being dragged, or null. */
    void animate(Handle hovered, Handle grabbed, double dt) {
        double k = 1 - Math.exp(-dt / HOVER_SECONDS);
        for (Handle h : handles) {
            float hv = HOVER.getOrDefault(h.id, 0f), pr = PRESS.getOrDefault(h.id, 0f);
            float ht = h == hovered || h == grabbed ? 1f : 0f, pt = h == grabbed ? 1f : 0f;
            hv += (ht - hv) * (float) k;
            pr += (pt - pr) * (float) k;
            if (Math.abs(ht - hv) < 0.004f) hv = ht;
            if (Math.abs(pt - pr) < 0.004f) pr = pt;
            HOVER.put(h.id, hv);
            PRESS.put(h.id, pr);
        }
    }

    static void forget() {
        HOVER.clear();
        PRESS.clear();
    }

    /** Draws every handle. */
    void emit() {
        for (Handle h : handles) {
            float e = HOVER.getOrDefault(h.id, 0f), p = PRESS.getOrDefault(h.id, 0f);
            // hover swells it, grabbing presses it back in a little
            double k = 1 + 0.22 * e - 0.1 * p;
            switch (h.kind) {
                case MOVE -> {
                    if (endOn(h)) continue;
                    Vec3 dir = h.to.subtract(h.from).normalize();
                    arrow(h.from, dir, h.from.distanceTo(h.to), k, h.color, e, along(dir, e));
                }
                case FLIP -> {
                    Vec3 mid = h.from.add(h.to).scale(0.5);
                    Vec3 dir = h.to.subtract(h.from).normalize();
                    double half = h.from.distanceTo(h.to) / 2;
                    arrow(mid, dir, half, k, h.color, e, 1f);
                    arrow(mid, dir.scale(-1), half, k, h.color, e, 1f);
                }
                case RING -> ring(e, p);
            }
        }
    }

    /** How visible an arrow is: one pointing along the view is seen end-on and only clutters, so it fades unless it is hovered. */
    private float along(Vec3 dir, float hover) {
        double c = Math.abs(dir.x * look.x + dir.y * look.y + dir.z * look.z);
        double fade = c < 0.6 ? 1 : 1 - Math.min(1, (c - 0.6) / 0.26);
        return (float) (fade + (1 - fade) * hover);
    }

    /** An arrow seen nearly end-on is not drawn, so it cannot be grabbed either: turn the view to reach that axis. */
    private boolean endOn(Handle h) {
        if (h.kind != Kind.MOVE) return false;
        Vec3 dir = h.to.subtract(h.from).normalize();
        return Math.abs(dir.x * look.x + dir.y * look.y + dir.z * look.z) > 0.88;
    }

    /** Set each frame by Interaction: the camera's turn, so labels can face it. */
    static org.joml.Quaternionf camera = new org.joml.Quaternionf();
    static final int CHIP = 0xDD0E2A47;

    /**
     * Text on a small navy panel with a thin outline, facing the camera (the blueprint look), readable over sky, grass
     * or a dark cave. The text hangs below {@code pos}, centred on it.
     */
    static void label(Vec3 pos, String text, float scale, int textColor, int outline) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        double w = mc.font.width(text) * scale / 16.0, h = 8 * scale / 16.0, pad = 0.14 * scale;
        org.joml.Vector3f r = new org.joml.Vector3f(1, 0, 0).rotate(camera), u = new org.joml.Vector3f(0, 1, 0).rotate(camera), f = new org.joml.Vector3f(0, 0, -1).rotate(camera);
        // a hair behind the text so the two never fight
        Vec3 o = pos.add(f.x * 0.02 * scale, f.y * 0.02 * scale, f.z * 0.02 * scale);
        Vec3 right = new Vec3(r.x, r.y, r.z), up = new Vec3(u.x, u.y, u.z);
        Vec3 tl = o.add(right.scale(-(w / 2 + pad))).add(up.scale(pad * 0.6));
        Vec3 tr = o.add(right.scale(w / 2 + pad)).add(up.scale(pad * 0.6));
        Vec3 br = o.add(right.scale(w / 2 + pad)).add(up.scale(-(h + pad * 0.6)));
        Vec3 bl = o.add(right.scale(-(w / 2 + pad))).add(up.scale(-(h + pad * 0.6)));
        Gizmos.rect(tl, bl, br, tr, GizmoStyle.strokeAndFill(outline, 1.6f, CHIP)).setAlwaysOnTop();
        Gizmos.billboardText(text, pos, net.minecraft.gizmos.TextGizmo.Style.forColorAndCentered(textColor).withScale(scale)).setAlwaysOnTop();
    }

    /** World-space text size that stays readable at a distance (the text is scale / 2 blocks tall). */
    static float labelScale(double distance, double base, double perBlock) {
        return (float) Math.max(base, distance * perBlock);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static int alpha(int argb, double a) {
        return ((int) Math.max(0, Math.min(255, a * 255)) << 24) | (argb & 0xFFFFFF);
    }

    /**
     * A solid arrow: a square shaft and a pyramid head, translucent fill under a crisp outline. {@code length} is the
     * whole arrow from {@code from} along the axis-aligned {@code dir}; {@code k} scales its thickness (the swell on hover).
     */
    private void arrow(Vec3 from, Vec3 dir, double length, double k, int color, float hover, float visible) {
        double unit = scale * k;
        double w = 0.15 * unit, headHalf = 0.46 * unit, headLen = Math.min(1.2 * unit, length * 0.45);
        Vec3 headBase = from.add(dir.scale(length - headLen));
        Vec3 apex = from.add(dir.scale(length));
        int stroke = mix(color, WHITE, 0.35f + 0.65f * hover);
        float sw = 2.4f + 1.8f * hover;
        stroke = alpha(stroke, visible);
        int fill = alpha(color, (0.5 + 0.38 * hover) * visible);
        int dim = alpha(color, (0.32 + 0.3 * hover) * visible);

        // shaft
        AABB shaft = new AABB(
            Math.min(from.x, headBase.x) - (dir.x == 0 ? w : 0), Math.min(from.y, headBase.y) - (dir.y == 0 ? w : 0), Math.min(from.z, headBase.z) - (dir.z == 0 ? w : 0),
            Math.max(from.x, headBase.x) + (dir.x == 0 ? w : 0), Math.max(from.y, headBase.y) + (dir.y == 0 ? w : 0), Math.max(from.z, headBase.z) + (dir.z == 0 ? w : 0));
        Gizmos.cuboid(shaft, GizmoStyle.strokeAndFill(stroke, sw * 0.7f, fill)).setAlwaysOnTop();

        // head: the two axes across the arrow
        Vec3 u, v;
        if (dir.x != 0) {
            u = new Vec3(0, 1, 0);
            v = new Vec3(0, 0, 1);
        } else if (dir.y != 0) {
            u = new Vec3(1, 0, 0);
            v = new Vec3(0, 0, 1);
        } else {
            u = new Vec3(1, 0, 0);
            v = new Vec3(0, 1, 0);
        }
        Vec3 c0 = headBase.add(u.scale(headHalf)).add(v.scale(headHalf));
        Vec3 c1 = headBase.add(u.scale(headHalf)).add(v.scale(-headHalf));
        Vec3 c2 = headBase.add(u.scale(-headHalf)).add(v.scale(-headHalf));
        Vec3 c3 = headBase.add(u.scale(-headHalf)).add(v.scale(headHalf));
        GizmoStyle side = GizmoStyle.strokeAndFill(stroke, sw, fill);
        Gizmos.rect(c0, c1, apex, apex, side).setAlwaysOnTop();
        Gizmos.rect(c1, c2, apex, apex, side).setAlwaysOnTop();
        Gizmos.rect(c2, c3, apex, apex, side).setAlwaysOnTop();
        Gizmos.rect(c3, c0, apex, apex, side).setAlwaysOnTop();
        Gizmos.rect(c0, c1, c2, c3, GizmoStyle.strokeAndFill(stroke, sw, dim)).setAlwaysOnTop();
    }

    /** The turn ring: a flat translucent band at the base with a crisp edge on both sides and four chevrons showing clockwise. */
    private void ring(float hover, float press) {
        double band = 0.5 * scale * (1 + 0.25 * hover - 0.1 * press);
        double r0 = ring.radius - band / 2, r1 = ring.radius + band / 2;
        int stroke = mix(RING_COLOR, WHITE, 0.2f + 0.8f * hover);
        float sw = 2.2f + 2.0f * hover;
        int fill = alpha(RING_COLOR, 0.28 + 0.35 * hover);
        int n = 72;
        Vec3[] in = new Vec3[n + 1], out = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double a = i * 2 * Math.PI / n;
            double c = Math.cos(a), s = Math.sin(a);
            in[i] = new Vec3(ring.cx + c * r0, ring.y, ring.cz + s * r0);
            out[i] = new Vec3(ring.cx + c * r1, ring.y, ring.cz + s * r1);
        }
        GizmoStyle band0 = GizmoStyle.fill(fill);
        for (int i = 0; i < n; i++) {
            Gizmos.rect(in[i], out[i], out[i + 1], in[i + 1], band0).setAlwaysOnTop();
            Gizmos.line(in[i], in[i + 1], stroke, sw).setAlwaysOnTop();
            Gizmos.line(out[i], out[i + 1], stroke, sw).setAlwaysOnTop();
        }
        // chevrons pointing clockwise (toward +z from +x, as seen from above)
        double w = 0.55 * scale * (1 + 0.2 * hover), tipAngle = 0.8 * scale / ring.radius;
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2 + Math.PI / 4;
            Vec3 tip = new Vec3(ring.cx + Math.cos(a + tipAngle) * ring.radius, ring.y, ring.cz + Math.sin(a + tipAngle) * ring.radius);
            Vec3 l = new Vec3(ring.cx + Math.cos(a) * (ring.radius - w), ring.y, ring.cz + Math.sin(a) * (ring.radius - w));
            Vec3 r = new Vec3(ring.cx + Math.cos(a) * (ring.radius + w), ring.y, ring.cz + Math.sin(a) * (ring.radius + w));
            Gizmos.rect(l, tip, tip, r, GizmoStyle.strokeAndFill(stroke, sw, alpha(WHITE, 0.55 + 0.35 * hover))).setAlwaysOnTop();
        }
    }

    // ---- drag guides

    /** While an arrow is dragged: the line it travels along with a tick at every block, the start outlined, and the distance. */
    static void moveGuide(Vec3 center, Direction.Axis axis, int steps, int color, AABB start, Vec3 tip, double distance) {
        Vec3 a = axisVec(axis);
        int reach = 16;
        Gizmos.line(center.subtract(a.scale(reach)), center.add(a.scale(reach)), alpha(color, 0.6), 2.0f).setAlwaysOnTop();
        Vec3 side = axis == Direction.Axis.Y ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        for (int k = -reach; k <= reach; k++) {
            boolean here = k == steps, five = k % 5 == 0;
            double half = here ? 0.55 : five ? 0.4 : 0.22;
            Vec3 p = center.add(a.scale(k));
            Gizmos.line(p.subtract(side.scale(half)), p.add(side.scale(half)), here ? WHITE : alpha(color, five ? 0.9 : 0.6), here ? 4.0f : 2.0f).setAlwaysOnTop();
        }
        Gizmos.cuboid(start, GizmoStyle.stroke(alpha(WHITE, 0.4), 1.6f)).setAlwaysOnTop();
        String text = steps == 0 ? "0" : (steps > 0 ? "+" : "") + steps;
        label(tip.add(0, 0.12 * distance + 0.9, 0), text, labelScale(distance, 1.1, 0.1), WHITE, alpha(color, 1.0));
    }

    /** While the ring is dragged: the sweep from where it started to the snapped angle, with its size in degrees. */
    static void turnGuide(double cx, double y, double cz, double radius, double startAngle, int turns, double distance) {
        double end = startAngle + turns * Math.PI / 2;
        int n = Math.max(1, Math.abs(turns) * 18);
        Vec3 c = new Vec3(cx, y, cz);
        GizmoStyle sweep = GizmoStyle.fill(alpha(WHITE, 0.22));
        for (int i = 0; i < n; i++) {
            double a0 = startAngle + (end - startAngle) * i / n, a1 = startAngle + (end - startAngle) * (i + 1) / n;
            Gizmos.rect(c, new Vec3(cx + Math.cos(a0) * radius, y, cz + Math.sin(a0) * radius),
                new Vec3(cx + Math.cos(a1) * radius, y, cz + Math.sin(a1) * radius), c, sweep).setAlwaysOnTop();
        }
        Gizmos.line(c, new Vec3(cx + Math.cos(startAngle) * radius, y, cz + Math.sin(startAngle) * radius), alpha(WHITE, 0.5), 2.0f).setAlwaysOnTop();
        Gizmos.line(c, new Vec3(cx + Math.cos(end) * radius, y, cz + Math.sin(end) * radius), WHITE, 3.5f).setAlwaysOnTop();
        String text = turns == 0 ? "0" : (turns > 0 ? "" : "-") + Math.abs(turns * 90) + "°";
        Vec3 label = new Vec3(cx + Math.cos(end) * (radius + 1.2), y + 0.9, cz + Math.sin(end) * (radius + 1.2));
        label(label, text, labelScale(distance, 1.1, 0.1), WHITE, alpha(WHITE, 1.0));
    }

    private static Vec3 axisVec(Direction.Axis a) {
        return switch (a) {
            case X -> new Vec3(1, 0, 0);
            case Y -> new Vec3(0, 1, 0);
            case Z -> new Vec3(0, 0, 1);
        };
    }

    // ---- placement outline

    /** The placement's box as corner brackets (the blueprint look) over a faint full outline for the one being edited. */
    static void outline(AABB b, int color, boolean strong) {
        double[] size = {b.maxX - b.minX, b.maxY - b.minY, b.maxZ - b.minZ};
        double[] len = {Math.max(0.6, Math.min(2.5, size[0] * 0.28)), Math.max(0.6, Math.min(2.5, size[1] * 0.28)), Math.max(0.6, Math.min(2.5, size[2] * 0.28))};
        int bracket = strong ? color : alpha(color, 0.55);
        float width = strong ? 4.0f : 2.6f;
        for (int ix = 0; ix < 2; ix++) {
            for (int iy = 0; iy < 2; iy++) {
                for (int iz = 0; iz < 2; iz++) {
                    Vec3 c = new Vec3(ix == 0 ? b.minX : b.maxX, iy == 0 ? b.minY : b.maxY, iz == 0 ? b.minZ : b.maxZ);
                    Gizmos.line(c, c.add(ix == 0 ? len[0] : -len[0], 0, 0), bracket, width);
                    Gizmos.line(c, c.add(0, iy == 0 ? len[1] : -len[1], 0), bracket, width);
                    Gizmos.line(c, c.add(0, 0, iz == 0 ? len[2] : -len[2]), bracket, width);
                }
            }
        }
        if (strong) Gizmos.cuboid(b, GizmoStyle.stroke(alpha(color, 0.28), 1.5f));
    }
}
