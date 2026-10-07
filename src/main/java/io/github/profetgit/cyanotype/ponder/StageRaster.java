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

    public static Camera camera(Scene s, Snapshot snap, int w, int h) {
        double radius = 0.5 * Math.sqrt((double) s.size[0] * s.size[0] + (double) s.size[1] * s.size[1] + (double) s.size[2] * s.size[2]);
        double scale = Math.min(w, h) * 0.92 / (2 * Math.max(1.0, radius)) * snap.zoom;
        return new Camera(snap.yaw, snap.pitch, scale, w / 2.0, h / 2.0, snap.focus[0], snap.focus[1], snap.focus[2]);
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

    /** Blueprint paper: the grid of the ground plane, going on well past the stage. */
    private void grid(Snapshot snap) {
        int pad = 5;
        int x0 = -pad, x1 = scene.size[0] + pad, z0 = -pad, z1 = scene.size[2] + pad;
        for (int x = x0; x <= x1; x++) line(x, 0, z0, x, 0, z1, GRID, 0.5, 1.0, UNDER | NOTEST);
        for (int z = z0; z <= z1; z++) line(x0, 0, z, x1, 0, z, GRID, 0.5, 1.0, UNDER | NOTEST);
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
        double w = 0.11 * k, hw = 0.3 * k, shaft = len * 0.62;
        double[] ax = new double[3], u = new double[3], v = new double[3];
        ax[axis] = sign;
        u[(axis + 1) % 3] = 1;
        v[(axis + 2) % 3] = 1;
        double[] s0 = base, s1 = {base[0] + ax[0] * shaft, base[1] + ax[1] * shaft, base[2] + ax[2] * shaft}, tip = {base[0] + ax[0] * len * k, base[1] + ax[1] * len * k, base[2] + ax[2] * len * k};
        // the shaft: a box
        double[][] ring0 = square(s0, u, v, w), ring1 = square(s1, u, v, w);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            shadedQuad(ring0[i], ring0[j], ring1[j], ring1[i], color, a, true);
        }
        shadedQuad(ring1[0], ring1[1], ring1[2], ring1[3], color, a, true);
        // the head: a pyramid
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
        double x0 = at[0], y0 = at[1], z0 = at[2], x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        double g = 0.01;
        x0 -= g;
        y0 -= g;
        z0 -= g;
        x1 += g;
        y1 += g;
        z1 += g;
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        double[][] faces = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
        for (double[] f : faces) {
            double[] p0 = cam.project(c[(int) f[0]][0], c[(int) f[0]][1], c[(int) f[0]][2]), p1 = cam.project(c[(int) f[1]][0], c[(int) f[1]][1], c[(int) f[1]][2]), p2 = cam.project(c[(int) f[2]][0], c[(int) f[2]][1], c[(int) f[2]][2]),
                p3 = cam.project(c[(int) f[3]][0], c[(int) f[3]][1], c[(int) f[3]][2]);
            double al = (0.10 + 0.12 * pulse) * a;
            flatTri(p0, p1, p2, color, al, BLEND | NOTEST);
            flatTri(p0, p2, p3, color, al, BLEND | NOTEST);
        }
        int edge = mix(WHITE, color, 0.3 * (1 - pulse));
        for (int[] e : edges) line(c[e[0]][0], c[e[0]][1], c[e[0]][2], c[e[1]][0], c[e[1]][1], c[e[1]][2], edge, 2.0, (0.75 + 0.25 * pulse) * a, BLEND | NOTEST);
        if (it.bool("beam", true)) {
            double mx = (x0 + x1) / 2, mz = (z0 + z1) / 2, top = y1;
            double beam = 2.6 + 0.2 * pulse;
            line(mx, top, mz, mx, top + beam, mz, color, 2.2, 0.85 * a, BLEND | NOTEST);
            double tipY = top + 0.15;
            line(mx, tipY, mz, mx + 0.35, tipY + 0.55, mz, color, 2.2, 0.9 * a, BLEND | NOTEST);
            line(mx, tipY, mz, mx - 0.35, tipY + 0.55, mz, color, 2.2, 0.9 * a, BLEND | NOTEST);
            line(mx, tipY, mz, mx, tipY + 0.55, mz + 0.35, color, 2.2, 0.9 * a, BLEND | NOTEST);
            line(mx, tipY, mz, mx, tipY + 0.55, mz - 0.35, color, 2.2, 0.9 * a, BLEND | NOTEST);
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

    /** A little player: legs, body, head, standing at the point, turned by the yaw (quarter turns). */
    private void avatar(Snapshot.ItemView it, double a) {
        double[] at = it.vec("at", new double[]{0, 0, 0});
        double yaw = it.num("yaw", 0) * Math.PI / 2;
        int shirt = color(it.str("color", "cyan"));
        double c = Math.cos(yaw), s = Math.sin(yaw);
        double[][] parts = {
            // x0, y0, z0, x1, y1, z1, colour
            {-0.25, 0, -0.12, 0, 0.75, 0.12, 0xFF2A4C8A}, {0, 0, -0.12, 0.25, 0.75, 0.12, 0xFF2A4C8A},
            {-0.25, 0.75, -0.15, 0.25, 1.45, 0.15, shirt}, {-0.5, 0.75, -0.12, -0.25, 1.45, 0.12, shirt}, {0.25, 0.75, -0.12, 0.5, 1.45, 0.12, shirt},
            {-0.25, 1.45, -0.25, 0.25, 1.95, 0.25, 0xFFE3B58A}};
        for (double[] b : parts) {
            double[][] v = new double[8][3];
            int k = 0;
            for (int xi = 0; xi < 2; xi++) {
                for (int yi = 0; yi < 2; yi++) {
                    for (int zi = 0; zi < 2; zi++) {
                        double lx = xi == 0 ? b[0] : b[3], ly = yi == 0 ? b[1] : b[4], lz = zi == 0 ? b[2] : b[5];
                        v[k][0] = at[0] + lx * c - lz * s;
                        v[k][1] = at[1] + ly;
                        v[k][2] = at[2] + lx * s + lz * c;
                        k++;
                    }
                }
            }
            int col = (int) b[6];
            // vertex index = xi * 4 + yi * 2 + zi
            int[][] sides = {{4, 5, 7, 6}, {0, 2, 3, 1}, {2, 6, 7, 3}, {0, 1, 5, 4}, {1, 3, 7, 5}, {0, 4, 6, 2}};
            for (int[] sd : sides) shadedQuadTested(v[sd[0]], v[sd[1]], v[sd[2]], v[sd[3]], col, a);
        }
    }

    private void shadedQuadTested(double[] a, double[] b, double[] c, double[] d, int color, double alpha) {
        double nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
        double ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]);
        double nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12) return;
        nx /= len;
        ny /= len;
        nz /= len;
        // the picture's own front test: only the sides that face the camera
        if (!front(nx, ny, nz)) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
            if (!front(nx, ny, nz)) return;
            double[] t = b;
            b = d;
            d = t;
        }
        int col = lit(color, shade(nx, ny, nz), WHITE, 0, 0);
        double[] pa = cam.project(a[0], a[1], a[2]), pb = cam.project(b[0], b[1], b[2]), pc = cam.project(c[0], c[1], c[2]), pd = cam.project(d[0], d[1], d[2]);
        // opaque, with depth, so the blocks hide it and it hides them
        addTri(pa, pb, pc, null, null, null, null, col, 1.0, WHITE, 0, 0, alpha, alpha < 0.995 ? BLEND : 0);
        addTri(pa, pc, pd, null, null, null, null, col, 1.0, WHITE, 0, 0, alpha, alpha < 0.995 ? BLEND : 0);
    }

    /** Footsteps: small squares along a path on the ground. */
    private void path(Snapshot.ItemView it, double a) {
        List<double[]> pts = it.props().points("points");
        int color = color(it.str("color", "dim"));
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] p = pts.get(i), q = pts.get(i + 1);
            double len = Math.sqrt((q[0] - p[0]) * (q[0] - p[0]) + (q[2] - p[2]) * (q[2] - p[2]));
            int n = Math.max(1, (int) (len / 0.6));
            for (int k = 0; k <= n; k++) {
                double f = k / (double) n;
                double x = p[0] + (q[0] - p[0]) * f, y = p[1] + (q[1] - p[1]) * f + 0.03, z = p[2] + (q[2] - p[2]) * f;
                double r = 0.11;
                double[] a0 = cam.project(x - r, y, z - r), a1 = cam.project(x + r, y, z - r), a2 = cam.project(x + r, y, z + r), a3 = cam.project(x - r, y, z + r);
                flatTri(a0, a1, a2, color, 0.8 * a, BLEND | NOTEST);
                flatTri(a0, a2, a3, color, 0.8 * a, BLEND | NOTEST);
            }
        }
    }
}
