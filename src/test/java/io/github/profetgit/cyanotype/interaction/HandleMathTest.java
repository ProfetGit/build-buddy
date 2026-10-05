package io.github.profetgit.cyanotype.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HandleMathTest {
    @Test
    void rayHitsAndMissesABox() {
        // straight down the +z axis at a unit box 5 away
        assertEquals(5.0, HandleMath.rayBox(0.5, 0.5, 0, 0, 0, 1, 0, 0, 5, 1, 1, 6), 1e-9);
        assertTrue(Double.isNaN(HandleMath.rayBox(2.5, 0.5, 0, 0, 0, 1, 0, 0, 5, 1, 1, 6)));
        // pointing away
        assertTrue(Double.isNaN(HandleMath.rayBox(0.5, 0.5, 10, 0, 0, 1, 0, 0, 5, 1, 1, 6)));
        // starting inside counts as an immediate hit
        assertEquals(0.0, HandleMath.rayBox(0.5, 0.5, 5.5, 0, 0, 1, 0, 0, 5, 1, 1, 6), 1e-9);
        // a ray parallel to a slab and outside it
        assertTrue(Double.isNaN(HandleMath.rayBox(0.5, 3, 0, 0, 0, 1, 0, 0, 5, 1, 1, 6)));
        // diagonal
        double t = HandleMath.rayBox(0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3);
        assertEquals(2.0, t, 1e-9);
    }

    @Test
    void closestPointOnAnAxisFollowsTheMouseRay() {
        // line: the x axis through (10, 0, 0). A ray from (0, 0, -10) aimed at (13, 0, 0) passes through that line at t = 3
        double dx = 13, dz = 10;
        double t = HandleMath.closestOnLine(0, 0, -10, dx, 0, dz, 10, 0, 0, 1, 0, 0);
        assertEquals(3.0, t, 1e-9);
        // a ray that passes above the line still resolves to the nearest point
        double above = HandleMath.closestOnLine(0, 5, -10, 13, -5, 10, 10, 0, 0, 1, 0, 0);
        assertEquals(3.0, above, 1e-9);
        // parallel
        assertTrue(Double.isNaN(HandleMath.closestOnLine(0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0)));
    }

    @Test
    void dragSnapsToWholeBlocksAtTheHalf() {
        assertEquals(0, HandleMath.snap(0.49));
        assertEquals(1, HandleMath.snap(0.51));
        assertEquals(-2, HandleMath.snap(-1.6));
        assertEquals(0, HandleMath.quarterTurns(Math.PI / 4 - 0.01));
        assertEquals(1, HandleMath.quarterTurns(Math.PI / 4 + 0.01));
        assertEquals(-1, HandleMath.quarterTurns(-Math.PI / 2));
    }

    @Test
    void anglesWrapTheShortWay() {
        assertEquals(0.2, HandleMath.angleDelta(Math.PI - 0.1, -Math.PI + 0.1), 1e-9);
        assertEquals(-0.2, HandleMath.angleDelta(-Math.PI + 0.1, Math.PI - 0.1), 1e-9);
        // +z is clockwise seen from above: from +x (angle 0) to +z (angle pi/2)
        assertEquals(Math.PI / 2, HandleMath.angle(0, 1, 0, 0), 1e-9);
    }

    @Test
    void groundPlane() {
        assertEquals(10.0, HandleMath.rayPlaneY(10, -1, 0), 1e-9);
        assertTrue(Double.isNaN(HandleMath.rayPlaneY(10, 1, 0)));
        assertTrue(Double.isNaN(HandleMath.rayPlaneY(10, 0, 0)));
    }
}
