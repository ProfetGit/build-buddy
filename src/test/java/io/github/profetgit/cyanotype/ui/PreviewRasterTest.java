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
        PreviewRaster.Scene s = PreviewRaster.sceneOfColors(solid(3, RED), 3, 3, 3);
        assertNotNull(s);
        assertEquals(54, s.count, "six sides of nine faces");
        assertTrue(!s.thinned);
        // a hole in the middle adds the six faces looking into it
        int[] g = solid(3, RED);
        g[(1 * 3 + 1) * 3 + 1] = 0;
        assertEquals(60, PreviewRaster.sceneOfColors(g, 3, 3, 3).count);
        assertEquals(0, PreviewRaster.sceneOfColors(new int[27], 3, 3, 3).count);
    }

    @Test
    void anEmptyBoxDrawsNothingAndDoesNotFail() {
        PreviewRaster.Scene s = PreviewRaster.sceneOfColors(new int[8], 2, 2, 2);
        int[] px = PreviewRaster.render(s, PreviewRaster.View.HOME, 100, 80, 1, -1).px();
        assertEquals(0, covered(px));
    }

    @Test
    void aCubeFillsTheMiddleOfThePictureAndLeavesTheCornersEmpty() {
        PreviewRaster.Scene s = PreviewRaster.sceneOfColors(solid(6, RED), 6, 6, 6);
        int[] px = PreviewRaster.render(s, PreviewRaster.View.HOME, 200, 160, 1, -1).px();
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
        PreviewRaster.Scene s = PreviewRaster.sceneOfColors(g, 2, 1, 1);
        // yaw pi/2 turns +x to face the camera; pitch tiny so side faces show
        int[] px = PreviewRaster.render(s, new PreviewRaster.View(Math.PI / 2, 0.0, 0.3, 0, 0), 120, 100, 1, -1).px();
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
        PreviewRaster.Scene s = PreviewRaster.sceneOfColors(solid(4, RED), 4, 4, 4);
        int[] near = PreviewRaster.render(s, new PreviewRaster.View(0.785, 0.52, 1.0, 0, 0), 200, 160, 1, -1).px();
        int[] closer = PreviewRaster.render(s, new PreviewRaster.View(0.785, 0.52, 1.6, 0, 0), 200, 160, 1, -1).px();
        assertTrue(covered(closer) > covered(near) * 1.5, covered(near) + " -> " + covered(closer));
        int[] moved = PreviewRaster.render(s, new PreviewRaster.View(0.785, 0.52, 1.0, 12, 0), 200, 160, 1, -1).px();
        assertNotEquals(Arrays.hashCode(near), Arrays.hashCode(moved), "the picture changed");
        assertEquals(covered(near), covered(moved), 40, "the same amount of build, somewhere else");
    }

    @Test
    void turningChangesTheShadesButNotTheSizeMuch() {
        PreviewRaster.Scene s = PreviewRaster.sceneOfColors(solid(5, RED), 5, 5, 5);
        int[] a = PreviewRaster.render(s, new PreviewRaster.View(0.3, 0.5, 1.0, 0, 0), 200, 160, 1, -1).px();
        int[] b = PreviewRaster.render(s, new PreviewRaster.View(1.9, 0.5, 1.0, 0, 0), 200, 160, 1, -1).px();
        assertTrue(Math.abs(covered(a) - covered(b)) < covered(a) / 4, covered(a) + " vs " + covered(b));
        assertNotEquals(Arrays.hashCode(a), Arrays.hashCode(b));
    }

    @Test
    void strideDrawsFewerFacesAndStillShowsTheShape() {
        PreviewRaster.Scene s = PreviewRaster.sceneOfColors(solid(10, RED), 10, 10, 10);
        int[] all = PreviewRaster.render(s, PreviewRaster.View.HOME, 200, 160, 1, -1).px();
        int[] some = PreviewRaster.render(s, PreviewRaster.View.HOME, 200, 160, 3, -1).px();
        assertTrue(covered(some) > 0 && covered(some) < covered(all));
    }

    private static BlockLook.Look sides(int[] left, int[] right) {
        // a 2 x 1 texture: the left texel and the right texel (flat faces everywhere else)
        BlockLook.Tex t = new BlockLook.Tex(2, 1, new int[]{left[0], right[0]}, 0xFF888888);
        return new BlockLook.Look(new BlockLook.Tex[]{t, t, t, t, t, t});
    }

    @Test
    void aTextureIsUprightAndNotMirroredOnTheSideFacingTheCamera() {
        int[] redTexel = {RED}, blueTexel = {BLUE};
        PreviewRaster.Scene s = PreviewRaster.scene(new int[]{1}, new BlockLook.Look[]{sides(redTexel, blueTexel)}, 1, 1, 1);
        // looking at the south face (+z) straight on: yaw 0, a little tilt; the texture's left texel is on the left of the picture
        int[] px = PreviewRaster.render(s, new PreviewRaster.View(0, 0.001, 1.0, 0, 0), 200, 200, 1, -1).px();
        int leftSide = px[100 * 200 + 70], rightSide = px[100 * 200 + 130];
        assertTrue(((leftSide >> 16) & 255) > ((leftSide) & 255), "left is red " + Integer.toHexString(leftSide));
        assertTrue((rightSide & 255) > ((rightSide >> 16) & 255), "right is blue " + Integer.toHexString(rightSide));
    }

    @Test
    void smallBlocksAreFlatColourAndBigOnesShowTheirTexture() {
        int[] redTexel = {RED}, blueTexel = {BLUE};
        BlockLook.Look look = sides(redTexel, blueTexel);
        // 40 blocks across: a block is only a few pixels, so the average colour (grey) shows, not the red and blue texels
        int[] cells = new int[40 * 1 * 1];
        Arrays.fill(cells, 1);
        PreviewRaster.Scene far = PreviewRaster.scene(cells, new BlockLook.Look[]{look}, 40, 1, 1);
        int[] a = PreviewRaster.render(far, new PreviewRaster.View(0, 0.001, 1.0, 0, 0), 120, 120, 1, -1).px();
        int mid = a[60 * 120 + 60];
        assertEquals(((mid >> 16) & 255), (mid & 255), 12, "grey, not red or blue: " + Integer.toHexString(mid));
    }

    @Test
    void theIdBufferNamesTheFaceUnderAPixelAndAHoveredCellIsLitUp() {
        int[] g = solid(3, RED);
        PreviewRaster.Scene s = PreviewRaster.sceneOfColors(g, 3, 3, 3);
        PreviewRaster.Frame f = PreviewRaster.render(s, PreviewRaster.View.HOME, 200, 160, 1, -1);
        int id = f.ids()[80 * 200 + 100];
        assertTrue(id > 0 && id <= s.count, "an id in the middle: " + id);
        int cell = s.cellOfFace(id - 1);
        assertTrue(cell >= 0 && cell < 27);
        assertEquals(0, f.ids()[0], "nothing in the corner");
        PreviewRaster.Frame lit = PreviewRaster.render(s, PreviewRaster.View.HOME, 200, 160, 1, cell);
        int before = f.px()[80 * 200 + 100], after = lit.px()[80 * 200 + 100];
        assertNotEquals(before, after, "the pointed-at block changes colour");
        assertTrue(((after >> 16) & 255) > ((before >> 16) & 255) - 1);
    }

    @Test
    void aBoxTooBigToPreviewIsRefused() {
        assertEquals(null, PreviewRaster.scene(new io.github.profetgit.cyanotype.blueprint.Blueprint(io.github.profetgit.cyanotype.blueprint.Blueprint.Metadata.of("t"), java.util.List.of())));
    }
}
