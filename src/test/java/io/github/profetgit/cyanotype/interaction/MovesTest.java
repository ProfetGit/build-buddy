package io.github.profetgit.cyanotype.interaction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MovesTest {
    @Test
    void turningKeepsTheCentre() {
        // 9 x 5 footprint at (10, 20): centre (14.5, 22.5); turned it is 5 x 9, so it starts at (12, 18)
        assertArrayEquals(new int[]{12, 18}, Moves.keepCenter(10, 20, 9, 5, 5, 9));
        // and turning back returns to the start
        assertArrayEquals(new int[]{10, 20}, Moves.keepCenter(12, 18, 5, 9, 9, 5));
        // an even box turned into an odd one cannot keep it exactly; it stays within half a block
        int[] o = Moves.keepCenter(0, 0, 4, 3, 3, 4);
        assertEquals(1, o[0]);
        assertEquals(0, o[1]);
    }

    @Test
    void turnsAreWorkedOutFromTheStartSoNothingDrifts() {
        // a drag that goes round and round: each step is a function of the start, so a full turn is exactly home
        int x = 7, z = -3, sx = 11, sz = 4;
        for (int steps = 0; steps <= 12; steps++) {
            boolean swapped = steps % 2 == 1;
            int[] n = Moves.keepCenter(x, z, sx, sz, swapped ? sz : sx, swapped ? sx : sz);
            if (steps % 4 == 0) {
                assertEquals(7, n[0], "after " + steps + " quarter turns");
                assertEquals(-3, n[1], "after " + steps + " quarter turns");
            }
        }
    }

    @Test
    void aimingCentresTheFootprint() {
        assertArrayEquals(new int[]{8, 18}, Moves.centerOn(10, 20, 5, 5));
        assertArrayEquals(new int[]{8, 19}, Moves.centerOn(10, 20, 4, 3));
        assertArrayEquals(new int[]{-3, -5}, Moves.centerOn(-1, -4, 4, 2));
    }
}
