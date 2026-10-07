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
 * it. Built from where the placement is drawn, sized with the distance to the camera so
 * they stay easy to hit on a big build and not huge on a small one. Drawn as solid translucent 3D shapes with crisp
 * outlines (the blueprint look): a hovered handle swells and brightens, a grabbed one presses in. Only geometry and
 * drawing here: Interaction decides what a grab does.
 */
final class Handles {
    enum Kind {
        MOVE, RING
    }

    static final int X_COLOR = 0xFFFF93A8, Y_COLOR = 0xFF93EBB4, Z_COLOR = 0xFF93C9FF, RING_COLOR = 0xFFF2F9FF, WHITE = 0xFFFFFFFF;

    /** One handle: what it does, where it is drawn, and the boxes a ray is tested against. */
    static final class Handle {
        final Kind kind;
        final Direction dir;
        final Direction.Axis axis;
        final int color;
        final Vec3 from, to;
        final double[][] boxes;
        final String id;
        /** How big this handle is drawn: a face arrow is sized by its own distance from the camera, so it stays easy to hit on a huge build. */
        final double scale;

        Handle(Kind kind, Direction dir, Direction.Axis axis, int color, Vec3 from, Vec3 to, double[][] boxes, double scale) {
            this.kind = kind;
            this.dir = dir;
            this.axis = axis;
            this.color = color;
            this.from = from;
            this.to = to;
            this.boxes = boxes;
            this.scale = scale;
            this.id = kind + ":" + (axis == null ? "" : axis.getName()) + ":" + (dir == null ? "" : dir.getName());
        }
    }

    /** Ring geometry, kept beside the list because a ray meets it on a plane rather than in boxes. */
    record Ring(double cx, double cz, double y, double radius, double tolerance, double unit) {
    }

    /** How strongly each handle is hovered, 0 to 1, eased between frames so hover grows in and out instead of switching. */
    private static final Map<String, Float> HOVER = new HashMap<>(), PRESS = new HashMap<>();
    private static final double HOVER_SECONDS = 0.07;
    /** The jelly: each handle's size as a spring (position, speed) that overshoots a little and settles, and when it first showed. */
    private static final Map<String, double[]> SPRING = new HashMap<>();
    private static final Map<String, Double> BORN = new HashMap<>();
    private static final double STIFFNESS = 240, DAMPING = 13, POP_STAGGER = 0.07;
    private static double clock;

    final List<Handle> handles = new ArrayList<>();
    Ring ring;
    /** Visual box of the placement: min corner and size. */
    final double x0, y0, z0, sx, sy, sz;
    final double scale;
    /** The view direction, so arrows that point along it can be faded. */
    final Vec3 look;
    /** Distance to the camera, for text that has to stay readable. */
    final double distance;
    /** The most an arrow is scaled up for distance: further away it stays this size and the build is just far. */
    static final double MAX_ARROW_SCALE = 8.0;
    /** The smallest a handle's grab box gets, per block of distance: about 30 pixels on a 1080p screen whatever the build's size. */
    static final double PICK_PER_BLOCK = 0.025;
    /** The widest the turn ring is drawn, in blocks of radius. */
    static final double RING_MAX = 24.0;
    /** The highest above the base the sideways arrows of a tall build stand: what a player can reach from the ground. */
    static final double REACH_HEIGHT = 6.0;

    /**
     * How big the arrows of a build are drawn, in the unit the arrow shapes are built from (a face arrow is 2.8 units long).
     * An arrow is about the same size on screen at any distance (distance / 14) so it stays easy to hit on a huge build,
     * but never so big that it swamps a small one: under 16 blocks across the arrows shrink with the build (to half at 8
     * blocks and under) and are never smaller than a third of a unit.
     */
    static double arrowScale(double distance, double ref, double max) {
        double k = Math.max(0.5, Math.min(1.0, ref / 16.0));
        double lo = Math.max(0.35, Math.min(1.0, ref / 14.0));
        return Math.max(lo, Math.min(max, distance / 14.0 * k));
    }

    Handles(double x0, double y0, double z0, double sx, double sy, double sz, Vec3 camera, Vec3 look) {
        this(x0, y0, z0, sx, sy, sz, camera, look, false);
    }

    /** @param facesOnly only the six arrows, no turn ring (the Save area box is resized, not turned) */
    Handles(double x0, double y0, double z0, double sx, double sy, double sz, Vec3 camera, Vec3 look, boolean facesOnly) {
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
        double ref = Math.max(sx, Math.max(sy, sz));
        this.scale = arrowScale(dist, ref, 12.0);

        // the arrows stand on fixed places of the build and never move with the camera, so a player can walk round to the side
        // of an arrow that points at them. Each pair mirrors: the same spot on both faces. Up and down stand at the middle of
        // the footprint; the four sideways ones at the middle of their face, as high as the middle of the build, but on a tall
        // build no higher than a player can reach standing (6 above the base)
        double armY = y0 + Math.min(sy / 2, REACH_HEIGHT);
        for (Direction d : Direction.values()) {
            Vec3 face = switch (d.getAxis()) {
                case X -> new Vec3(d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? x0 + sx : x0, armY, cz);
                case Z -> new Vec3(cx, armY, d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? z0 + sz : z0);
                case Y -> new Vec3(cx, d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? y0 + sy : y0, cz);
            };
            double fd = camera.distanceTo(face);
            double s = arrowScale(fd, ref, MAX_ARROW_SCALE);
            double gap = 0.45 * s, len = 2.8 * s, spick = Math.max(0.45 * s, PICK_PER_BLOCK * fd);
            Vec3 from = face.add(d.getStepX() * gap, d.getStepY() * gap, d.getStepZ() * gap);
            Vec3 to = from.add(d.getStepX() * len, d.getStepY() * len, d.getStepZ() * len);
            handles.add(new Handle(Kind.MOVE, d, d.getAxis(), axisColor(d.getAxis()), from, to, new double[][]{box(from, to, spick)}, s));
        }

        // the ring lies under the build at its base, round its middle; on a huge build it is cut down to a size that can be
        // looked at and reached round, rather than a circle the size of the footprint
        double radius = Math.min(Math.hypot(sx, sz) / 2 + 0.9 * scale, RING_MAX);
        ring = new Ring(cx, cz, y0 + 0.05, radius, Math.max(0.45 * scale, PICK_PER_BLOCK * dist), scale);
        if (facesOnly) return;
        handles.add(new Handle(Kind.RING, null, Direction.Axis.Y, RING_COLOR, Vec3.ZERO, Vec3.ZERO, new double[0][], scale));
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
        animate(hovered, grabbed, dt, null);
    }

    /** As above; the arrows of {@code emphasis} glow a little (the axis the scroll wheel moves along). */
    void animate(Handle hovered, Handle grabbed, double dt, Direction.Axis emphasis) {
        double k = 1 - Math.exp(-dt / HOVER_SECONDS);
        clock += dt;
        boolean still = io.github.profetgit.cyanotype.ui.Motion.reduced();
        int index = 0;
        for (Handle h : handles) {
            double born = BORN.computeIfAbsent(h.id, id -> clock);
            double[] sp = SPRING.computeIfAbsent(h.id, id -> new double[]{still ? 1 : 0, 0});
            boolean up = h == hovered || h == grabbed;
            double target = clock - born < index * POP_STAGGER && !still ? 0 : 1 + (up ? 0.22 : 0) - (h == grabbed ? 0.1 : 0);
            index++;
            if (still) {
                sp[0] = target;
                sp[1] = 0;
            } else {
                double left = Math.min(dt, 0.1);
                while (left > 0) {
                    double step = Math.min(left, 1 / 240.0);
                    sp[1] += (STIFFNESS * (target - sp[0]) - DAMPING * sp[1]) * step;
                    sp[0] += sp[1] * step;
                    left -= step;
                }
            }
            float hv = HOVER.getOrDefault(h.id, 0f), pr = PRESS.getOrDefault(h.id, 0f);
            float glow = emphasis != null && h.kind == Kind.MOVE && h.axis == emphasis ? 0.5f : 0f;
            float ht = h == hovered || h == grabbed ? 1f : glow, pt = h == grabbed ? 1f : 0f;
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
        SPRING.clear();
        BORN.clear();
    }

    /** Draws every handle. */
    void emit() {
        for (Handle h : handles) {
            float e = HOVER.getOrDefault(h.id, 0f), p = PRESS.getOrDefault(h.id, 0f);
            // the spring is the size: it pops out when editing starts, swells on hover and gives a little when grabbed
            double[] sp = SPRING.get(h.id);
            double k = sp == null ? 1 + 0.22 * e - 0.1 * p : sp[0];
            if (k < 0.04) continue;
            boolean still = io.github.profetgit.cyanotype.ui.Motion.reduced();
            switch (h.kind) {
                case MOVE -> {
                    Vec3 dir = h.to.subtract(h.from).normalize();
                    double len = h.from.distanceTo(h.to);
                    // it breathes along its axis, each arrow in its own time
                    double bob = still ? 0 : 0.1 * h.scale * Math.sin(clock * 2.4 + phase(h)) * (1 - 0.7 * Math.min(1, e + p));
                    double grow = Math.min(1, k);
                    arrow(h.from.add(dir.scale(bob + len * (1 - grow) * 0.4)), dir, len * grow, Math.max(k, 0.2), h.color, e, (float) Math.min(1, k * 1.4), h.scale);
                }
                case RING -> ring(e, p, (float) Math.min(1, k * 1.4), still ? 0 : clock * 0.18);
            }
        }
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

    /** Where the sun is for the soft shading of the round shapes (a fixed direction in the world, so they read as lit solids). */
    private static final Vec3 SUN = new Vec3(0.35, 0.8, 0.5).normalize();
    /**
     * A solid arrow: a square shaft and a pyramid head, each face filled with soft translucent colour and shaded as if lit
     * from above, with a thin light edge so the shape stays crisp over any background. {@code length} is the whole arrow
     * from {@code from} along the axis-aligned {@code dir}; {@code k} scales its thickness (the swell on hover).
     */
    private void arrow(Vec3 from, Vec3 dir, double length, double k, int color, float hover, float visible, double scale) {
        double unit = scale * k;
        double w = 0.21 * unit, headHalf = 0.58 * unit, headLen = Math.min(1.25 * unit, length * 0.45);
        Vec3 headBase = from.add(dir.scale(length - headLen));
        Vec3 apex = from.add(dir.scale(length));
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
        int body = mix(color, WHITE, 0.12f + 0.35f * hover);
        double opacity = (0.66 + 0.26 * hover) * visible;
        int edge = alpha(mix(color, WHITE, 0.6f + 0.4f * hover), (0.55 + 0.4 * hover) * visible);
        float ew = 1.5f + 1.2f * hover;
        // shaft: four corners at the base and at the head
        Vec3[] sb = square(from, u, v, w), sh = square(headBase, u, v, w);
        Vec3[] sideNormal = {u, v, u.scale(-1), v.scale(-1)};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            face(sb[i], sb[j], sh[j], sh[i], sideNormal[i].add(sideNormal[j]).normalize(), body, opacity, edge, ew);
        }
        face(sb[0], sb[1], sb[2], sb[3], dir.scale(-1), body, opacity, edge, ew);
        // head: a flat shoulder under a four-sided point
        Vec3[] hb = square(headBase, u, v, headHalf);
        face(hb[0], hb[1], hb[2], hb[3], dir.scale(-1), body, opacity * 0.85, edge, ew);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            Vec3 out = sideNormal[i].add(sideNormal[j]).normalize();
            face(hb[i], hb[j], apex, apex, out.scale(headLen).add(dir.scale(headHalf * 1.4)).normalize(), body, opacity, edge, ew);
        }
    }

    /** The four corners of a square of half-width {@code r} round {@code c}, in the plane across the arrow. */
    private static Vec3[] square(Vec3 c, Vec3 u, Vec3 v, double r) {
        return new Vec3[]{c.add(u.scale(r)).add(v.scale(r)), c.add(u.scale(-r)).add(v.scale(r)), c.add(u.scale(-r)).add(v.scale(-r)), c.add(u.scale(r)).add(v.scale(-r))};
    }

    /** One filled face, lit by {@link #SUN} from its outward {@code normal}, with a thin light edge. */
    private static void face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 normal, int body, double opacity, int edge, float edgeWidth) {
        double light = 0.5 + 0.5 * normal.dot(SUN);
        int shaded = light >= 0.5 ? mix(body, WHITE, (float) ((light - 0.5) * 0.7)) : mix(body, 0xFF3B4A66, (float) ((0.5 - light) * 0.5));
        Gizmos.rect(a, b, c, d, GizmoStyle.strokeAndFill(edge, edgeWidth, alpha(shaded, opacity))).setAlwaysOnTop();
    }

    /** The turn ring: a soft translucent band at the base with four rounded arrows showing clockwise. */
    private void ring(float hover, float press, float visible, double drift) {
        double band = 0.5 * ring.unit * (1 + 0.25 * hover - 0.1 * press);
        double r0 = ring.radius - band / 2, r1 = ring.radius + band / 2;
        int stroke = alpha(mix(RING_COLOR, WHITE, 0.2f + 0.8f * hover), visible);
        float sw = 2.2f + 2.0f * hover;
        int fill = alpha(RING_COLOR, (0.28 + 0.35 * hover) * visible);
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
        double w = 0.55 * ring.unit * (1 + 0.2 * hover), tipAngle = 0.8 * ring.unit / ring.radius;
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2 + Math.PI / 4 + drift;
            Vec3 tip = new Vec3(ring.cx + Math.cos(a + tipAngle) * ring.radius, ring.y, ring.cz + Math.sin(a + tipAngle) * ring.radius);
            Vec3 l = new Vec3(ring.cx + Math.cos(a) * (ring.radius - w), ring.y, ring.cz + Math.sin(a) * (ring.radius - w));
            Vec3 r = new Vec3(ring.cx + Math.cos(a) * (ring.radius + w), ring.y, ring.cz + Math.sin(a) * (ring.radius + w));
            Gizmos.rect(l, tip, tip, r, GizmoStyle.strokeAndFill(stroke, sw, alpha(WHITE, (0.55 + 0.35 * hover) * visible))).setAlwaysOnTop();
        }
    }

    private static double phase(Handle h) {
        return (h.id.hashCode() & 0xFFFF) / 65535.0 * Math.PI * 2;
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

    // ---- layer focus

    /** The frame of the layers being shown: a rectangle at the bottom and at the top of the range, joined at the corners. */
    static void layerBorder(AABB footprint, double yLo, double yHi, int color) {
        int c = alpha(mix(color, WHITE, 0.35f), 0.95);
        double x0 = footprint.minX, x1 = footprint.maxX, z0 = footprint.minZ, z1 = footprint.maxZ;
        for (double y : new double[]{yLo, yHi}) {
            Vec3 a = new Vec3(x0, y, z0), b = new Vec3(x1, y, z0), d = new Vec3(x1, y, z1), e = new Vec3(x0, y, z1);
            Gizmos.line(a, b, c, 3.2f);
            Gizmos.line(b, d, c, 3.2f);
            Gizmos.line(d, e, c, 3.2f);
            Gizmos.line(e, a, c, 3.2f);
        }
        GizmoStyle plane = GizmoStyle.fill(alpha(color, 0.10));
        Gizmos.rect(new Vec3(x0, yLo, z0), new Vec3(x1, yLo, z0), new Vec3(x1, yLo, z1), new Vec3(x0, yLo, z1), plane);
        for (double[] corner : new double[][]{{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}}) {
            Gizmos.line(new Vec3(corner[0], yLo, corner[1]), new Vec3(corner[0], yHi, corner[1]), alpha(c, 0.55), 1.8f);
        }
    }

    // ---- next block marker

    /** An animated marker on the next block to build: a pulsing outline, a beam rising from it and a label. */
    static void nextMarker(Vec3 cell, String what, double distance, double time) {
        double pulse = 0.5 + 0.5 * Math.sin(time * 5.0);
        AABB box = new AABB(cell.x - 0.02 - 0.05 * pulse, cell.y - 0.02 - 0.05 * pulse, cell.z - 0.02 - 0.05 * pulse,
            cell.x + 1.02 + 0.05 * pulse, cell.y + 1.02 + 0.05 * pulse, cell.z + 1.02 + 0.05 * pulse);
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(alpha(WHITE, 0.7 + 0.3 * pulse), 3.5f, alpha(0xFFFFD866, 0.16 + 0.16 * pulse))).setAlwaysOnTop();
        Vec3 top = new Vec3(cell.x + 0.5, cell.y + 1.0, cell.z + 0.5);
        double beam = 5 + 2 * pulse;
        Gizmos.line(top, top.add(0, beam, 0), alpha(0xFFFFD866, 0.85), 3.0f).setAlwaysOnTop();
        // a small chevron at the end of the beam, pointing down at the block
        double h = 0.5;
        Vec3 tip = top.add(0, 0.6, 0), l = top.add(-h, 1.4, 0), r = top.add(h, 1.4, 0), f = top.add(0, 1.4, -h), b = top.add(0, 1.4, h);
        Gizmos.line(tip, l, alpha(0xFFFFD866, 0.9), 3.0f).setAlwaysOnTop();
        Gizmos.line(tip, r, alpha(0xFFFFD866, 0.9), 3.0f).setAlwaysOnTop();
        Gizmos.line(tip, f, alpha(0xFFFFD866, 0.9), 3.0f).setAlwaysOnTop();
        Gizmos.line(tip, b, alpha(0xFFFFD866, 0.9), 3.0f).setAlwaysOnTop();
        label(top.add(0, beam + 0.7 + 0.04 * distance, 0), what, labelScale(distance, 0.9, 0.06), WHITE, alpha(0xFFFFD866, 1.0));
    }

    // ---- placement outline

    /** The placement's box as corner brackets (the blueprint look) over a faint full outline for the one being edited. */
    static void outline(AABB b, int color, boolean strong) {
        brackets(b, color, strong);
        if (strong) Gizmos.cuboid(b, GizmoStyle.stroke(alpha(color, 0.28), 1.5f));
    }

    /** Only the corner brackets of {@link #outline}. */
    static void brackets(AABB b, int color, boolean strong) {
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
    }
}
