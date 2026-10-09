package io.github.profetgit.buildbuddy.ghost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeelTest {
    @Test
    void easingClosesTheGapAtTheSameRateAtAnyFrameRate() {
        // one 20 ms step twice, or one 40 ms step: the same position
        double a = Feel.ease(Feel.ease(0, 10, 0.02, Feel.FOLLOW), 10, 0.02, Feel.FOLLOW);
        double b = Feel.ease(0, 10, 0.04, Feel.FOLLOW);
        assertEquals(b, a, 1e-9);
        // it arrives, and stops there
        double x = 0;
        for (int i = 0; i < 200; i++) x = Feel.ease(x, 3, 0.016, Feel.FOLLOW);
        assertEquals(3.0, x, 0.0);
    }

    @Test
    void springDropsWithOneOvershootAndSettles() {
        assertEquals(1.0, Feel.spring(0), 1e-9);
        assertEquals(0.0, Feel.spring(Feel.SETTLE_SECONDS), 0.0);
        double min = 1, max = -1;
        boolean crossedZero = false, backUp = false;
        double prev = 1;
        for (double t = 0; t < Feel.SETTLE_SECONDS; t += 0.002) {
            double v = Feel.spring(t);
            min = Math.min(min, v);
            if (prev > 0 && v <= 0) crossedZero = true;
            if (crossedZero && v > prev) backUp = true;
            prev = v;
        }
        assertTrue(crossedZero, "it should pass through its resting place once");
        assertTrue(min < -0.05 && min > -0.4, "overshoot " + min);
        assertTrue(backUp, "and come back");
        // the ending is close enough to rest that the cut-off is not visible
        assertTrue(Math.abs(Feel.spring(Feel.SETTLE_SECONDS - 0.002)) < 0.03);
    }
}
