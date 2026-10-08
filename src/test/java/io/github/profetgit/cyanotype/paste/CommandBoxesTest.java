package io.github.profetgit.cyanotype.paste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CommandBoxesTest {
    private static void check(int[] grid, int w, int h, int d, int cap) {
        int[] copy = grid.clone();
        int[] covered = new int[grid.length];
        for (int[] b : CommandPasteJob.boxes(copy, w, h, d, cap)) {
            int vol = (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
            assertTrue(vol <= cap, "box over the cap: " + vol);
            for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) for (int x = b[0]; x <= b[3]; x++) {
                int i = (y * d + z) * w + x;
                assertEquals(b[6], grid[i], "a box covers only its own state");
                covered[i]++;
            }
        }
        for (int i = 0; i < grid.length; i++) assertEquals(grid[i] >= 0 ? 1 : 0, covered[i], "cell " + i);
    }

    @Test
    void aSolidBlockIsOneBox() {
        int[] g = new int[10 * 4 * 10];
        java.util.Arrays.fill(g, 3);
        assertEquals(1, CommandPasteJob.boxes(g.clone(), 10, 4, 10, 4096).size());
        check(g, 10, 4, 10, 4096);
    }

    @Test
    void theCapSplitsBigBoxes() {
        int[] g = new int[40 * 8 * 40];
        java.util.Arrays.fill(g, 1);
        check(g, 40, 8, 40, 4096);
        assertTrue(CommandPasteJob.boxes(g.clone(), 40, 8, 40, 4096).size() >= 3);
    }

    @Test
    void randomGridsAreCoveredExactlyOnce() {
        Random rnd = new Random(7);
        for (int t = 0; t < 40; t++) {
            int w = 1 + rnd.nextInt(12), h = 1 + rnd.nextInt(6), d = 1 + rnd.nextInt(12);
            int[] g = new int[w * h * d];
            for (int i = 0; i < g.length; i++) g[i] = rnd.nextInt(4) - 1;
            check(g, w, h, d, 1 + rnd.nextInt(60));
        }
    }
}
