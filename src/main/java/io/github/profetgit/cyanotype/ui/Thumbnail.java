package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import java.util.Arrays;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A small isometric picture of a blueprint, made in software from the blocks' map colours: no game rendering is needed,
 * so it can be done on a worker thread for any number of files. {@link #frames} returns the same picture from several
 * turns around the vertical axis; the library plays them in sequence on the card under the mouse (the "rotating mini
 * preview"). Cells hidden inside the build are skipped, and a depth buffer decides what is in front.
 */
public final class Thumbnail {
    private Thumbnail() {
    }

    /** The colour of a block for the picture: the average of its real textures (resource pack included), or its map colour when there is no game. */
    static int colorOf(BlockState s) {
        return BlockLook.average(s);
    }

    /**
     * The same turning pictures, drawn like the Save preview: every block with its real texture or model (resource packs apply),
     * lit by facing, at the pixel size they are shown at and smoothed from {@code ss x ss} samples a pixel. Falls back to the map
     * colour picture when the box is too big for the preview or anything fails.
     *
     * @return {@code frames} pictures of {@code w * h} ARGB pixels each (0 = transparent)
     */
    public static int[][] texturedFrames(Blueprint bp, int frames, int w, int h, int ss) {
        try {
            PreviewRaster.Scene scene = PreviewRaster.scene(bp);
            if (scene == null) return frames(bp, frames, w, h);
            int[][] out = new int[frames][];
            for (int i = 0; i < frames; i++) {
                PreviewRaster.View v = new PreviewRaster.View(PreviewRaster.View.HOME.yaw() + 2 * Math.PI * i / frames, PreviewRaster.View.HOME.pitch(), 1.1, 0, 0);
                out[i] = PreviewRaster.resolveArgb(PreviewRaster.render(scene, v, w * ss, h * ss, 1, -1, false, 1.2), ss, w, h);
            }
            return out;
        } catch (RuntimeException e) {
            return frames(bp, frames, w, h);
        }
    }

    /**
     * @param frames how many turns around the build (evenly spaced over a full revolution)
     * @return {@code frames} pictures of {@code w * h} ARGB pixels each (0 = transparent)
     */
    public static int[][] frames(Blueprint bp, int frames, int w, int h) {
        int ex = bp.sizeX, ey = bp.sizeY, ez = bp.sizeZ;
        int[][] out = new int[frames][];
        if (ex <= 0 || ey <= 0 || ez <= 0 || (long) ex * ey * ez > 80_000_000L) {
            for (int i = 0; i < frames; i++) out[i] = new int[w * h];
            return out;
        }
        // a dense colour grid over the enclosing box (0 = air)
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
                        int gx = r.x - bp.minX + x, gy = r.y - bp.minY + y, gz = r.z - bp.minZ + z;
                        grid[(gy * ez + gz) * ex + gx] = c;
                    }
                }
            }
        }
        // cells with an open side: only those can show
        int[] exposed = new int[grid.length];
        int n = 0;
        for (int y = 0; y < ey; y++) {
            for (int z = 0; z < ez; z++) {
                for (int x = 0; x < ex; x++) {
                    int i = (y * ez + z) * ex + x;
                    if (grid[i] == 0) continue;
                    boolean open = x == 0 || x == ex - 1 || y == 0 || y == ey - 1 || z == 0 || z == ez - 1
                        || grid[i - 1] == 0 || grid[i + 1] == 0 || grid[i - ex] == 0 || grid[i + ex] == 0 || grid[i - ex * ez] == 0 || grid[i + ex * ez] == 0;
                    if (open) exposed[n++] = i;
                }
            }
        }
        for (int f = 0; f < frames; f++) out[f] = render(grid, exposed, n, ex, ey, ez, 2 * Math.PI * f / frames, w, h);
        return out;
    }

    private static int[] render(int[] grid, int[] exposed, int n, int ex, int ey, int ez, double theta, int w, int h) {
        double cos = Math.cos(theta), sin = Math.sin(theta);
        // project the box corners to fit the picture
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int cx = 0; cx <= 1; cx++) for (int cy = 0; cy <= 1; cy++) for (int cz = 0; cz <= 1; cz++) {
            double[] p = project(cx * ex - ex / 2.0, cy * ey - ey / 2.0, cz * ez - ez / 2.0, cos, sin);
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        double scale = Math.min((w - 2) / Math.max(1e-6, maxX - minX), (h - 2) / Math.max(1e-6, maxY - minY));
        double ox = w / 2.0 - (minX + maxX) / 2 * scale, oy = h / 2.0 - (minY + maxY) / 2 * scale;
        int[] px = new int[w * h];
        double[] depth = new double[w * h];
        Arrays.fill(depth, -Double.MAX_VALUE);
        for (int k = 0; k < n; k++) {
            int i = exposed[k];
            int x = i % ex, z = (i / ex) % ez, y = i / (ex * ez);
            int color = grid[i];
            double dx = x - ex / 2.0, dy = y - ey / 2.0, dz = z - ez / 2.0;
            // the top, and whichever side faces turn toward the viewer once the build is turned
            face(px, depth, w, h, dx, dy, dz, cos, sin, scale, ox, oy, TOP, shade(color, 1.0));
            for (double[] side : SIDES) {
                double rx = side[0] * cos - side[1] * sin, rz = side[0] * sin + side[1] * cos;
                if (rx + rz > 1e-9) face(px, depth, w, h, dx, dy, dz, cos, sin, scale, ox, oy, side, shade(color, 0.66 + 0.16 * (rx - rz)));
            }
        }
        return px;
    }

    /** Isometric projection of a point (turned about the vertical axis): screen x, screen y (down), nearness. */
    private static double[] project(double x, double y, double z, double cos, double sin) {
        double rx = x * cos - z * sin, rz = x * sin + z * cos;
        return new double[]{(rx - rz) * 0.8660254, (rx + rz) * 0.5 - y, rx + rz + y};
    }

    private static int shade(int argb, double k) {
        int r = (int) (((argb >> 16) & 255) * k), g = (int) (((argb >> 8) & 255) * k), b = (int) ((argb & 255) * k);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static final double[] TOP = new double[0];
    /** The four side faces: the first two numbers are the outward normal in (x, z), then the four corners (x y z). */
    private static final double[][] SIDES = {
        {1, 0, /* +x */ 1, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0},
        {-1, 0, /* -x */ 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},
        {0, 1, /* +z */ 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},
        {0, -1, /* -z */ 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
    };

    /** One face of the unit cube at (dx, dy, dz), given as its four corners. */
    private static void face(int[] px, double[] depth, int w, int h, double dx, double dy, double dz, double cos, double sin, double scale, double ox, double oy, double[] def, int color) {
        double[][] c = new double[4][];
        // the top is stored as bare corners; a side has its two normal numbers first
        int off = def == TOP ? 0 : 2;
        if (def == TOP) {
            c[0] = new double[]{0, 1, 0};
            c[1] = new double[]{1, 1, 0};
            c[2] = new double[]{1, 1, 1};
            c[3] = new double[]{0, 1, 1};
        } else {
            for (int i = 0; i < 4; i++) c[i] = new double[]{def[off + i * 3], def[off + i * 3 + 1], def[off + i * 3 + 2]};
        }
        double[] sx = new double[4], sy = new double[4];
        double d = 0;
        for (int i = 0; i < 4; i++) {
            double[] p = project(dx + c[i][0], dy + c[i][1], dz + c[i][2], cos, sin);
            sx[i] = p[0] * scale + ox;
            sy[i] = p[1] * scale + oy;
            d += p[2];
        }
        d /= 4;
        double minx = Math.min(Math.min(sx[0], sx[1]), Math.min(sx[2], sx[3])), maxx = Math.max(Math.max(sx[0], sx[1]), Math.max(sx[2], sx[3]));
        double miny = Math.min(Math.min(sy[0], sy[1]), Math.min(sy[2], sy[3])), maxy = Math.max(Math.max(sy[0], sy[1]), Math.max(sy[2], sy[3]));
        int x0 = Math.max(0, (int) Math.floor(minx)), x1 = Math.min(w - 1, (int) Math.ceil(maxx));
        int y0 = Math.max(0, (int) Math.floor(miny)), y1 = Math.min(h - 1, (int) Math.ceil(maxy));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double ptx = x + 0.5, pty = y + 0.5;
                // a face is a small convex quad: inside when it is on one side of every edge; tiny faces cover their centre pixel
                if (maxx - minx < 1.2 && maxy - miny < 1.2 || inside(sx, sy, ptx, pty)) {
                    int idx = y * w + x;
                    if (d > depth[idx]) {
                        depth[idx] = d;
                        px[idx] = color;
                    }
                }
            }
        }
    }

    private static boolean inside(double[] sx, double[] sy, double x, double y) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            double cross = (sx[j] - sx[i]) * (y - sy[i]) - (sy[j] - sy[i]) * (x - sx[i]);
            if (cross > 0) pos = true;
            else if (cross < 0) neg = true;
            if (pos && neg) return false;
        }
        return true;
    }
}
