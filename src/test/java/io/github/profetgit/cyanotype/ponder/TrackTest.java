package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrackTest {
    private static Track track(Ease ease, double... tv) {
        int n = tv.length / 2;
        double[] t = new double[n];
        double[][] v = new double[n][];
        Ease[] e = new Ease[n];
        for (int i = 0; i < n; i++) {
            t[i] = tv[2 * i];
            v[i] = new double[]{tv[2 * i + 1]};
            e[i] = ease;
        }
        return new Track(t, v, e);
    }

    @Test
    void holdsTheFirstValueBeforeAndTheLastAfter() {
        Track tr = track(Ease.LINEAR, 1, 10, 3, 20);
        assertEquals(10, tr.scalar(0), 1e-9);
        assertEquals(10, tr.scalar(1), 1e-9);
        assertEquals(20, tr.scalar(3), 1e-9);
        assertEquals(20, tr.scalar(99), 1e-9);
    }

    @Test
    void linearGoesStraightBetweenKeys() {
        Track tr = track(Ease.LINEAR, 0, 0, 4, 8);
        assertEquals(2, tr.scalar(1), 1e-9);
        assertEquals(6, tr.scalar(3), 1e-9);
    }

    @Test
    void eachEaseStartsAtZeroAndEndsAtOne() {
        for (Ease e : Ease.values()) {
            assertEquals(0, e.apply(0), 1e-12, e + " at 0");
            assertEquals(1, e.apply(1), 1e-12, e + " at 1");
            assertEquals(0, e.apply(-1), 1e-12);
            assertEquals(1, e.apply(2), 1e-12);
        }
    }

    @Test
    void easesAreMonotoneExceptTheSpringWhichOvershootsOnce() {
        for (Ease e : new Ease[]{Ease.LINEAR, Ease.IN, Ease.OUT, Ease.IN_OUT}) {
            double prev = 0;
            for (int i = 1; i <= 100; i++) {
                double v = e.apply(i / 100.0);
                assertTrue(v >= prev - 1e-12, e + " went back at " + i);
                prev = v;
            }
        }
        double max = 0;
        for (int i = 1; i < 100; i++) max = Math.max(max, Ease.SPRING.apply(i / 100.0));
        assertTrue(max > 1.02 && max < 1.4, "the spring overshoots a little, not a lot: " + max);
    }

    @Test
    void aStepHoldsTheOldValueUntilTheKey() {
        Track tr = track(Ease.STEP, 0, 0, 2, 1);
        assertEquals(0, tr.scalar(1.999), 1e-9);
        assertEquals(1, tr.scalar(2), 1e-9);
        assertEquals(1, tr.scalar(2.5), 1e-9);
    }

    @Test
    void theEaseBelongsToTheSegmentThatEndsAtTheKey() {
        Track tr = new Track(new double[]{0, 2, 4}, new double[][]{{0}, {10}, {20}}, new Ease[]{Ease.LINEAR, Ease.STEP, Ease.LINEAR});
        // 0..2 is a step (the key at 2 says step), 2..4 is linear
        assertEquals(0, tr.scalar(1), 1e-9);
        assertEquals(10, tr.scalar(2), 1e-9);
        assertEquals(15, tr.scalar(3), 1e-9);
    }

    @Test
    void vectorsGoComponentByComponent() {
        Track tr = new Track(new double[]{0, 2}, new double[][]{{0, 10, 100}, {2, 20, 0}}, new Ease[]{Ease.LINEAR, Ease.LINEAR});
        double[] v = tr.at(1);
        assertEquals(1, v[0], 1e-9);
        assertEquals(15, v[1], 1e-9);
        assertEquals(50, v[2], 1e-9);
    }

    @Test
    void keysAtTheSameTimeTheLastWins() {
        Track tr = new Track(new double[]{0, 1, 1}, new double[][]{{0}, {5}, {9}}, new Ease[]{Ease.LINEAR, Ease.LINEAR, Ease.LINEAR});
        assertEquals(9, tr.scalar(1), 1e-9);
    }

    @Test
    void badTracksAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Track(new double[]{1, 0}, new double[][]{{0}, {1}}, new Ease[]{Ease.LINEAR, Ease.LINEAR}));
        assertThrows(IllegalArgumentException.class, () -> new Track(new double[]{0, 1}, new double[][]{{0}, {1, 2}}, new Ease[]{Ease.LINEAR, Ease.LINEAR}));
        assertThrows(IllegalArgumentException.class, () -> new Track(new double[0], new double[0][], new Ease[0]));
    }
}
