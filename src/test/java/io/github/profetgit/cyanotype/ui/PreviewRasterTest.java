package io.github.profetgit.cyanotype.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PreviewRasterTest {
    private static final int RED = 0xFFC83C3C, BLUE = 0xFF3C3CC8;

    private static int[] solid(int n, int color) {
        int[] g = new int[n * n * n];
        Arrays.fill(g, color);
        return g;
    }

    private static int covered(int[] px) {
        int n = 0;
        for (int p : px) if (p != 0) n++;
        return n;
    }

    @Test
    void onlyTheFacesWithAirInFrontAreKept() {
        PreviewRaster.Scene s = PreviewRaster.scene(solid(3, RED), 3, 3, 3);
        assertNotNull(s);
        assertEquals(54, s.count, "six sides of nine faces");
        assertTrue(!s.thinned);
        // a hole in the middle adds the six faces looking into it
        int[] g = solid(3, RED);
        g[(1 * 3 + 1) * 3 + 1] = 0;
        assertEquals(60, PreviewRaster.scene(g, 3, 3, 3).count);
        assertEquals(0, PreviewRaster.scene(new int[27], 3, 3, 3).count);
    }

    @Test
    void anEmptyBoxDrawsNothingAndDoesNotFail() {
        PreviewRaster.Scene s = PreviewRaster.scene(new int[8], 2, 2, 2);
        int[] px = PreviewRaster.render(s, PreviewRaster.View.HOME, 100, 80, 1);
        assertEquals(0, covered(px));
    }

    @Test
    void aCubeFillsTheMiddleOfThePictureAndLeavesTheCornersEmpty() {
        PreviewRaster.Scene s = PreviewRaster.scene(solid(6, RED), 6, 6, 6);
        int[] px = PreviewRaster.render(s, PreviewRaster.View.HOME, 200, 160, 1);
        assertTrue(covered(px) > 4000, "covered " + covered(px));
        assertEquals(0, px[0], "top left corner");
        assertEquals(0, px[200 * 160 - 1], "bottom right corner");
        assertNotEquals(0, px[80 * 200 + 100], "the middle");
    }

    @Test
    void theNearerBlockHidesTheFartherOne() {
        // two blocks in a row along x; looking along x from +x (yaw 0, no tilt) only the nearer, red one shows
        int[] g = new int[2 * 1 * 1];
        g[0] = BLUE;
        g[1] = RED;
        PreviewRaster.Scene s = PreviewRaster.scene(g, 2, 1, 1);
        // yaw pi/2 turns +x to face the camera; pitch tiny so side faces show
        int[] px = PreviewRaster.render(s, new PreviewRaster.View(Math.PI / 2, 0.0, 0.3, 0, 0), 120, 100, 1);
        int reds = 0, blues = 0;
        for (int p : px) {
            if (p == 0) continue;
            int r = (p >> 16) & 255, b = p & 255;
            if (r > b) reds++;
            else blues++;
        }
        assertTrue(reds > 0 && blues == 0, "reds " + reds + ", blues " + blues);
    }

    @Test
    void zoomingInMakesTheBuildBiggerAndPanningMovesIt() {
        PreviewRaster.Scene s = PreviewRaster.scene(solid(4, RED), 4, 4, 4);
        int[] near = PreviewRaster.render(s, new PreviewRaster.View(0.785, 0.52, 1.0, 0, 0), 200, 160, 1);
        int[] closer = PreviewRaster.render(s, new PreviewRaster.View(0.785, 0.52, 1.6, 0, 0), 200, 160, 1);
        assertTrue(covered(closer) > covered(near) * 1.5, covered(near) + " -> " + covered(closer));
        int[] moved = PreviewRaster.render(s, new PreviewRaster.View(0.785, 0.52, 1.0, 12, 0), 200, 160, 1);
        assertNotEquals(Arrays.hashCode(near), Arrays.hashCode(moved), "the picture changed");
        assertEquals(covered(near), covered(moved), 40, "the same amount of build, somewhere else");
    }

    @Test
    void turningChangesTheShadesButNotTheSizeMuch() {
        PreviewRaster.Scene s = PreviewRaster.scene(solid(5, RED), 5, 5, 5);
        int[] a = PreviewRaster.render(s, new PreviewRaster.View(0.3, 0.5, 1.0, 0, 0), 200, 160, 1);
        int[] b = PreviewRaster.render(s, new PreviewRaster.View(1.9, 0.5, 1.0, 0, 0), 200, 160, 1);
        assertTrue(Math.abs(covered(a) - covered(b)) < covered(a) / 4, covered(a) + " vs " + covered(b));
        assertNotEquals(Arrays.hashCode(a), Arrays.hashCode(b));
    }

    @Test
    void strideDrawsFewerFacesAndStillShowsTheShape() {
        PreviewRaster.Scene s = PreviewRaster.scene(solid(10, RED), 10, 10, 10);
        int[] all = PreviewRaster.render(s, PreviewRaster.View.HOME, 200, 160, 1);
        int[] some = PreviewRaster.render(s, PreviewRaster.View.HOME, 200, 160, 3);
        assertTrue(covered(some) > 0 && covered(some) < covered(all));
    }

    @Test
    void aBoxTooBigToPreviewIsRefused() {
        assertEquals(null, PreviewRaster.scene(new io.github.profetgit.cyanotype.blueprint.Blueprint(io.github.profetgit.cyanotype.blueprint.Blueprint.Metadata.of("t"), java.util.List.of())));
    }
}
