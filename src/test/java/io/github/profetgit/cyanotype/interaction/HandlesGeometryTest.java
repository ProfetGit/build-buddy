package io.github.profetgit.cyanotype.interaction;

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
    void aSmallBuildKeepsItsArrowsAtTheMiddleOfEachFaceOrNearIt() {
        Vec3 cam = new Vec3(5, 6, -12);
        Handles h = new Handles(0, 0, 0, 10, 10, 10, cam, new Vec3(0, 0, 1));
        // the camera is straight in front of the middle: the north arrow stands there
        assertEquals(5.0, base(move(h, Direction.NORTH)).x, 1e-6);
        assertEquals(6.0, base(move(h, Direction.NORTH)).y, 1e-6);
        assertTrue(base(move(h, Direction.NORTH)).z < 0);
        // and the top arrow is above the top, as it always was (a short build is not tall)
        assertTrue(base(move(h, Direction.UP)).y > 10);
    }

    @Test
    void onAHugeBuildEveryArrowStandsNearTheCamera() {
        Vec3 cam = new Vec3(-6, 90, 100);
        Handles h = new Handles(0, 0, 0, 200, 200, 200, cam, new Vec3(1, 0, 0));
        // the middle of the east face is 200 blocks away; the arrows of the faces the camera is by are within a short walk
        for (Direction d : new Direction[]{Direction.WEST, Direction.UP, Direction.DOWN}) {
            double dist = base(move(h, d)).distanceTo(cam);
            assertTrue(dist < 40, d + " arrow " + dist + " blocks from the camera");
        }
        // the others stand at the nearest point of their face, which is as near as that face gets
        assertTrue(base(move(h, Direction.NORTH)).distanceTo(cam) < 110);
    }

    @Test
    void theUpArrowOfATallBuildStandsBesideTheWallAtEyeLevel() {
        Vec3 cam = new Vec3(-6, 90, 100);
        Handles h = new Handles(0, 0, 0, 200, 200, 200, cam, new Vec3(1, 0, 0));
        Handles.Handle up = move(h, Direction.UP), down = move(h, Direction.DOWN);
        assertEquals(90.0, up.from.y, 3.0);
        assertEquals(90.0, down.from.y, 3.0);
        // up points up, down points down, and they do not overlap
        assertTrue(up.to.y > up.from.y);
        assertTrue(down.to.y < down.from.y);
        assertTrue(up.from.distanceTo(down.from) > 2.0);
        // outside the west wall, the one the camera is at
        assertTrue(up.from.x < 0 && down.from.x < 0);
    }

    @Test
    void anArrowIsSizedByItsOwnDistanceAndNeverBeyondTheCap() {
        Vec3 far = new Vec3(-600, 50, 50);
        Handles h = new Handles(0, 0, 0, 100, 100, 100, far, new Vec3(1, 0, 0));
        for (Direction d : Direction.values()) assertTrue(move(h, d).scale <= Handles.MAX_ARROW_SCALE + 1e-9 && move(h, d).scale >= 1.0);
        // close to a face of a big build, its arrow is small even though the middle of the build is far
        Handles near = new Handles(0, 0, 0, 400, 100, 400, new Vec3(-3, 50, 200), new Vec3(1, 0, 0));
        assertTrue(move(near, Direction.WEST).scale < 1.5, "scale " + move(near, Direction.WEST).scale);
    }

    @Test
    void theArrowNearestTheCameraCanBePickedWithTheRayThatLooksAtIt() {
        Vec3 cam = new Vec3(-6, 90, 100);
        Vec3 look = new Vec3(1, 0, 0);
        Handles h = new Handles(0, 0, 0, 200, 200, 200, cam, look);
        Handles.Handle up = move(h, Direction.UP);
        Vec3 mid = up.from.add(up.to).scale(0.5);
        Vec3 dir = mid.subtract(cam).normalize();
        Handles.Handle hit = h.pick(cam, dir);
        assertNotNull(hit, "from " + up.from + " to " + up.to + " cam " + cam + " dir " + dir);
        assertEquals(Direction.UP, hit.dir);
    }

    @Test
    void aTallBuildIsOnlyTallWhenItIsTallAndTheTopIsFar() {
        assertFalse(Handles.tall(new Vec3(0, 5, 0), new Vec3(0, 10, 0), 10));
        assertFalse(Handles.tall(new Vec3(0, 5, 0), new Vec3(0, 18, 0), 18), "tall but the top is near");
        assertTrue(Handles.tall(new Vec3(0, 5, 0), new Vec3(0, 60, 0), 60));
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
