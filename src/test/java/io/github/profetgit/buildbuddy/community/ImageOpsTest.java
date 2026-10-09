package io.github.profetgit.buildbuddy.community;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ImageOpsTest {
    @Test
    void aPictureNotLargerThanTheTargetIsKept() {
        int[] px = {0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC};
        assertArrayEquals(px, ImageOps.boxDownscale(px, 2, 2, 4, 4));
    }

    @Test
    void shrinkingAveragesEveryChannel() {
        // a 4 x 2 picture, left half black, right half white, shrunk to 2 x 1: still one black and one white pixel
        int b = 0xFF000000, w = 0xFFFFFFFF;
        assertArrayEquals(new int[]{b, w}, ImageOps.boxDownscale(new int[]{b, b, w, w, b, b, w, w}, 4, 2, 2, 1));
        // 2 x 2 down to 1 x 1: the mean of black and white is mid grey (rounded)
        int[] grey = ImageOps.boxDownscale(new int[]{b, w, w, b}, 2, 2, 1, 1);
        assertEquals(0xFF, grey[0] >>> 24);
        assertEquals(128, grey[0] & 255);
        assertEquals(128, (grey[0] >> 8) & 255);
        assertEquals(128, (grey[0] >> 16) & 255);
    }

    @Test
    void anOddRatioStillCoversEveryPixelOnce() {
        // 800 x 600 down to 192 x 144 (4.17 : 1): a solid picture stays exactly that colour, no edge is left dark
        int[] solid = new int[800 * 600];
        java.util.Arrays.fill(solid, 0xFF336699);
        int[] out = ImageOps.boxDownscale(solid, 800, 600, 192, 144);
        assertEquals(192 * 144, out.length);
        for (int p : out) assertEquals(0xFF336699, p);
    }

    @Test
    void sizesThatCannotBeRefused() {
        assertThrows(IllegalArgumentException.class, () -> ImageOps.boxDownscale(new int[3], 2, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> ImageOps.boxDownscale(new int[4], 2, 2, 0, 1));
    }
}
