package io.github.profetgit.cyanotype.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WheelGeometryTest {
    private static final double INNER = 28, OUTER = 124;

    private static int at(double angle, double radius, int n) {
        return WheelGeometry.segmentAt(Math.sin(angle) * radius, -Math.cos(angle) * radius, INNER, OUTER, n);
    }

    @Test
    void everySegmentIsTheSameSizeForAnyNumberOfTools() {
        for (int n = 1; n <= 24; n++) {
            double step = WheelGeometry.step(n);
            assertEquals(2 * Math.PI, step * n, 1e-12, "n " + n);
            for (int i = 0; i < n; i++) {
                assertEquals(step, WheelGeometry.boundary(i, n) - WheelGeometry.boundary(i - 1, n), 1e-12);
                // the line between two segments is exactly halfway between their middles
                assertEquals(WheelGeometry.boundary(i, n), (WheelGeometry.center(i, n) + WheelGeometry.center(i + 1, n)) / 2, 1e-12);
            }
        }
    }

    @Test
    void anIconPlacedAtTheMiddleOfASegmentIsInThatSegmentAndNeverOnALine() {
        for (int n = 2; n <= 24; n++) {
            for (int i = 0; i < n; i++) {
                double[] p = WheelGeometry.pointAt(i, n, 45);
                assertEquals(i, WheelGeometry.segmentAt(p[0], p[1], INNER, OUTER, n), "n " + n + " segment " + i);
                // half a step away from the nearest line, by construction
                double c = WheelGeometry.center(i, n);
                assertEquals(WheelGeometry.step(n) / 2, Math.abs(WheelGeometry.boundary(i, n) - c), 1e-12);
            }
        }
    }

    @Test
    void justEitherSideOfALineBelongsToTheNeighbours() {
        for (int n = 3; n <= 16; n++) {
            for (int i = 0; i < n; i++) {
                double b = WheelGeometry.boundary(i, n), eps = 1e-4;
                assertEquals(i, at(b - eps, 60, n), "n " + n + " before line " + i);
                assertEquals((i + 1) % n, at(b + eps, 60, n), "n " + n + " after line " + i);
            }
        }
    }

    @Test
    void theFirstSegmentIsCentredStraightUpAndTheRestRunClockwise() {
        assertEquals(0, at(0, 60, 8));
        assertEquals(0, at(-0.1, 60, 8));
        assertEquals(1, at(Math.PI / 4, 60, 8));
        assertEquals(2, at(Math.PI / 2, 60, 8), "straight right is the third of eight");
        assertEquals(4, at(Math.PI, 60, 8));
        assertEquals(6, at(-Math.PI / 2, 60, 8));
        // five tools: the second is 72 degrees round
        assertEquals(1, at(Math.toRadians(72), 60, 5));
        assertEquals(4, at(Math.toRadians(-72), 60, 5));
    }

    @Test
    void theHubAndTheFarOutsideChooseNothing() {
        assertEquals(-1, WheelGeometry.segmentAt(5, -5, INNER, OUTER, 8));
        assertEquals(-1, WheelGeometry.segmentAt(0, -200, INNER, OUTER, 8));
        assertTrue(WheelGeometry.segmentAt(0, -50, INNER, OUTER, 8) >= 0);
    }
}
