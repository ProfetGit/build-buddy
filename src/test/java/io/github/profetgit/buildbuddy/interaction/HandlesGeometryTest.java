package io.github.profetgit.buildbuddy.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HandlesGeometryTest {
    private static Handles.Handle move(Handles h, Direction d) {
        for (Handles.Handle x : h.handles) if (x.kind == Handles.Kind.MOVE && x.dir == d) return x;
        throw new AssertionError("no arrow " + d);
    }

    private static Vec3 base(Handles.Handle h) {
        return h.from;
    }

    @Test
    void theArrowsStandOnFixedPlacesAndNeverFollowTheCamera() {
        Handles a = new Handles(0, 0, 0, 12, 8, 14, new Vec3(-20, 3, 7), new Vec3(1, 0, 0));
        Handles b = new Handles(0, 0, 0, 12, 8, 14, new Vec3(30, 20, -9), new Vec3(-1, -0.4, 0.2).normalize());
        for (Direction d : Direction.values()) {
            // the arrow is bigger or smaller with the distance, but it stands on the same line of the build
            double[] pa = {base(move(a, d)).x, base(move(a, d)).y, base(move(a, d)).z}, pb = {base(move(b, d)).x, base(move(b, d)).y, base(move(b, d)).z};
            for (int i = 0; i < 3; i++) {
                if (i != d.getAxis().ordinal()) assertEquals(pa[i], pb[i], 1e-9, d + " axis " + i);
            }
        }
    }

    @Test
    void everyPairMirrorsItselfAcrossTheBuild() {
        Handles h = new Handles(2, 1, 3, 12, 8, 14, new Vec3(-20, 3, 7), new Vec3(1, 0, 0));
        // up and down share their x and z (the middle of the footprint), east and west their y and z, north and south their x and y
        assertEquals(base(move(h, Direction.UP)).x, base(move(h, Direction.DOWN)).x, 1e-9);
        assertEquals(base(move(h, Direction.UP)).z, base(move(h, Direction.DOWN)).z, 1e-9);
        assertEquals(8.0, base(move(h, Direction.UP)).x, 1e-9);
        assertEquals(10.0, base(move(h, Direction.UP)).z, 1e-9);
        assertEquals(base(move(h, Direction.EAST)).y, base(move(h, Direction.WEST)).y, 1e-9);
        assertEquals(base(move(h, Direction.EAST)).z, base(move(h, Direction.WEST)).z, 1e-9);
        assertEquals(base(move(h, Direction.NORTH)).x, base(move(h, Direction.SOUTH)).x, 1e-9);
        assertEquals(base(move(h, Direction.NORTH)).y, base(move(h, Direction.SOUTH)).y, 1e-9);
        assertTrue(base(move(h, Direction.UP)).y > 9 && base(move(h, Direction.DOWN)).y < 1);
    }

    @Test
    void onATallBuildTheSidewaysArrowsStayWithinStandingReach() {
        Handles h = new Handles(0, 0, 0, 200, 200, 200, new Vec3(-6, 90, 100), new Vec3(1, 0, 0));
        for (Direction d : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            assertEquals(Handles.REACH_HEIGHT, base(move(h, d)).y, 1.0, d + " height");
        }
        Handles shed = new Handles(0, 0, 0, 8, 6, 8, new Vec3(-6, 2, 4), new Vec3(1, 0, 0));
        assertEquals(3.0, base(move(shed, Direction.EAST)).y, 1.0, "a short build: the middle");
    }

    @Test
    void anArrowIsSizedByItsOwnDistanceAndNeverBeyondTheCap() {
        Vec3 far = new Vec3(-600, 50, 50);
        Handles h = new Handles(0, 0, 0, 100, 100, 100, far, new Vec3(1, 0, 0));
        for (Direction d : Direction.values()) assertTrue(move(h, d).scale <= Handles.MAX_ARROW_SCALE + 1e-9 && move(h, d).scale >= 1.0);
        // an arrow near the camera is smaller than one far away on the same build
        Handles near = new Handles(0, 0, 0, 400, 100, 400, new Vec3(-3, 3, 200), new Vec3(1, 0, 0));
        assertTrue(move(near, Direction.WEST).scale < move(near, Direction.EAST).scale, "west " + move(near, Direction.WEST).scale + ", east " + move(near, Direction.EAST).scale);
    }

    @Test
    void anArrowCanBePickedWithTheRayThatLooksAtIt() {
        Vec3 cam = new Vec3(-6, 90, 100);
        Vec3 look = new Vec3(1, 0, 0);
        Handles h = new Handles(0, 0, 0, 200, 200, 200, cam, look);
        for (Direction d : Direction.values()) {
            Handles.Handle m = move(h, d);
            Vec3 mid = m.from.add(m.to).scale(0.5);
            Handles.Handle hit = h.pick(cam, mid.subtract(cam).normalize());
            assertNotNull(hit, d + " from " + m.from + " to " + m.to);
            assertEquals(d, hit.dir, "ray at the " + d + " arrow");
        }
    }

    @Test
    void anArrowSeenEndOnIsKnownAsSuch() {
        assertTrue(Interaction.endOn(new Vec3(0, 0, 1), new Vec3(0.05, 0.05, 0.99).normalize()));
        assertFalse(Interaction.endOn(new Vec3(0, 0, 1), new Vec3(0.6, 0, 0.8)));
    }

    @Test
    void arrowsShrinkWithASmallBuildButNeverVanishOrGrowPastTheCap() {
        // a 4 block shed seen from 9 blocks: much smaller than the old minimum of 1 unit (2.8 blocks long)
        double shed = Handles.arrowScale(9, 4, Handles.MAX_ARROW_SCALE);
        assertTrue(shed < 0.5 && shed >= 0.35, "scale " + shed);
        // a 16 block house keeps the old size at the old distances
        assertEquals(1.0, Handles.arrowScale(9, 16, Handles.MAX_ARROW_SCALE), 1e-9);
        assertTrue(Handles.arrowScale(9, 10, 8) > shed && Handles.arrowScale(9, 10, 8) < 1.0, "a middling build in between");
        assertEquals(Handles.MAX_ARROW_SCALE, Handles.arrowScale(5000, 300, Handles.MAX_ARROW_SCALE), 1e-9);
        // never smaller on screen than the grab box floor lets you hit
        Handles h = new Handles(0, 0, 0, 4, 4, 4, new Vec3(-30, 2, 2), new Vec3(1, 0, 0));
        Handles.Handle east = move(h, Direction.WEST);
        double half = (east.boxes[0][3] - east.boxes[0][0]) / 2;
        assertTrue(half >= Handles.PICK_PER_BLOCK * 20, "half width " + half);
    }

    @Test
    void theRingLiesUnderTheBuildRoundItsMiddleAndIsCutDownOnAHugeOne() {
        Handles small = new Handles(0, 0, 0, 9, 6, 9, new Vec3(-6, 2, 4), new Vec3(1, 0, 0));
        assertEquals(4.5, small.ring.cx(), 1e-9);
        assertEquals(4.5, small.ring.cz(), 1e-9);
        assertEquals(0.05, small.ring.y(), 1e-9, "at the base");
        assertTrue(small.ring.radius() > Math.hypot(9, 9) / 2, "round the footprint");
        Handles mega = new Handles(0, 0, 0, 240, 120, 240, new Vec3(-8, 3, 100), new Vec3(1, 0, 0));
        assertEquals(120, mega.ring.cx(), 1e-9, "still round the middle");
        assertEquals(Handles.RING_MAX, mega.ring.radius(), 1e-9, "cut down");
        assertTrue(mega.ring.tolerance() > 0.3);
    }

    @Test
    void thereAreNoFlipArrowsAndEveryArrowStaysWhateverWayTheViewPoints() {
        for (Vec3 look : new Vec3[]{new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, -1, 0), new Vec3(0.7, 0.3, 0.64).normalize()}) {
            Handles h = new Handles(0, 0, 0, 12, 8, 12, new Vec3(-10, 4, 6), look);
            int moves = 0, others = 0;
            for (Handles.Handle x : h.handles) {
                if (x.kind == Handles.Kind.MOVE) moves++;
                else if (x.kind != Handles.Kind.RING) others++;
            }
            assertEquals(6, moves);
            assertEquals(0, others);
        }
    }
}
