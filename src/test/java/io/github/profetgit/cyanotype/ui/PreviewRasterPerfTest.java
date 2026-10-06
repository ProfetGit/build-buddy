package io.github.profetgit.cyanotype.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PreviewRasterPerfTest {
    private static BlockLook.Look textured(long seed) {
        Random r = new Random(seed);
        int[] px = new int[256];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | (0x60 + r.nextInt(0x90)) << 16 | (0x40 + r.nextInt(0x90)) << 8 | 0x30 + r.nextInt(0x80);
        BlockLook.Tex t = new BlockLook.Tex(16, 16, px, 0xFF907050);
        return new BlockLook.Look(new BlockLook.Tex[]{t, t, t, t, t, t});
    }

    /** A hollow house with a roof and some posts: a few thousand blocks, like a real build. */
    static PreviewRaster.Scene house(int n, int h) {
        int[] cells = new int[n * h * n];
        for (int y = 0; y < h; y++)
            for (int z = 0; z < n; z++)
                for (int x = 0; x < n; x++) {
                    boolean wall = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                    boolean floor = y == 0 || y == h - 1;
                    if ((wall && y < h - 1) || floor || (x % 5 == 2 && z % 5 == 2)) cells[(y * n + z) * n + x] = 1 + ((x + y + z) % 3);
                }
        return PreviewRaster.scene(cells, new BlockLook.Look[]{textured(1), textured(2), textured(3)}, n, h, n);
    }

    @Test
    void timings() {
        for (int[] size : new int[][]{{26, 16}, {80, 40}}) {
            PreviewRaster.Scene s = house(size[0], size[1]);
            assertNotNull(s);
            for (int[] dim : new int[][]{{580, 360}, {1200, 720}, {2400, 1440}}) {
                for (double zoom : new double[]{1, 4}) {
                    PreviewRaster.View v = new PreviewRaster.View(0.6, 0.5, zoom, 0, 0);
                    for (int i = 0; i < 3; i++) PreviewRaster.render(s, v, dim[0], dim[1], 1, -1);
                    long t0 = System.nanoTime();
                    int reps = 8;
                    for (int i = 0; i < reps; i++) PreviewRaster.render(s, v, dim[0], dim[1], 1, -1);
                    System.out.printf("PERF house %d: faces %d  %dx%d zoom %.0f: %.1f ms%n", size[0], s.count, dim[0], dim[1], zoom, (System.nanoTime() - t0) / 1e6 / reps);
                }
            }
        }
    }
}
