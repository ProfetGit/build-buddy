package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Draws a blueprint from any side, in software, from the blocks' map colours: the picture in the Save screen that can be turned,
 * zoomed and dragged. No game rendering is needed, so it runs on a worker thread. Only the faces that have air in front of
 * them are kept (a {@link Scene}), each face is drawn as two flat triangles with a depth buffer, shaded by the way it faces and
 * by a light that stays where it is as the build turns, with a few per cent of variation from block to block so a wall
 * reads as blocks and not as one slab. The camera looks straight on (no perspective), so sizes stay true when zooming.
 */
public final class PreviewRaster {
    private PreviewRaster() {
    }

    /** Boxes with more cells than this are not previewed (the grid of colours would not fit in memory sensibly). */
    public static final int MAX_CELLS = 16_000_000;
    /** More faces than this are thinned out (every n-th is kept) and the preview says so. */
    public static final int MAX_FACES = 6_000_000;

    /** The faces to draw: {@code faces[i] = cell << 3 | direction} (+x, -x, +y, -y, +z, -z), and the colour of every cell (0 = air). */
    public static final class Scene {
        public final int ex, ey, ez;
        final int[] grid;
        final int[] faces;
        public final int count;
        /** True when there were too many faces and only some are kept. */
        public final boolean thinned;

        Scene(int ex, int ey, int ez, int[] grid, int[] faces, int count, boolean thinned) {
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            this.grid = grid;
            this.faces = faces;
            this.count = count;
            this.thinned = thinned;
        }

        /** Half the length of the diagonal of the box: how far from the middle anything can be. */
        public double radius() {
            return 0.5 * Math.sqrt((double) ex * ex + (double) ey * ey + (double) ez * ez);
        }
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

    /** The colour of a block in the picture. */
    static int colorOf(net.minecraft.world.level.block.state.BlockState s) {
        return Thumbnail.colorOf(s);
    }

    /** Collects what can be seen of a blueprint, or null when the box is empty or too large to preview. */
    public static @Nullable Scene scene(Blueprint bp) {
        int ex = bp.sizeX, ey = bp.sizeY, ez = bp.sizeZ;
        if (ex <= 0 || ey <= 0 || ez <= 0 || (long) ex * ey * ez > MAX_CELLS) return null;
        int[] grid = new int[ex * ey * ez];
        for (Region r : bp.regions) {
            int[] pal = new int[r.palette.length];
            for (int i = 0; i < pal.length; i++) {
                PaletteEntry e = r.palette[i];
                pal[i] = e.isAir() ? 0 : colorOf(e.state());
            }
            for (int y = 0; y < r.sy; y++) {
                for (int z = 0; z < r.sz; z++) {
                    for (int x = 0; x < r.sx; x++) {
                        int c = pal[r.blocks[(y * r.sz + z) * r.sx + x] & 0xFFFF];
                        if (c == 0) continue;
                        grid[((r.y - bp.minY + y) * ez + (r.z - bp.minZ + z)) * ex + (r.x - bp.minX + x)] = c;
                    }
                }
            }
        }
        return scene(grid, ex, ey, ez);
    }

    /** From a grid of colours (0 = air), x fastest, then z, then y. */
    static @Nullable Scene scene(int[] grid, int ex, int ey, int ez) {
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
                        if (grid[i] == 0) continue;
                        for (int d = 0; d < 6; d++) {
                            int nx = x + N[d][0], ny = y + N[d][1], nz = z + N[d][2];
                            boolean open = nx < 0 || ny < 0 || nz < 0 || nx >= ex || ny >= ey || nz >= ez || grid[(ny * ez + nz) * ex + nx] == 0;
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
            if (pass == 1) return new Scene(ex, ey, ez, grid, faces, n, total > MAX_FACES);
            if (total == 0) return new Scene(ex, ey, ez, grid, new int[0], 0, false);
        }
        return null;
    }

    /** How many pixels a block is across when the whole build just fits a picture of this size. */
    public static double fit(Scene s, int w, int h) {
        return Math.min(w, h) * 0.92 / (2 * Math.max(1.0, s.radius()));
    }

    /**
     * Draws the scene.
     *
     * @param stride draw every n-th face only (1 = all of them): used while the picture is being dragged, to stay fast on big builds
     * @return {@code w * h} ARGB pixels, 0 where nothing is
     */
    public static int[] render(Scene s, View v, int w, int h, int stride) {
        int[] px = new int[w * h];
        if (s.count == 0) return px;
        double[] depth = new double[w * h];
        Arrays.fill(depth, -Double.MAX_VALUE);
        double cy = Math.cos(v.yaw), sy = Math.sin(v.yaw), cp = Math.cos(v.pitch), sp = Math.sin(v.pitch);
        double scale = fit(s, w, h) * v.zoom;
        double ox = w / 2.0 + v.panX, oy = h / 2.0 + v.panY;
        double mx = s.ex / 2.0, my = s.ey / 2.0, mz = s.ez / 2.0;
        // which way each face turns in the camera's space; a face looking away is skipped
        boolean[] front = new boolean[6];
        for (int d = 0; d < 6; d++) {
            double nx = N[d][0] * cy - N[d][2] * sy, nz = N[d][0] * sy + N[d][2] * cy;
            front[d] = N[d][1] * sp + nz * cp > 1e-9;
        }
        double[] fx = new double[4], fy = new double[4], fz = new double[4];
        for (int k = 0; k < s.count; k += stride) {
            int code = s.faces[k];
            int d = code & 7, i = code >>> 3;
            if (!front[d]) continue;
            int x = i % s.ex, z = (i / s.ex) % s.ez, y = i / (s.ex * s.ez);
            for (int c = 0; c < 4; c++) {
                double qx = x + CORNERS[d][c][0] - mx, qy = y + CORNERS[d][c][1] - my, qz = z + CORNERS[d][c][2] - mz;
                double rx = qx * cy - qz * sy, rz = qx * sy + qz * cy;
                fx[c] = ox + rx * scale;
                fy[c] = oy - (qy * cp - rz * sp) * scale;
                fz[c] = qy * sp + rz * cp;
            }
            int color = shade(s.grid[i], LIGHT[d] * (0.94 + 0.12 * jitter(i)));
            tri(px, depth, w, h, fx[0], fy[0], fz[0], fx[1], fy[1], fz[1], fx[2], fy[2], fz[2], color);
            tri(px, depth, w, h, fx[0], fy[0], fz[0], fx[2], fy[2], fz[2], fx[3], fy[3], fz[3], color);
        }
        return px;
    }

    /** A steady number in 0..1 for a cell, so the variation between blocks does not flicker as the view moves. */
    private static double jitter(int cell) {
        int h = cell * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 65535.0;
    }

    private static int shade(int argb, double k) {
        int r = (int) Math.min(255, ((argb >> 16) & 255) * k), g = (int) Math.min(255, ((argb >> 8) & 255) * k), b = (int) Math.min(255, (argb & 255) * k);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void tri(int[] px, double[] depth, int w, int h, double x0, double y0, double z0, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        double minX = Math.min(x0, Math.min(x1, x2)), maxX = Math.max(x0, Math.max(x1, x2));
        double minY = Math.min(y0, Math.min(y1, y2)), maxY = Math.max(y0, Math.max(y1, y2));
        int ix0 = Math.max(0, (int) Math.floor(minX)), ix1 = Math.min(w - 1, (int) Math.ceil(maxX));
        int iy0 = Math.max(0, (int) Math.floor(minY)), iy1 = Math.min(h - 1, (int) Math.ceil(maxY));
        if (ix0 > ix1 || iy0 > iy1) return;
        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-9) return;
        double inv = 1.0 / area;
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
                if (z > depth[at]) {
                    depth[at] = z;
                    px[at] = color;
                }
            }
        }
    }
}
