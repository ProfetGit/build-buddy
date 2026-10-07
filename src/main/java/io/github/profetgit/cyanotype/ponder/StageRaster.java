package io.github.profetgit.cyanotype.ponder;

import io.github.profetgit.cyanotype.ui.BlockLook;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Draws a {@link Snapshot} of a lesson, in software, into an ARGB picture: the lesson's blocks (real textures through
 * {@link BlockLook}, so the player's resource pack shows), a ghost group translucent and tinted as the mod's ghost is, cells that
 * drop, swell or fade as they come and go, and the overlays (boxes, arrows, rings, markers, dimension lines, a player figure),
 * which are drawn into the same buffer so they hide behind blocks (or not) and are smoothed with the rest. The camera is
 * orthographic like the Save preview's. Text, the cursor and the mock panels are not here: the screen draws those over the
 * picture with the positions {@link #labels} and {@link Camera#project} give.
 *
 * <p>One instance draws one picture at a time and keeps its arrays between pictures, so a worker thread owns one.
 */
public final class StageRaster {
    /** Block states by their string, for the palette of a lesson: the game's looks in play, flat colours in tests. */
    public interface Looks {
        BlockLook.Look of(String spec);

        /** The 64 x 64 skin the little player wears, or null for flat colours (tests, no game). */
        default BlockLook.@Nullable Tex skin() {
            return null;
        }
    }

    /** The orthographic camera of a picture: the point of the world at the middle of the picture, and how large a block is. */
    public static final class Camera {
        private final double yaw, pitch, scale, ox, oy, fx, fy, fz, cy, sy, cp, sp;

        public Camera(double yaw, double pitch, double scale, double ox, double oy, double fx, double fy, double fz) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.scale = scale;
            this.ox = ox;
            this.oy = oy;
            this.fx = fx;
            this.fy = fy;
            this.fz = fz;
            this.cy = Math.cos(yaw);
            this.sy = Math.sin(yaw);
            this.cp = Math.cos(pitch);
            this.sp = Math.sin(pitch);
        }

        public double yaw() {
            return yaw;
        }

        public double pitch() {
            return pitch;
        }

        /** Samples a block is across (the picture of a block's width in the screen is this times the cosine of its angle). */
        public double scale() {
            return scale;
        }

        /** Writes screen x, screen y (in samples from the top left) and depth (bigger is nearer). */
        public void project(double x, double y, double z, double[] out) {
            double qx = x - fx, qy = y - fy, qz = z - fz;
            double rx = qx * cy - qz * sy, rz = qx * sy + qz * cy;
            out[0] = ox + rx * scale;
            out[1] = oy - (qy * cp - rz * sp) * scale;
            out[2] = qy * sp + rz * cp;
        }

        public double[] project(double x, double y, double z) {
            double[] out = new double[3];
            project(x, y, z, out);
            return out;
        }

        /** Whether a surface with this normal (in the world) faces the camera. */
        public boolean faces(double nx, double ny, double nz) {
            return ny * sp + (nx * sy + nz * cy) * cp > 1e-9;
        }
    }

    /** A piece of text to put over the picture: where (samples), what, which colour (ARGB), how it hangs (0 centred, -1 to the left of the point, 1 to the right), and how visible. */
    public record TextMark(double x, double y, String text, int color, int anchor, double alpha, boolean boxed) {
    }

    // modes of a triangle
    private static final int BLEND = 1, OVER = 2, NOTEST = 4, UNDER = 8, FLAT = 16;
    private static final int STRIDE = 19;
    private static final int[][] N = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][][] CORNERS = {
        {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
        {{0, 0, 1}, {0, 1, 1}, {0, 1, 0}, {0, 0, 0}},
        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
        {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
        {{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}},
        {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
    };
    private static final double LX = -0.35, LY = 0.85, LZ = 0.40;
    private static final double LLEN = Math.sqrt(LX * LX + LY * LY + LZ * LZ);
    /** Blocks drawn smaller than this many samples across are flat colour: the texture would only shimmer. */
    private static final double TEXTURE_FROM = 5.0;
    private static final int GHOST_MUL = 0xFFCCEDFF, WHITE = 0xFFFFFFFF;

    public static final int BACKGROUND_TOP = 0xFF16406A, BACKGROUND_BOTTOM = 0xFF0C2542, GRID = 0xFF2A6396;

    // triangle storage, reused between pictures
    private double[] tri = new double[STRIDE * 4096];
    private BlockLook.Tex[] tex = new BlockLook.Tex[4096];
    private int[] flat = new int[4096], tint = new int[4096], mul = new int[4096], mode = new int[4096];
    private int tris;

    private int w, h;
    private int[] px;
    private float[] depth;
    private final List<TextMark> marks = new ArrayList<>();
    private Camera cam;
    private double unit = 1;
    private Looks looks;
    private Scene scene;
    private BlockLook.Look[] lookCache = new BlockLook.Look[0];

    /**
     * The camera of a picture: the lesson's own framing (yaw, pitch, zoom, focus), kept honest: when a lesson stands on a slab
     * called "ground", the slab and the other groups at their places stay inside the picture (or the lesson's {@code frame}),
     * first by moving the picture a little and only if that is not enough by drawing it smaller. Nothing is ever zoomed in.
     */
    public static Camera camera(Scene s, Snapshot snap, int w, int h) {
        double radius = 0.5 * Math.sqrt((double) s.size[0] * s.size[0] + (double) s.size[1] * s.size[1] + (double) s.size[2] * s.size[2]);
        double scale = Math.min(w, h) * 0.92 / (2 * Math.max(1.0, radius)) * snap.zoom;
        double ox = w / 2.0, oy = h / 2.0;
        if (s.groups.stream().anyMatch(g -> g.id.equals("ground"))) {
            Camera base = new Camera(snap.yaw, snap.pitch, scale, ox, oy, snap.focus[0], snap.focus[1], snap.focus[2]);
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            double[] p = new double[3];
            for (Scene.Group g : s.groups) {
                for (int k = 0; k < 8; k++) {
                    base.project(g.pos[0] + (k & 1) * g.sx, g.pos[1] + (k >> 1 & 1) * g.sy, g.pos[2] + (k >> 2 & 1) * g.sz, p);
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                }
            }
            double margin = 0.03;
            double rx0 = w * (s.frame[0] + margin), rx1 = w * (s.frame[2] - margin);
            double ry0 = h * (s.frame[1] + margin), ry1 = h * (s.frame[3] - margin);
            double k = Math.min(1.0, Math.min((rx1 - rx0) / Math.max(1e-6, maxX - minX), (ry1 - ry0) / Math.max(1e-6, maxY - minY)));
            // the bounds after drawing smaller about the picture's middle
            double bx0 = ox + (minX - ox) * k, bx1 = ox + (maxX - ox) * k, by0 = oy + (minY - oy) * k, by1 = oy + (maxY - oy) * k;
            double dx = bx0 < rx0 ? rx0 - bx0 : bx1 > rx1 ? rx1 - bx1 : 0, dy = by0 < ry0 ? ry0 - by0 : by1 > ry1 ? ry1 - by1 : 0;
            scale *= k;
            ox += dx;
            oy += dy;
        }
        return new Camera(snap.yaw, snap.pitch, scale, ox, oy, snap.focus[0], snap.focus[1], snap.focus[2]);
    }

    /** The text overlays (labels, dimension numbers) of the last picture, in samples. */
    public List<TextMark> labels() {
        return marks;
    }

    public Camera lastCamera() {
        return cam;
    }

    /**
     * Draws a snapshot.
     *
     * @param unit how many samples make one GUI unit: line widths and ticks follow it so they look the same at any resolution
     * @return the picture, {@code w * h} ARGB pixels
     */
    public int[] render(Scene scene, Snapshot snap, Looks looks, int w, int h, double unit) {
        this.scene = scene;
        this.looks = looks;
        this.w = w;
        this.h = h;
        this.unit = unit;
        if (px == null || px.length != w * h) {
            px = new int[w * h];
            depth = new float[w * h];
        }
        marks.clear();
        tris = 0;
        cam = camera(scene, snap, w, h);
        if (lookCache.length != scene.palette.size() + 1) lookCache = new BlockLook.Look[scene.palette.size() + 1];
        else Arrays.fill(lookCache, null);
        background();
        grid(snap);
        int[][] tints = tintMasks(snap);
        for (int i = 0; i < snap.groups.size(); i++) emitGroup(snap.groups.get(i), snap, tints[i]);
        for (Snapshot.ItemView it : snap.overlays) emitOverlay(it, snap);
        Arrays.fill(depth, -Float.MAX_VALUE);
        rasterAll();
        return px;
    }

    // ---- background

    private void background() {
        for (int y = 0; y < h; y++) {
            int c = mix(BACKGROUND_TOP, BACKGROUND_BOTTOM, h <= 1 ? 0 : y / (double) (h - 1));
            Arrays.fill(px, y * w, (y + 1) * w, c);
        }
    }

    /** Blueprint paper: the grid of the ground plane, going on past the stage and fading out, with the soft shadow of the slab on it. */
    private void grid(Snapshot snap) {
        int pad = 6;
        double[] fp = footprint();
        int x0 = (int) Math.floor(fp[0]) - pad, x1 = (int) Math.ceil(fp[2]) + pad, z0 = (int) Math.floor(fp[1]) - pad, z1 = (int) Math.ceil(fp[3]) + pad;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z < z1; z++) gridSegment(fp, x, z, x, z + 1, pad);
        }
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x < x1; x++) gridSegment(fp, x, z, x + 1, z, pad);
        }
        shadow(fp);
    }

    /** The ground slab's footprint: minX, minZ, maxX, maxZ (the whole stage when a lesson has no group called "ground"). */
    private double[] footprint() {
        for (Scene.Group g : scene.groups) {
            if (g.id.equals("ground")) return new double[]{g.pos[0], g.pos[2], g.pos[0] + g.sx, g.pos[2] + g.sz};
        }
        return new double[]{0, 0, scene.size[0], scene.size[2]};
    }

    private void gridSegment(double[] fp, double xa, double za, double xb, double zb, int pad) {
        double mx = (xa + xb) / 2, mz = (za + zb) / 2;
        double dx = Math.max(0, Math.max(fp[0] - mx, mx - fp[2])), dz = Math.max(0, Math.max(fp[1] - mz, mz - fp[3]));
        double fade = 1 - Math.min(1, Math.hypot(dx, dz) / (pad + 0.5));
        if (fade <= 0.02) return;
        line(xa, 0, za, xb, 0, zb, GRID, 0.5, Math.pow(fade, 1.3), UNDER | NOTEST);
    }

    /** A soft shadow on the paper beside the slab, away from the light: three growing layers of the same dark. */
    private void shadow(double[] fp) {
        boolean has = false;
        for (Scene.Group g : scene.groups) if (g.id.equals("ground")) has = true;
        if (!has) return;
        double ox = 0.9, oz = -0.9;
        for (int k = 0; k < 4; k++) {
            double e = 0.15 + k * 0.45;
            double x0 = fp[0] + ox - e, x1 = fp[2] + ox + e, z0 = fp[1] + oz - e, z1 = fp[3] + oz + e;
            double[] a = cam.project(x0, 0, z0), b = cam.project(x1, 0, z0), c = cam.project(x1, 0, z1), d = cam.project(x0, 0, z1);
            addTri(a, b, c, null, null, null, null, 0xFF06142A, 1.0, WHITE, 0, 0, 0.13, UNDER | FLAT | BLEND | NOTEST);
            addTri(a, c, d, null, null, null, null, 0xFF06142A, 1.0, WHITE, 0, 0, 0.13, UNDER | FLAT | BLEND | NOTEST);
        }
    }

    // ---- groups

    private BlockLook.Look look(int paletteNumber) {
        BlockLook.Look l = lookCache[paletteNumber];
        if (l == null) lookCache[paletteNumber] = l = looks.of(scene.palette.get(paletteNumber - 1));
        return l;
    }

    /** Per group, per cell: the colour a tint overlay lays over it (ARGB: the amount is in the alpha), or 0. */
    private int[][] tintMasks(Snapshot snap) {
        int[][] out = new int[snap.groups.size()][];
        for (int i = 0; i < out.length; i++) out[i] = new int[snap.groups.get(i).group.volume()];
        for (Snapshot.ItemView it : snap.overlays) {
            if (!it.type().equals("tint")) continue;
            String gid = it.str("group", "");
            for (int i = 0; i < out.length; i++) {
                Scene.Group g = snap.groups.get(i).group;
                if (!g.id.equals(gid)) continue;
                int color = color(it.str("color", "gold"));
                double amount = it.num("alpha", 0.5) * it.alpha;
                if (it.bool("pulse", false)) amount *= 0.65 + 0.35 * Math.sin(it.age() * 7);
                int a = (int) Math.max(0, Math.min(255, amount * 255));
                List<double[]> cells = it.props().points("cells");
                String keyName = it.str("key", "");
                if (!cells.isEmpty()) {
                    for (double[] c : cells) {
                        if (c.length != 3) continue;
                        int x = (int) c[0], y = (int) c[1], z = (int) c[2];
                        if (x < 0 || y < 0 || z < 0 || x >= g.sx || y >= g.sy || z >= g.sz) continue;
                        out[i][g.index(x, y, z)] = a << 24 | (color & 0xFFFFFF);
                    }
                } else {
                    for (int c = 0; c < g.volume(); c++) {
                        if (g.cell[c] == 0) continue;
                        if (!keyName.isEmpty() && !scene.paletteKeys.get(g.cell[c] - 1).equals(keyName)) continue;
                        out[i][c] = a << 24 | (color & 0xFFFFFF);
                    }
                }
            }
        }
        return out;
    }

    private void emitGroup(Snapshot.GroupView gv, Snapshot snap, int[] tintMask) {
        Scene.Group g = gv.group;
        boolean ghost = g.mode == Scene.Mode.GHOST;
        double ang = gv.angle(), c = Math.cos(ang), s = Math.sin(ang);
        double px0 = g.sx / 2.0, pz0 = g.sz / 2.0;
        double texFrom = TEXTURE_FROM;
        boolean textured = cam.scale() >= texFrom;
        int layer = g.sx * g.sz;
        double[] wv = new double[3];
        double[] sv = new double[3];
        double[][] corner = new double[4][3];
        double[][] cuv = new double[4][2];
        for (int cellIndex = 0; cellIndex < g.volume(); cellIndex++) {
            byte st = gv.state[cellIndex];
            if (st == Snapshot.ABSENT) continue;
            int x = cellIndex % g.sx, z = (cellIndex / g.sx) % g.sz, y = cellIndex / layer;
            BlockLook.Look look = look(g.cell[cellIndex]);
            // how this one cell is arriving or going
            byte an = gv.anim[cellIndex];
            double p = gv.progress[cellIndex];
            double offY = 0, cs = 1, aMul = 1;
            if (an == Snapshot.DROP_IN) {
                double q = 1 - p;
                offY = 2.6 * q * q;
                aMul = Math.min(1, p * 4);
            } else if (an == Snapshot.POP_IN) {
                cs = 0.55 + 0.45 * Ease.SPRING.apply(p);
            } else if (an == Snapshot.FADE_IN) {
                aMul = p;
            } else if (an == Snapshot.FADE_OUT) {
                aMul = 1 - p;
            }
            boolean wrong = st == Snapshot.WRONG;
            if (wrong) cs *= 1.012;
            double alpha = (ghost ? Scene.GHOST_OPACITY : 1.0) * gv.alpha * aMul;
            if (alpha <= 0.004) continue;
            boolean blend = ghost || alpha < 0.995;
            int tm = tintMask[cellIndex];
            int tintColor = wrong ? 0xFFFF4A4A : tm & 0xFFFFFF | 0xFF000000;
            double tintAmt = wrong ? 0.55 : (tm >>> 24) / 255.0;
            int mulColor = ghost ? GHOST_MUL : WHITE;
            double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            if (look.cube()) {
                for (int d = 0; d < 6; d++) {
                    // hidden when a block of the same group stands against this side
                    int nx = x + N[d][0], ny = y + N[d][1], nz = z + N[d][2];
                    if (nx >= 0 && ny >= 0 && nz >= 0 && nx < g.sx && ny < g.sy && nz < g.sz) {
                        int ni = (ny * g.sz + nz) * g.sx + nx;
                        if (gv.state[ni] != Snapshot.ABSENT && look(g.cell[ni]).cube() && (ghost || !look(g.cell[ni]).translucent()) && gv.anim[ni] == Snapshot.NO_ANIM) continue;
                    }
                    // the side's normal in the world, for the front test and the light
                    double nwx = gv.mirror ? -N[d][0] : N[d][0], nwz = N[d][2];
                    double ex = nwx * c - nwz * s, ez = nwx * s + nwz * c;
                    double ey = N[d][1];
                    if (!front(ex, ey, ez)) continue;
                    for (int k = 0; k < 4; k++) {
                        int[] cc = CORNERS[d][k];
                        double lx = cx + (cc[0] - 0.5) * cs, ly = cy + (cc[1] - 0.5) * cs + offY, lz = cz + (cc[2] - 0.5) * cs;
                        toWorld(gv, c, s, px0, pz0, lx, ly, lz, wv);
                        cam.project(wv[0], wv[1], wv[2], sv);
                        corner[k][0] = sv[0];
                        corner[k][1] = sv[1];
                        corner[k][2] = sv[2];
                        cuv[k][0] = u(d, cc[0], cc[2]);
                        cuv[k][1] = v(d, cc[1], cc[2]);
                    }
                    BlockLook.Tex t = look.face()[d];
                    boolean useTex = textured && t.px() != null;
                    double light = shade(ex, ey, ez) * (useTex ? 1.0 : 0.94 + 0.12 * jitter(cellIndex));
                    quad(corner, cuv, useTex ? t : null, t.avg(), light, mulColor, tintColor, tintAmt, alpha, blend ? BLEND : 0);
                }
            } else {
                BlockLook.Quad[] model = look.model();
                if (model == null) continue;
                for (BlockLook.Quad q : model) {
                    for (int k = 0; k < 4; k++) {
                        double lx = cx + (x + q.p()[k * 3] - cx) * cs, ly = cy + (y + q.p()[k * 3 + 1] - cy) * cs + offY, lz = cz + (z + q.p()[k * 3 + 2] - cz) * cs;
                        toWorld(gv, c, s, px0, pz0, lx, ly, lz, wv);
                        cam.project(wv[0], wv[1], wv[2], sv);
                        corner[k][0] = sv[0];
                        corner[k][1] = sv[1];
                        corner[k][2] = sv[2];
                        cuv[k][0] = q.uv()[k * 2];
                        cuv[k][1] = q.uv()[k * 2 + 1];
                    }
                    int dir = q.dir();
                    double nwx = gv.mirror ? -N[dir][0] : N[dir][0], nwz = N[dir][2];
                    double ex = nwx * c - nwz * s, ez = nwx * s + nwz * c;
                    boolean useTex = q.tex().px() != null;
                    quad(corner, cuv, useTex ? q.tex() : null, q.tex().avg(), shade(ex, N[dir][1], ez) * 0.92 + 0.08, mulColor, tintColor, tintAmt, alpha, blend ? BLEND : 0);
                }
            }
        }
    }

    private static void toWorld(Snapshot.GroupView gv, double c, double s, double px, double pz, double lx, double ly, double lz, double[] out) {
        double rx = lx - px, rz = lz - pz;
        if (gv.mirror) rx = -rx;
        out[0] = gv.pos[0] + px + (rx * c - rz * s) * gv.scale;
        out[1] = gv.pos[1] + ly * gv.scale;
        out[2] = gv.pos[2] + pz + (rx * s + rz * c) * gv.scale;
    }

    private boolean front(double nx, double ny, double nz) {
        return cam.faces(nx, ny, nz);
    }

    private static double shade(double nx, double ny, double nz) {
        double dot = (nx * LX + ny * LY + nz * LZ) / LLEN;
        return 0.46 + 0.54 * Math.max(0, dot);
    }

    private static double u(int d, double x, double z) {
        return switch (d) {
            case 0 -> 1 - z;
            case 1 -> z;
            case 2, 3 -> x;
            case 4 -> x;
            default -> 1 - x;
        };
    }

    private static double v(int d, double y, double z) {
        return switch (d) {
            case 2 -> z;
            case 3 -> 1 - z;
            default -> 1 - y;
        };
    }

    private static double jitter(int cell) {
        int hh = cell * 0x9E3779B1;
        hh ^= hh >>> 15;
        hh *= 0x85EBCA6B;
        hh ^= hh >>> 13;
        return (hh & 0xFFFF) / 65535.0;
    }

    // ---- triangles

    private void grow() {
        int n = tex.length * 2;
        tri = Arrays.copyOf(tri, n * STRIDE);
        tex = Arrays.copyOf(tex, n);
        flat = Arrays.copyOf(flat, n);
        tint = Arrays.copyOf(tint, n);
        mul = Arrays.copyOf(mul, n);
        mode = Arrays.copyOf(mode, n);
    }

    private void addTri(double[] a, double[] b, double[] c, double[] ua, double[] ub, double[] uc, BlockLook.@Nullable Tex t, int flatColor, double light, int mulColor, int tintColor, double tintAmt, double alpha, int m) {
        if (tris == tex.length) grow();
        int o = tris * STRIDE;
        tri[o] = a[0];
        tri[o + 1] = a[1];
        tri[o + 2] = a[2];
        tri[o + 3] = b[0];
        tri[o + 4] = b[1];
        tri[o + 5] = b[2];
        tri[o + 6] = c[0];
        tri[o + 7] = c[1];
        tri[o + 8] = c[2];
        if (ua != null) {
            tri[o + 9] = ua[0];
            tri[o + 10] = ua[1];
            tri[o + 11] = ub[0];
            tri[o + 12] = ub[1];
            tri[o + 13] = uc[0];
            tri[o + 14] = uc[1];
        }
        tri[o + 15] = light;
        tri[o + 16] = alpha;
        tri[o + 17] = tintAmt;
        tex[tris] = t;
        flat[tris] = flatColor;
        tint[tris] = tintColor;
        mul[tris] = mulColor;
        mode[tris] = m | (t == null ? FLAT : 0);
        tris++;
    }

    private void quad(double[][] q, double[][] uv, BlockLook.@Nullable Tex t, int flatColor, double light, int mulColor, int tintColor, double tintAmt, double alpha, int m) {
        addTri(q[0], q[1], q[2], uv[0], uv[1], uv[2], t, flatColor, light, mulColor, tintColor, tintAmt, alpha, m);
        addTri(q[0], q[2], q[3], uv[0], uv[2], uv[3], t, flatColor, light, mulColor, tintColor, tintAmt, alpha, m);
    }

    /** A flat colour triangle in screen space (samples) with depths, for overlays and lines. */
    private void flatTri(double[] a, double[] b, double[] c, int color, double alpha, int m) {
        addTri(a, b, c, null, null, null, null, color, 1.0, WHITE, 0, 0, alpha, m | OVER);
    }

    /** A line from a world point to another, in a colour, {@code width} GUI units thick. */
    private void line(double x0, double y0, double z0, double x1, double y1, double z1, int color, double width, double alpha, int m) {
        double[] a = cam.project(x0, y0, z0), b = cam.project(x1, y1, z1);
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return;
        double hw = Math.max(0.5, width * unit) / 2;
        double nx = -dy / len * hw, ny = dx / len * hw;
        double[] p0 = {a[0] + nx, a[1] + ny, a[2]}, p1 = {a[0] - nx, a[1] - ny, a[2]}, p2 = {b[0] - nx, b[1] - ny, b[2]}, p3 = {b[0] + nx, b[1] + ny, b[2]};
        if ((m & UNDER) != 0) {
            addTri(p0, p1, p2, null, null, null, null, color, 1.0, WHITE, 0, 0, alpha, UNDER | FLAT | BLEND);
            addTri(p0, p2, p3, null, null, null, null, color, 1.0, WHITE, 0, 0, alpha, UNDER | FLAT | BLEND);
        } else {
            flatTri(p0, p1, p2, color, alpha, m | BLEND);
            flatTri(p0, p2, p3, color, alpha, m | BLEND);
        }
    }

    // ---- rasterising

    private void rasterAll() {
        int bands = Math.max(1, Math.min(Math.min(BANDS, h / 40), tris / 300 + 1));
        if (bands == 1) {
            band(0, h);
            return;
        }
        int rows = (h + bands - 1) / bands;
        java.util.stream.IntStream.range(0, bands).parallel().forEach(b -> band(b * rows, Math.min(h, (b + 1) * rows)));
    }

    private static final int BANDS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));

    private void band(int y0, int y1) {
        // paper first, then solid blocks, then everything see-through and the overlays in the order they were made
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < tris; i++) {
                int m = mode[i];
                boolean under = (m & UNDER) != 0, opaque = (m & (BLEND | OVER)) == 0;
                if (pass == 0 && !under) continue;
                if (pass == 1 && !opaque) continue;
                if (pass == 2 && (under || opaque)) continue;
                fill(i, y0, y1);
            }
        }
    }

    private void fill(int i, int y0, int y1) {
        int o = i * STRIDE;
        double x0 = tri[o], ya = tri[o + 1], z0 = tri[o + 2], x1 = tri[o + 3], yb = tri[o + 4], z1 = tri[o + 5], x2 = tri[o + 6], yc = tri[o + 7], z2 = tri[o + 8];
        double minY = Math.min(ya, Math.min(yb, yc)), maxY = Math.max(ya, Math.max(yb, yc));
        double minX = Math.min(x0, Math.min(x1, x2)), maxX = Math.max(x0, Math.max(x1, x2));
        int iy0 = Math.max(y0, (int) Math.floor(minY)), iy1 = Math.min(y1 - 1, (int) Math.ceil(maxY));
        int ixMin = Math.max(0, (int) Math.floor(minX)), ixMax = Math.min(w - 1, (int) Math.ceil(maxX));
        if (iy0 > iy1 || ixMin > ixMax) return;
        double area = (x1 - x0) * (yc - ya) - (x2 - x0) * (yb - ya);
        if (Math.abs(area) < 1e-9) return;
        double inv = 1.0 / area;
        double b0 = -inv * (yc - yb), b1 = -inv * (ya - yc), b2 = -b0 - b1;
        double u0 = tri[o + 9], v0 = tri[o + 10], u1 = tri[o + 11], v1 = tri[o + 12], u2 = tri[o + 13], v2 = tri[o + 14];
        double zx = b0 * z0 + b1 * z1 + b2 * z2, ux = b0 * u0 + b1 * u1 + b2 * u2, vx = b0 * v0 + b1 * v1 + b2 * v2;
        double light = tri[o + 15], alpha = tri[o + 16], tintAmt = tri[o + 17];
        int m = mode[i];
        BlockLook.Tex t = tex[i];
        int[] texels = t == null ? null : t.px();
        int tw = t == null ? 1 : t.w(), th = t == null ? 1 : t.h();
        int mc = mul[i], tc = tint[i];
        int flatBase = flat[i];
        boolean blend = (m & BLEND) != 0, test = (m & NOTEST) == 0, write = (m & (BLEND | OVER | UNDER)) == 0;
        boolean under = (m & UNDER) != 0, over = (m & OVER) != 0;
        final double eps = 0.02;
        for (int y = iy0; y <= iy1; y++) {
            double py = y + 0.5;
            double a0 = inv * (x1 * (yc - py) - x2 * (yb - py));
            double a1 = inv * (x2 * (ya - py) - x0 * (yc - py));
            double a2 = 1 - a0 - a1;
            double lo = ixMin + 0.5, hi = ixMax + 0.5;
            if (b0 > 1e-12) lo = Math.max(lo, (-eps - a0) / b0); else if (b0 < -1e-12) hi = Math.min(hi, (-eps - a0) / b0); else if (a0 < -eps) continue;
            if (b1 > 1e-12) lo = Math.max(lo, (-eps - a1) / b1); else if (b1 < -1e-12) hi = Math.min(hi, (-eps - a1) / b1); else if (a1 < -eps) continue;
            if (b2 > 1e-12) lo = Math.max(lo, (-eps - a2) / b2); else if (b2 < -1e-12) hi = Math.min(hi, (-eps - a2) / b2); else if (a2 < -eps) continue;
            int xs = Math.max(ixMin, (int) Math.ceil(lo - 0.5)), xe = Math.min(ixMax, (int) Math.floor(hi - 0.5));
            if (xs > xe) continue;
            double zRow = a0 * z0 + a1 * z1 + a2 * z2, uRow = a0 * u0 + a1 * u1 + a2 * u2, vRow = a0 * v0 + a1 * v1 + a2 * v2;
            int at = y * w + xs;
            for (int x = xs; x <= xe; x++, at++) {
                double pxx = x + 0.5;
                float z = (float) (zRow + zx * pxx);
                if (test && !under) {
                    // overlays may sit on a surface (a little give); blocks must be nearer than what is drawn
                    if (over ? z < depth[at] - 1e-3f : z <= depth[at]) continue;
                }
                int texel;
                if (texels != null) {
                    double uu = uRow + ux * pxx, vv = vRow + vx * pxx;
                    uu = uu < 0 ? 0 : Math.min(0.9999, uu);
                    vv = vv < 0 ? 0 : Math.min(0.9999, vv);
                    texel = texels[(int) (vv * th) * tw + (int) (uu * tw)];
                    if ((texel >>> 24) < 128) continue;
                } else {
                    texel = flatBase;
                }
                int color = lit(texel, light, mc, tc, tintAmt);
                if (blend) px[at] = mixArgb(px[at], color, alpha);
                else px[at] = color;
                if (write) depth[at] = z;
            }
        }
    }

    private static int lit(int texel, double light, int mc, int tc, double tintAmt) {
        double r = ((texel >> 16) & 255) * light * ((mc >> 16) & 255) / 255.0;
        double g = ((texel >> 8) & 255) * light * ((mc >> 8) & 255) / 255.0;
        double b = (texel & 255) * light * (mc & 255) / 255.0;
        if (tintAmt > 0.001) {
            r = r * (1 - tintAmt) + ((tc >> 16) & 255) * tintAmt;
            g = g * (1 - tintAmt) + ((tc >> 8) & 255) * tintAmt;
            b = b * (1 - tintAmt) + (tc & 255) * tintAmt;
        }
        return 0xFF000000 | (int) Math.min(255, r) << 16 | (int) Math.min(255, g) << 8 | (int) Math.min(255, b);
    }

    private static int mixArgb(int dst, int src, double a) {
        int r = (int) (((src >> 16) & 255) * a + ((dst >> 16) & 255) * (1 - a)), g = (int) (((src >> 8) & 255) * a + ((dst >> 8) & 255) * (1 - a)), b = (int) ((src & 255) * a + (dst & 255) * (1 - a));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    // ---- overlays

    /** An overlay colour: a name from the mod's palette, or #RRGGBB. */
    public static int color(String s) {
        return switch (s) {
            case "x", "red" -> 0xFFFF6B6B;
            case "y", "green" -> 0xFF6BE58F;
            case "z", "blue" -> 0xFF6BB8FF;
            case "gold", "yellow" -> 0xFFFFD866;
            case "cyan" -> 0xFF7FE3FF;
            case "ring", "line" -> 0xFFE8F6FF;
            case "white" -> 0xFFFFFFFF;
            case "dim" -> 0xFF8BB6D8;
            case "ink" -> 0xFF0A1B30;
            default -> {
                if (s.startsWith("#") && s.length() == 7) {
                    try {
                        yield 0xFF000000 | Integer.parseInt(s.substring(1), 16);
                    } catch (NumberFormatException e) {
                        yield 0xFFFFFFFF;
                    }
                }
                yield 0xFFFFFFFF;
            }
        };
    }

    private void emitOverlay(Snapshot.ItemView it, Snapshot snap) {
        double a = it.alpha;
        switch (it.type()) {
            case "box" -> box(it, a);
            case "arrow" -> arrow(it, a);
            case "disc" -> disc(it, a);
            case "ring" -> ring(it, a);
            case "marker" -> marker(it, a);
            case "dim" -> dim(it, a);
            case "avatar" -> avatar(it, a);
            case "path" -> path(it, a);
            case "label" -> {
                double[] at = it.vec("at", new double[]{0, 0, 0});
                double[] p = cam.project(at[0], at[1], at[2]);
                String anchor = it.str("anchor", "center");
                marks.add(new TextMark(p[0], p[1], it.str("text", ""), color(it.str("color", "white")), anchor.equals("left") ? -1 : anchor.equals("right") ? 1 : 0, a, true));
            }
            default -> {
            }
        }
    }

    /** A flat translucent disc with a brighter rim on the ground: how far something reaches. */
    private void disc(Snapshot.ItemView it, double a) {
        double[] c = it.vec("center", new double[]{0, 0, 0});
        double r = it.num("radius", 4);
        int color = color(it.str("color", "cyan"));
        double al = it.num("alpha", 0.16) * a;
        int n = 56;
        double y = c[1] + 0.02;
        double[] mid = cam.project(c[0], y, c[2]);
        for (int i = 0; i < n; i++) {
            double t0 = i * 2 * Math.PI / n, t1 = (i + 1) * 2 * Math.PI / n;
            double[] p0 = cam.project(c[0] + Math.cos(t0) * r, y, c[2] + Math.sin(t0) * r), p1 = cam.project(c[0] + Math.cos(t1) * r, y, c[2] + Math.sin(t1) * r);
            flatTri(mid, p0, p1, color, al, BLEND | NOTEST);
            line(c[0] + Math.cos(t0) * r, y, c[2] + Math.sin(t0) * r, c[0] + Math.cos(t1) * r, y, c[2] + Math.sin(t1) * r, color, 1.3, 0.8 * a, BLEND | NOTEST);
        }
    }

    private void box(Snapshot.ItemView it, double a) {
        double[] f = it.vec("from", new double[]{0, 0, 0}), t = it.vec("to", new double[]{1, 1, 1});
        int color = color(it.str("color", "cyan"));
        boolean dashed = it.str("style", "solid").equals("dashed");
        double x0 = Math.min(f[0], t[0]), x1 = Math.max(f[0], t[0]), y0 = Math.min(f[1], t[1]), y1 = Math.max(f[1], t[1]), z0 = Math.min(f[2], t[2]), z1 = Math.max(f[2], t[2]);
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        // the floor, faintly
        double[] p0 = cam.project(c[0][0], c[0][1], c[0][2]), p1 = cam.project(c[1][0], c[1][1], c[1][2]), p2 = cam.project(c[2][0], c[2][1], c[2][2]), p3 = cam.project(c[3][0], c[3][1], c[3][2]);
        flatTri(p0, p1, p2, color, 0.10 * a, BLEND);
        flatTri(p0, p2, p3, color, 0.10 * a, BLEND);
        for (int[] e : edges) {
            double[] u = c[e[0]], v = c[e[1]];
            if (dashed) dashedLine(u, v, color, 1.4, a * 0.9);
            else line(u[0], u[1], u[2], v[0], v[1], v[2], color, 1.4, a * 0.9, BLEND | NOTEST);
        }
        if (it.bool("brackets", true)) {
            for (double[] corner : c) {
                for (int axis = 0; axis < 3; axis++) {
                    double len = Math.min(0.7, (axis == 0 ? x1 - x0 : axis == 1 ? y1 - y0 : z1 - z0) / 3);
                    double[] dir = {0, 0, 0};
                    double lo = axis == 0 ? x0 : axis == 1 ? y0 : z0;
                    dir[axis] = corner[axis] == lo ? len : -len;
                    line(corner[0], corner[1], corner[2], corner[0] + dir[0], corner[1] + dir[1], corner[2] + dir[2], mix(color, WHITE, 0.5), 2.6, a, BLEND | NOTEST);
                }
            }
        }
    }

    private void dashedLine(double[] u, double[] v, int color, double width, double alpha) {
        double dx = v[0] - u[0], dy = v[1] - u[1], dz = v[2] - u[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double dash = 0.35, gap = 0.22;
        for (double s = 0; s < len; s += dash + gap) {
            double e = Math.min(len, s + dash);
            line(u[0] + dx * s / len, u[1] + dy * s / len, u[2] + dz * s / len, u[0] + dx * e / len, u[1] + dy * e / len, u[2] + dz * e / len, color, width, alpha, BLEND | NOTEST);
        }
    }

    private void arrow(Snapshot.ItemView it, double a) {
        double[] at = it.vec("at", new double[]{0, 0, 0});
        String dir = it.str("dir", "+x");
        int axis = dir.endsWith("y") ? 1 : dir.endsWith("z") ? 2 : 0;
        double sign = dir.startsWith("-") ? -1 : 1;
        double len = it.num("len", 1.8), off = it.num("off", 0), hot = Math.max(0, Math.min(1, it.num("hot", 0)));
        int color = mix(color(it.str("color", axis == 0 ? "x" : axis == 1 ? "y" : "z")), WHITE, 0.35 * hot);
        double k = 1 + 0.25 * hot;
        double[] base = at.clone();
        base[axis] += sign * off;
        solidArrow(base, axis, sign, len * k, 0.11 * k, 0.3 * k, 0.62, color, a);
    }

    /** A solid arrow from {@code base} along an axis: a square shaft and a pyramid head, lit, always on top (the handles of the game are). */
    private void solidArrow(double[] base, int axis, double sign, double len, double w, double hw, double shaftFrac, int color, double a) {
        double shaft = len * shaftFrac;
        double[] ax = new double[3], u = new double[3], v = new double[3];
        ax[axis] = sign;
        u[(axis + 1) % 3] = 1;
        v[(axis + 2) % 3] = 1;
        double[] s0 = base, s1 = {base[0] + ax[0] * shaft, base[1] + ax[1] * shaft, base[2] + ax[2] * shaft}, tip = {base[0] + ax[0] * len, base[1] + ax[1] * len, base[2] + ax[2] * len};
        double[][] ring0 = square(s0, u, v, w), ring1 = square(s1, u, v, w);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            shadedQuad(ring0[i], ring0[j], ring1[j], ring1[i], color, a, true);
        }
        shadedQuad(ring1[0], ring1[1], ring1[2], ring1[3], color, a, true);
        double[][] hb = square(s1, u, v, hw);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            shadedTri(hb[i], hb[j], tip, color, a);
        }
        shadedQuad(hb[0], hb[1], hb[2], hb[3], mix(color, 0xFF000000, 0.35), a, true);
    }

    private static double[][] square(double[] c, double[] u, double[] v, double r) {
        double[][] out = new double[4][3];
        double[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) for (int d = 0; d < 3; d++) out[i][d] = c[d] + u[d] * r * sg[i][0] + v[d] * r * sg[i][1];
        return out;
    }

    /** A lit world-space quad, always on top (handles are). */
    private void shadedQuad(double[] a, double[] b, double[] c, double[] d, int color, double alpha, boolean onTop) {
        double nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
        double ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]);
        double nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12) return;
        // the quad is seen from either side, so the light uses the side that faces the camera
        double lit = shade(nx / len, ny / len, nz / len), lit2 = shade(-nx / len, -ny / len, -nz / len);
        double light = front(nx / len, ny / len, nz / len) ? lit : lit2;
        int col = lit(color, light, WHITE, 0, 0);
        double[] pa = cam.project(a[0], a[1], a[2]), pb = cam.project(b[0], b[1], b[2]), pc = cam.project(c[0], c[1], c[2]), pd = cam.project(d[0], d[1], d[2]);
        int m = (onTop ? NOTEST : 0) | (alpha < 0.995 ? BLEND : 0);
        flatTri(pa, pb, pc, col, alpha, m);
        flatTri(pa, pc, pd, col, alpha, m);
    }

    private void shadedTri(double[] a, double[] b, double[] c, int color, double alpha) {
        double nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
        double ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]);
        double nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12) return;
        double light = front(nx / len, ny / len, nz / len) ? shade(nx / len, ny / len, nz / len) : shade(-nx / len, -ny / len, -nz / len);
        int col = lit(color, light, WHITE, 0, 0);
        flatTri(cam.project(a[0], a[1], a[2]), cam.project(b[0], b[1], b[2]), cam.project(c[0], c[1], c[2]), col, alpha, NOTEST | (alpha < 0.995 ? BLEND : 0));
    }

    private void ring(Snapshot.ItemView it, double a) {
        double[] c = it.vec("center", new double[]{0, 0, 0});
        double r = it.num("radius", 3), angle = it.num("angle", 0), hot = Math.max(0, Math.min(1, it.num("hot", 0)));
        int color = mix(color(it.str("color", "ring")), WHITE, 0.4 * hot);
        int n = 48;
        double band = 0.28;
        double y = c[1] + 0.03;
        for (int i = 0; i < n; i++) {
            double t0 = i * 2 * Math.PI / n, t1 = (i + 1) * 2 * Math.PI / n;
            double[] p0 = cam.project(c[0] + Math.cos(t0) * (r - band), y, c[2] + Math.sin(t0) * (r - band)), p1 = cam.project(c[0] + Math.cos(t0) * (r + band), y, c[2] + Math.sin(t0) * (r + band)),
                p2 = cam.project(c[0] + Math.cos(t1) * (r + band), y, c[2] + Math.sin(t1) * (r + band)), p3 = cam.project(c[0] + Math.cos(t1) * (r - band), y, c[2] + Math.sin(t1) * (r - band));
            double al = (0.28 + 0.3 * hot) * a;
            flatTri(p0, p1, p2, color, al, BLEND | NOTEST);
            flatTri(p0, p2, p3, color, al, BLEND | NOTEST);
        }
        // the band's two edges, crisp, so it reads as a ring and not a smear
        int edge = mix(color, WHITE, 0.5);
        for (int i = 0; i < n; i++) {
            double t0 = i * 2 * Math.PI / n, t1 = (i + 1) * 2 * Math.PI / n;
            for (double rr : new double[]{r - band, r + band}) {
                line(c[0] + Math.cos(t0) * rr, y, c[2] + Math.sin(t0) * rr, c[0] + Math.cos(t1) * rr, y, c[2] + Math.sin(t1) * rr, edge, 1.0, (0.55 + 0.3 * hot) * a, BLEND | NOTEST);
            }
        }
        // four chevrons going round clockwise seen from above, turned by the angle (quarter turns)
        for (int k = 0; k < 4; k++) {
            double t = (k * 0.25 + angle * 0.25) * 2 * Math.PI;
            double cx = c[0] + Math.cos(t) * r, cz = c[2] + Math.sin(t) * r;
            double tx = -Math.sin(t), tz = Math.cos(t), rx = Math.cos(t), rz = Math.sin(t);
            double[] tip = cam.project(cx + tx * 0.7, y, cz + tz * 0.7), l = cam.project(cx - tx * 0.3 + rx * 0.5, y, cz - tz * 0.3 + rz * 0.5), rr = cam.project(cx - tx * 0.3 - rx * 0.5, y, cz - tz * 0.3 - rz * 0.5);
            flatTri(tip, l, rr, mix(color, WHITE, 0.6), 0.9 * a, BLEND | NOTEST);
        }
    }

    private void marker(Snapshot.ItemView it, double a) {
        double[] at = it.vec("at", new double[]{0, 0, 0});
        int color = color(it.str("color", "gold"));
        double pulse = 0.5 + 0.5 * Math.sin(it.age() * 6);
        double g = 0.01;
        double x0 = at[0] - g, y0 = at[1] - g, z0 = at[2] - g, x1 = at[0] + 1 + g, y1 = at[1] + 1 + g, z1 = at[2] + 1 + g;
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        int[][] faces = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
        for (int[] f : faces) {
            double[] p0 = cam.project(c[f[0]][0], c[f[0]][1], c[f[0]][2]), p1 = cam.project(c[f[1]][0], c[f[1]][1], c[f[1]][2]), p2 = cam.project(c[f[2]][0], c[f[2]][1], c[f[2]][2]), p3 = cam.project(c[f[3]][0], c[f[3]][1], c[f[3]][2]);
            double al = (0.14 + 0.14 * pulse) * a;
            flatTri(p0, p1, p2, color, al, BLEND | NOTEST);
            flatTri(p0, p2, p3, color, al, BLEND | NOTEST);
        }
        int edge = mix(color, WHITE, 0.35 + 0.35 * (1 - pulse));
        for (int[] e : edges) line(c[e[0]][0], c[e[0]][1], c[e[0]][2], c[e[1]][0], c[e[1]][1], c[e[1]][2], edge, 1.4, (0.7 + 0.3 * pulse) * a, BLEND | NOTEST);
        for (double[] corner : c) {
            for (int axis = 0; axis < 3; axis++) {
                double[] dir = {0, 0, 0};
                double lo = axis == 0 ? x0 : axis == 1 ? y0 : z0;
                dir[axis] = corner[axis] == lo ? 0.3 : -0.3;
                line(corner[0], corner[1], corner[2], corner[0] + dir[0], corner[1] + dir[1], corner[2] + dir[2], WHITE, 2.4, a, BLEND | NOTEST);
            }
        }
        if (it.bool("beam", true)) {
            // a solid arrow bobbing over the block, point down
            double bob = 0.16 * Math.sin(it.age() * 4);
            double mx = (x0 + x1) / 2, mz = (z0 + z1) / 2, tipY = y1 + 0.28 + bob;
            solidArrow(new double[]{mx, tipY + 1.5, mz}, 1, -1, 1.5, 0.13, 0.38, 0.5, mix(color, WHITE, 0.15), a);
        }
    }

    private void dim(Snapshot.ItemView it, double a) {
        double[] p = it.vec("a", new double[]{0, 0, 0}), q = it.vec("b", new double[]{1, 0, 0});
        int color = color(it.str("color", "gold"));
        line(p[0], p[1], p[2], q[0], q[1], q[2], color, 1.6, 0.95 * a, BLEND | NOTEST);
        // a tick across each end, in the picture's plane
        double[] pa = cam.project(p[0], p[1], p[2]), pb = cam.project(q[0], q[1], q[2]);
        double dx = pb[0] - pa[0], dy = pb[1] - pa[1], len = Math.hypot(dx, dy);
        if (len > 1e-6) {
            double nx = -dy / len * 4 * unit, ny = dx / len * 4 * unit;
            tick(pa, nx, ny, color, a);
            tick(pb, nx, ny, color, a);
        }
        String text = it.str("text", "");
        if (!text.isEmpty()) marks.add(new TextMark((pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2 - 7 * unit, text, color, 0, a, true));
    }

    private void tick(double[] at, double nx, double ny, int color, double a) {
        double hw = Math.max(0.5, 1.6 * unit) / 2;
        double len = Math.hypot(nx, ny);
        if (len < 1e-9) return;
        double ux = -ny / len * hw, uy = nx / len * hw;
        double z = at[2];
        double[] p0 = {at[0] - nx + ux, at[1] - ny + uy, z}, p1 = {at[0] - nx - ux, at[1] - ny - uy, z}, p2 = {at[0] + nx - ux, at[1] + ny - uy, z}, p3 = {at[0] + nx + ux, at[1] + ny + uy, z};
        flatTri(p0, p1, p2, color, 0.95 * a, BLEND | NOTEST);
        flatTri(p0, p2, p3, color, 0.95 * a, BLEND | NOTEST);
    }

    // The little player: the same boxes and skin layout as the game's player model (a pixel is 1.8 / 32 of a block), so the
    // default skin lands on it unchanged. Each part: x0 y0 z0 x1 y1 z1 (pixels, feet at 0, front towards +z, the player's right at -x),
    // the skin's origin u v, the pivot height of its swing, a sign for how it swings, and a fallback colour.
    private static final double PIXEL = 1.8 / 32;
    private static final double[][] PLAYER = {
        {-4, 0, -2, 0, 12, 2, 0, 16, 12, 1, 0xFF2A4C8A}, {0, 0, -2, 4, 12, 2, 16, 48, 12, -1, 0xFF2A4C8A},
        {-4, 12, -2, 4, 24, 2, 16, 16, 24, 0, 0},
        {-8, 12, -2, -4, 24, 2, 40, 16, 22, -1, 0}, {4, 12, -2, 8, 24, 2, 32, 48, 22, 1, 0},
        {-4, 24, -4, 4, 32, 4, 0, 0, 24, 0, 0xFFE3B58A}};

    private void avatar(Snapshot.ItemView it, double a) {
        double[] at = it.vec("at", new double[]{0, 0, 0});
        double yaw = it.num("yaw", 0) * Math.PI / 2;
        double walk = Math.max(0, Math.min(1, it.num("walk", 0)));
        int shirt = color(it.str("color", "cyan"));
        BlockLook.Tex skin = looks.skin();
        double phase = it.age() * 9.0, swing = Math.sin(phase) * 0.8 * walk, bob = Math.abs(Math.cos(phase)) * 0.05 * walk;
        double c = Math.cos(yaw), s = Math.sin(yaw);
        // its shadow first: a soft dark disc on the ground under the feet
        double sr = 0.42;
        double[] mid = cam.project(at[0], at[1] + 0.02, at[2]);
        for (int i = 0; i < 20; i++) {
            double t0 = i * 2 * Math.PI / 20, t1 = (i + 1) * 2 * Math.PI / 20;
            double[] p0 = cam.project(at[0] + Math.cos(t0) * sr, at[1] + 0.02, at[2] + Math.sin(t0) * sr), p1 = cam.project(at[0] + Math.cos(t1) * sr, at[1] + 0.02, at[2] + Math.sin(t1) * sr);
            addTri(mid, p0, p1, null, null, null, null, 0xFF06142A, 1.0, WHITE, 0, 0, 0.32 * a, BLEND | OVER | FLAT);
        }
        for (double[] part : PLAYER) drawPart(at, c, s, bob, part, swing, skin, part[10] == 0 && part[1] >= 12 && part[4] < 30 ? shirt : (int) part[10], a, 0);
        if (skin != null) drawPart(at, c, s, bob, new double[]{-4, 24, -4, 4, 32, 4, 32, 0, 24, 0, 0}, 0, skin, 0, a, 0.5);
    }

    /** One box of the player, turned to the yaw, its limb swung about the pivot, textured from the skin (or one flat colour). */
    private void drawPart(double[] at, double c, double s, double bob, double[] p, double swing, BlockLook.@Nullable Tex skin, int fallback, double a, double grow) {
        double x0 = p[0] - grow, y0 = p[1] - grow, z0 = p[2] - grow, x1 = p[3] + grow, y1 = p[4] + grow, z1 = p[5] + grow;
        double w = p[3] - p[0], h = p[4] - p[1], d = p[5] - p[2];
        double u = p[6], v = p[7], pivot = p[8], rot = swing * p[9];
        // texture rectangles (pixels): right, front, left, back, top, bottom
        double[][] rect = {{u, v + d, d, h}, {u + d, v + d, w, h}, {u + d + w, v + d, d, h}, {u + 2 * d + w, v + d, w, h}, {u + d, v, w, d}, {u + d + w, v, w, d}};
        // corners of each face (x, y, z in the box), in the order top-left, top-right, bottom-right, bottom-left of its picture
        double[][][] face = {
            {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}},
            {{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}},
            {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}},
            {{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}},
            {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}},
            {{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}}};
        double[][] normal = {{-1, 0, 0}, {0, 0, 1}, {1, 0, 0}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        double cr = Math.cos(rot), sr = Math.sin(rot);
        double[][] corner = new double[4][3];
        double[][] cuv = new double[4][2];
        double[] uvx = {0, 1, 1, 0}, uvy = {0, 0, 1, 1};
        for (int f = 0; f < 6; f++) {
            double ny = normal[f][1] * cr - normal[f][2] * sr, nz = normal[f][1] * sr + normal[f][2] * cr, nx = normal[f][0];
            double wx = nx * c - nz * s, wz = nx * s + nz * c;
            if (!front(wx, ny, wz)) continue;
            for (int k = 0; k < 4; k++) {
                double lx = face[f][k][0], ly = face[f][k][1], lz = face[f][k][2];
                double dy = ly - pivot, rz = lz;
                double ry = pivot + dy * cr - rz * sr, rzz = dy * sr + rz * cr;
                double gx = lx * PIXEL, gy = ry * PIXEL + bob, gz = rzz * PIXEL;
                double[] sv = cam.project(at[0] + gx * c - gz * s, at[1] + gy, at[2] + gx * s + gz * c);
                corner[k][0] = sv[0];
                corner[k][1] = sv[1];
                corner[k][2] = sv[2];
                cuv[k][0] = skin == null ? 0 : (rect[f][0] + uvx[k] * rect[f][2]) / skin.w();
                cuv[k][1] = skin == null ? 0 : (rect[f][1] + uvy[k] * rect[f][3]) / skin.h();
            }
            double light = shade(wx, ny, wz);
            quad(corner, cuv, skin != null && skin.px() != null ? skin : null, fallback, light, WHITE, 0, 0, a, a < 0.995 ? BLEND : 0);
        }
    }

    /** Footsteps: a pair of small boot prints, left and right in turn, along a path on the ground. */
    private void path(Snapshot.ItemView it, double a) {
        List<double[]> pts = it.props().points("points");
        int color = color(it.str("color", "dim"));
        int step = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] p = pts.get(i), q = pts.get(i + 1);
            double dx = q[0] - p[0], dz = q[2] - p[2], len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) continue;
            dx /= len;
            dz /= len;
            int n = Math.max(1, (int) (len / 0.62));
            for (int k = 0; k <= n; k++, step++) {
                double f = k / (double) n;
                double side = step % 2 == 0 ? 0.13 : -0.13;
                double x = p[0] + (q[0] - p[0]) * f - dz * side, y = p[1] + (q[1] - p[1]) * f + 0.03, z = p[2] + (q[2] - p[2]) * f + dx * side;
                boot(x, y, z, dx, dz, color, a);
            }
        }
    }

    /** One boot print: a long oval with a smaller one for the heel, pointing the way it walks. */
    private void boot(double x, double y, double z, double dx, double dz, int color, double a) {
        double px = -dz, pz = dx;
        for (int part = 0; part < 2; part++) {
            double cx = x + dx * (part == 0 ? 0.07 : -0.1), cz = z + dz * (part == 0 ? 0.07 : -0.1);
            double hl = part == 0 ? 0.16 : 0.09, hw = part == 0 ? 0.11 : 0.08;
            double[] mid = cam.project(cx, y, cz);
            int m = 8;
            for (int i = 0; i < m; i++) {
                double t0 = i * 2 * Math.PI / m, t1 = (i + 1) * 2 * Math.PI / m;
                double[] p0 = cam.project(cx + dx * Math.cos(t0) * hl + px * Math.sin(t0) * hw, y, cz + dz * Math.cos(t0) * hl + pz * Math.sin(t0) * hw);
                double[] p1 = cam.project(cx + dx * Math.cos(t1) * hl + px * Math.sin(t1) * hw, y, cz + dz * Math.cos(t1) * hl + pz * Math.sin(t1) * hw);
                flatTri(mid, p0, p1, color, 0.85 * a, BLEND);
            }
        }
    }
}
