package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Draws a blueprint from any side, in software: the picture in the Save screen that can be turned, zoomed and dragged, and where
 * a block can be pointed at. No game rendering is needed, so it runs on a worker thread. Only the faces that have air in front of
 * them are kept (a {@link Scene}); each is drawn as two triangles with a depth buffer, textured with the block's real face texture
 * ({@link BlockLook}, resource packs included) once a block is big enough on screen to show it and flat in its average colour
 * below that, shaded by the way it faces under a light that stays where it is as the build turns. The camera looks straight on (no
 * perspective), so sizes stay true when zooming. Besides the picture it fills an id buffer, so the block under a pixel is known.
 */
public final class PreviewRaster {
    private PreviewRaster() {
    }

    /** Boxes with more cells than this are not previewed (the grid of cells would not fit in memory sensibly). */
    public static final int MAX_CELLS = 16_000_000;
    /** More faces than this are thinned out (every n-th is kept) and the preview says so. */
    public static final int MAX_FACES = 6_000_000;
    /** Blocks drawn smaller than this many pixels across are flat colour: the texture would only shimmer. */
    static final double TEXTURE_FROM = 5.0;

    /** The faces to draw: {@code faces[i] = cell << 3 | direction} (+x, -x, +y, -y, +z, -z); {@code cells[c]} is the look's number plus one (0 = air). */
    public static final class Scene {
        public final int ex, ey, ez;
        final int[] cells;
        final BlockLook.Look[] looks;
        final int[] faces;
        /** Cells whose block is not a plain cube (stairs, torches, plants...) and can be seen: their model's quads are drawn. */
        final int[] custom;
        public final int count;
        /** True when there were too many faces and only some are kept. */
        public final boolean thinned;

        Scene(int ex, int ey, int ez, int[] cells, BlockLook.Look[] looks, int[] faces, int[] custom, int count, boolean thinned) {
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            this.cells = cells;
            this.looks = looks;
            this.faces = faces;
            this.custom = custom;
            this.count = count;
            this.thinned = thinned;
        }

        /** Half the length of the diagonal of the box: how far from the middle anything can be. */
        public double radius() {
            return 0.5 * Math.sqrt((double) ex * ex + (double) ey * ey + (double) ez * ez);
        }

        /** The cell a face belongs to, as an index into the box (x fastest, then z, then y). */
        public int cellOfFace(int face) {
            return faces[face] >>> 3;
        }

        /** The cell an id of the id buffer names: a cube face (id - 1 is its number) or, negative, a cell drawn from its model (-id - 1 is the cell); -1 for nothing. */
        public int cellOfId(int id) {
            if (id > 0) return id - 1 < count ? cellOfFace(id - 1) : -1;
            if (id < 0) return -id - 1 < cells.length ? -id - 1 : -1;
            return -1;
        }

        /** Whether anything is drawn at all. */
        public boolean empty() {
            return count == 0 && custom.length == 0;
        }
    }

    /** What one render makes: the picture, and for every pixel the number of the face drawn there plus one (0 = nothing). */
    public record Frame(int w, int h, int[] px, int[] ids) {
    }

    /** The camera: turn about the vertical axis, tilt, zoom (1 = the whole build fits), and where the middle is on the picture, in pixels from its centre. */
    public record View(double yaw, double pitch, double zoom, double panX, double panY) {
        public static final View HOME = new View(0.785, 0.52, 1.0, 0, 0);
    }

    private static final int[][] N = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** The four corners of each face on the unit cube, wound so the first three make a triangle with the face's outward normal. */
    private static final int[][][] CORNERS = {
        {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
        {{0, 0, 1}, {0, 1, 1}, {0, 1, 0}, {0, 0, 0}},
        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
        {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
        {{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}},
        {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
    };
    private static final double LX = -0.35, LY = 0.85, LZ = 0.40;
    private static final double[] LIGHT = shades();

    private static double[] shades() {
        double len = Math.sqrt(LX * LX + LY * LY + LZ * LZ);
        double[] out = new double[6];
        for (int d = 0; d < 6; d++) {
            double dot = (N[d][0] * LX + N[d][1] * LY + N[d][2] * LZ) / len;
            out[d] = 0.46 + 0.54 * Math.max(0, dot);
        }
        return out;
    }

    /** Texture coordinates of a point of a face (u to the right and v down as seen from outside; on a top, up the picture is north). */
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

    /** Collects what can be seen of a blueprint, or null when the box is too large to preview. */
    public static @Nullable Scene scene(Blueprint bp) {
        int ex = bp.sizeX, ey = bp.sizeY, ez = bp.sizeZ;
        if (ex <= 0 || ey <= 0 || ez <= 0 || (long) ex * ey * ez > MAX_CELLS) return null;
        int[] cells = new int[ex * ey * ez];
        Map<BlockState, Integer> ids = new HashMap<>();
        java.util.List<BlockLook.Look> looks = new java.util.ArrayList<>();
        for (Region r : bp.regions) {
            int[] pal = new int[r.palette.length];
            for (int i = 0; i < pal.length; i++) {
                PaletteEntry e = r.palette[i];
                if (e.isAir()) continue;
                BlockState st = e.state();
                Integer id = ids.get(st);
                if (id == null) {
                    looks.add(BlockLook.of(st));
                    id = looks.size();
                    ids.put(st, id);
                }
                pal[i] = id;
            }
            for (int y = 0; y < r.sy; y++) {
                for (int z = 0; z < r.sz; z++) {
                    for (int x = 0; x < r.sx; x++) {
                        int c = pal[r.blocks[(y * r.sz + z) * r.sx + x] & 0xFFFF];
                        if (c == 0) continue;
                        cells[((r.y - bp.minY + y) * ez + (r.z - bp.minZ + z)) * ex + (r.x - bp.minX + x)] = c;
                    }
                }
            }
        }
        return scene(cells, looks.toArray(new BlockLook.Look[0]), ex, ey, ez);
    }

    /** From a grid of colours (0 = air), for tests: every distinct colour is a flat look. */
    static @Nullable Scene sceneOfColors(int[] colors, int ex, int ey, int ez) {
        Map<Integer, Integer> ids = new HashMap<>();
        java.util.List<BlockLook.Look> looks = new java.util.ArrayList<>();
        int[] cells = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] == 0) continue;
            Integer id = ids.get(colors[i]);
            if (id == null) {
                looks.add(BlockLook.Look.solid(colors[i]));
                id = looks.size();
                ids.put(colors[i], id);
            }
            cells[i] = id;
        }
        return scene(cells, looks.toArray(new BlockLook.Look[0]), ex, ey, ez);
    }

    static @Nullable Scene scene(int[] cells, BlockLook.Look[] looks, int ex, int ey, int ez) {
        // what hides the faces behind it: a plain cube that is not see-through; water shows what is behind it and is no wall
        boolean[] cube = new boolean[looks.length + 1], water = new boolean[looks.length + 1], isCube = new boolean[looks.length + 1];
        for (int i = 0; i < looks.length; i++) {
            water[i + 1] = looks[i].translucent();
            isCube[i + 1] = looks[i].cube();
            cube[i + 1] = looks[i].cube() && !looks[i].translucent();
        }
        // the cells drawn from their model: not plain cubes, and not shut in by cubes on every side
        java.util.ArrayList<Integer> customList = new java.util.ArrayList<>();
        long total = 0;
        for (int pass = 0; pass < 2; pass++) {
            // the first pass counts the faces, the second keeps them (every n-th when there are too many)
            int keep = pass == 0 ? 0 : (int) Math.min(total, MAX_FACES);
            int step = pass == 0 || total <= MAX_FACES ? 1 : (int) Math.ceil(total / (double) MAX_FACES);
            int[] faces = pass == 0 ? null : new int[keep + 8];
            int n = 0;
            long seen = 0;
            for (int y = 0; y < ey; y++) {
                for (int z = 0; z < ez; z++) {
                    int row = (y * ez + z) * ex;
                    for (int x = 0; x < ex; x++) {
                        int i = row + x;
                        int c = cells[i];
                        if (c == 0) continue;
                        if (!isCube[c]) {
                            if (pass == 0 && !enclosed(cells, cube, x, y, z, ex, ey, ez)) customList.add(i);
                            continue;
                        }
                        for (int d = 0; d < 6; d++) {
                            int nx = x + N[d][0], ny = y + N[d][1], nz = z + N[d][2];
                            boolean outside = nx < 0 || ny < 0 || nz < 0 || nx >= ex || ny >= ey || nz >= ez;
                            int nc = outside ? 0 : cells[(ny * ez + nz) * ex + nx];
                            // an opaque face is open next to air, a model or water; a water face only next to air or a model (not next to water or a solid)
                            boolean open = water[c] ? nc == 0 || (!cube[nc] && !water[nc]) : !cube[nc];
                            if (!open) continue;
                            if (pass == 0) {
                                total++;
                            } else if (seen++ % step == 0 && n < keep) {
                                faces[n++] = i << 3 | d;
                            }
                        }
                    }
                }
            }
            if (pass == 1) {
                int[] custom = new int[customList.size()];
                for (int k = 0; k < custom.length; k++) custom[k] = customList.get(k);
                return new Scene(ex, ey, ez, cells, looks, faces, custom, n, total > MAX_FACES);
            }
            if (total == 0) {
                int[] custom = new int[customList.size()];
                for (int k = 0; k < custom.length; k++) custom[k] = customList.get(k);
                return new Scene(ex, ey, ez, cells, looks, new int[0], custom, 0, false);
            }
        }
        return null;
    }

    /** Whether a cell has a plain cube on every one of its six sides (then nothing of its model can be seen). */
    private static boolean enclosed(int[] cells, boolean[] cube, int x, int y, int z, int ex, int ey, int ez) {
        for (int d = 0; d < 6; d++) {
            int nx = x + N[d][0], ny = y + N[d][1], nz = z + N[d][2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= ex || ny >= ey || nz >= ez || !cube[cells[(ny * ez + nz) * ex + nx]]) return false;
        }
        return true;
    }

    /** How many pixels a block is across when the whole build just fits a picture of this size. */
    public static double fit(Scene s, int w, int h) {
        return Math.min(w, h) * 0.92 / (2 * Math.max(1.0, s.radius()));
    }

    /**
     * Draws the scene.
     *
     * @param stride draw every n-th face only (1 = all of them): used while the picture is being dragged, to stay fast on big builds
     * @param hover  the cell to light up (an index into the box), or -1
     */
    public static Frame render(Scene s, View v, int w, int h, int stride, int hover) {
        int[] px = new int[w * h], ids = new int[w * h];
        if (s.empty()) return new Frame(w, h, px, ids);
        double[] depth = new double[w * h];
        Arrays.fill(depth, -Double.MAX_VALUE);
        double cy = Math.cos(v.yaw), sy = Math.sin(v.yaw), cp = Math.cos(v.pitch), sp = Math.sin(v.pitch);
        double scale = fit(s, w, h) * v.zoom;
        boolean textured = scale >= TEXTURE_FROM;
        double ox = w / 2.0 + v.panX, oy = h / 2.0 + v.panY;
        double mx = s.ex / 2.0, my = s.ey / 2.0, mz = s.ez / 2.0;
        // which way each face turns in the camera's space; a face looking away is skipped
        boolean[] front = new boolean[6];
        for (int d = 0; d < 6; d++) {
            double nz = N[d][0] * sy + N[d][2] * cy;
            front[d] = N[d][1] * sp + nz * cp > 1e-9;
        }
        double[] fx = new double[4], fy = new double[4], fz = new double[4], fu = new double[4], fv = new double[4];
        // two sweeps: everything solid first, then the water over it (blended, not written to the depth buffer)
        for (int sweep = 0; sweep < 2; sweep++) {
        for (int k = 0; k < s.count; k += stride) {
            int code = s.faces[k];
            int d = code & 7, i = code >>> 3;
            if (!front[d]) continue;
            boolean wet = s.looks[s.cells[i] - 1].translucent();
            if (wet != (sweep == 1)) continue;
            // the surface of water is a little below the top of its block
            boolean lower = wet && !(i + s.ex * s.ez < s.cells.length && s.looks[Math.max(0, s.cells[i + s.ex * s.ez] - 1)].translucent() && s.cells[i + s.ex * s.ez] != 0);
            int x = i % s.ex, z = (i / s.ex) % s.ez, y = i / (s.ex * s.ez);
            for (int c = 0; c < 4; c++) {
                int[] cc = CORNERS[d][c];
                double qx = x + cc[0] - mx, qy = y + (lower && cc[1] == 1 ? 0.89 : cc[1]) - my, qz = z + cc[2] - mz;
                double rx = qx * cy - qz * sy, rz = qx * sy + qz * cy;
                fx[c] = ox + rx * scale;
                fy[c] = oy - (qy * cp - rz * sp) * scale;
                fz[c] = qy * sp + rz * cp;
                fu[c] = u(d, cc[0], cc[2]);
                fv[c] = v(d, cc[1], cc[2]);
            }
            BlockLook.Tex tex = s.looks[s.cells[i] - 1].face()[d];
            boolean useTexture = textured && tex.px() != null;
            double light = LIGHT[d] * (useTexture ? 1.0 : 0.94 + 0.12 * jitter(i));
            boolean hot = i == hover;
            tri(px, ids, depth, w, h, fx[0], fy[0], fz[0], fu[0], fv[0], fx[1], fy[1], fz[1], fu[1], fv[1], fx[2], fy[2], fz[2], fu[2], fv[2], tex, useTexture, light, hot, k + 1, wet);
            tri(px, ids, depth, w, h, fx[0], fy[0], fz[0], fu[0], fv[0], fx[2], fy[2], fz[2], fu[2], fv[2], fx[3], fy[3], fz[3], fu[3], fv[3], tex, useTexture, light, hot, k + 1, wet);
        }
        }
        // the cells that are not cubes: every quad of their model, seen from either side
        double[] qx = new double[4], qy = new double[4], qz = new double[4];
        for (int k = 0; k < s.custom.length; k += stride) {
            int i = s.custom[k];
            int x = i % s.ex, z = (i / s.ex) % s.ez, y = i / (s.ex * s.ez);
            BlockLook.Quad[] model = s.looks[s.cells[i] - 1].model();
            if (model == null) continue;
            for (BlockLook.Quad q : model) {
                for (int c = 0; c < 4; c++) {
                    double wx = x + q.p()[c * 3] - mx, wy = y + q.p()[c * 3 + 1] - my, wz = z + q.p()[c * 3 + 2] - mz;
                    double rx = wx * cy - wz * sy, rz = wx * sy + wz * cy;
                    qx[c] = ox + rx * scale;
                    qy[c] = oy - (wy * cp - rz * sp) * scale;
                    qz[c] = wy * sp + rz * cp;
                }
                double light = LIGHT[q.dir()] * 0.92 + 0.08;
                boolean hot = i == hover;
                boolean useTexture = q.tex().px() != null;
                float[] uv = q.uv();
                tri(px, ids, depth, w, h, qx[0], qy[0], qz[0], uv[0], uv[1], qx[1], qy[1], qz[1], uv[2], uv[3], qx[2], qy[2], qz[2], uv[4], uv[5], q.tex(), useTexture, light, hot, -(i + 1), false);
                tri(px, ids, depth, w, h, qx[0], qy[0], qz[0], uv[0], uv[1], qx[2], qy[2], qz[2], uv[4], uv[5], qx[3], qy[3], qz[3], uv[6], uv[7], q.tex(), useTexture, light, hot, -(i + 1), false);
            }
        }
        return new Frame(w, h, px, ids);
    }

    /** A steady number in 0..1 for a cell, so the variation between blocks does not flicker as the view moves. */
    private static double jitter(int cell) {
        int h = cell * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 65535.0;
    }

    private static int blend(int dst, int src, double a) {
        int r = (int) (((src >> 16) & 255) * a + ((dst >> 16) & 255) * (1 - a)), g = (int) (((src >> 8) & 255) * a + ((dst >> 8) & 255) * (1 - a)), b = (int) ((src & 255) * a + (dst & 255) * (1 - a));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int shade(int argb, double k, boolean hot) {
        int r = (int) Math.min(255, ((argb >> 16) & 255) * k), g = (int) Math.min(255, ((argb >> 8) & 255) * k), b = (int) Math.min(255, (argb & 255) * k);
        if (hot) {
            r = (r * 45 + 255 * 55) / 100;
            g = (g * 45 + 90 * 55) / 100;
            b = (b * 45 + 90 * 55) / 100;
        }
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void tri(int[] px, int[] ids, double[] depth, int w, int h,
                            double x0, double y0, double z0, double u0, double v0,
                            double x1, double y1, double z1, double u1, double v1,
                            double x2, double y2, double z2, double u2, double v2,
                            BlockLook.Tex tex, boolean useTexture, double light, boolean hot, int id, boolean wet) {
        double minX = Math.min(x0, Math.min(x1, x2)), maxX = Math.max(x0, Math.max(x1, x2));
        double minY = Math.min(y0, Math.min(y1, y2)), maxY = Math.max(y0, Math.max(y1, y2));
        int ix0 = Math.max(0, (int) Math.floor(minX)), ix1 = Math.min(w - 1, (int) Math.ceil(maxX));
        int iy0 = Math.max(0, (int) Math.floor(minY)), iy1 = Math.min(h - 1, (int) Math.ceil(maxY));
        if (ix0 > ix1 || iy0 > iy1) return;
        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-9) return;
        double inv = 1.0 / area;
        int flat = shade(tex.avg(), light, hot);
        int[] texels = tex.px();
        int tw = tex.w(), th = tex.h();
        for (int y = iy0; y <= iy1; y++) {
            double py = y + 0.5;
            for (int x = ix0; x <= ix1; x++) {
                double pxx = x + 0.5;
                double w0 = ((x1 - pxx) * (y2 - py) - (x2 - pxx) * (y1 - py)) * inv;
                double w1 = ((x2 - pxx) * (y0 - py) - (x0 - pxx) * (y2 - py)) * inv;
                double w2 = 1 - w0 - w1;
                // a pixel counts when its middle is inside, with a little give so neighbouring faces leave no cracks
                if (w0 < -0.02 || w1 < -0.02 || w2 < -0.02) continue;
                double z = w0 * z0 + w1 * z1 + w2 * z2;
                int at = y * w + x;
                if (z <= depth[at]) continue;
                if (wet) {
                    // water: blended over what is there, with no depth written, so what is behind it still shows and can still be pointed at
                    int dst = px[at];
                    int src = flat;
                    px[at] = dst == 0 ? (0xB0 << 24 | (src & 0xFFFFFF)) : blend(dst, src, 0.55);
                    if (ids[at] == 0) ids[at] = id;
                    continue;
                }
                int color = flat;
                if (useTexture) {
                    double uu = Math.max(0, Math.min(0.9999, w0 * u0 + w1 * u1 + w2 * u2)), vv = Math.max(0, Math.min(0.9999, w0 * v0 + w1 * v1 + w2 * v2));
                    int t = texels[(int) (vv * th) * tw + (int) (uu * tw)];
                    if ((t >>> 24) < 128) continue;
                    color = shade(t, light, hot);
                }
                depth[at] = z;
                px[at] = color;
                ids[at] = id;
            }
        }
    }
}
