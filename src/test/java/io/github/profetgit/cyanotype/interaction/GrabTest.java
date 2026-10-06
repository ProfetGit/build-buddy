package io.github.profetgit.cyanotype.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GrabTest {
    private static Vec3 toward(Vec3 from, Vec3 to) {
        return to.subtract(from).normalize();
    }

    @Test
    void theGrabbedPointStaysUnderTheCrosshairAtAnyDistance() {
        for (double dist : new double[]{8, 60, 400}) {
            Vec3 eye = new Vec3(0, 70, 0);
            Vec3 p0 = new Vec3(dist, 10, 0);
            Grab g = Grab.start(eye, toward(eye, p0), p0, 10);
            // look at a point 5 blocks east and 7 south of it on the same plane
            g.update(eye, toward(eye, p0.add(5, 0, 7)), false);
            assertEquals(5, g.dx, "dx at " + dist);
            assertEquals(7, g.dz, "dz at " + dist);
            assertEquals(0, g.dy);
        }
    }

    @Test
    void shiftMovesOnlyUpAndDownAndKeepsTheSideways() {
        Vec3 eye = new Vec3(0, 20, 0);
        Vec3 p0 = new Vec3(30, 15, 0);
        Grab g = Grab.start(eye, toward(eye, p0), p0, 0);
        g.update(eye, toward(eye, p0.add(4, 0, 2)), false);
        assertEquals(4, g.dx);
        g.update(eye, toward(eye, p0.add(4, 0, 2)), true);
        assertEquals(0, g.dy, "pressing Shift anchors where the view is");
        g.update(eye, toward(eye, p0.add(0, 6, 0)), true);
        assertTrue(Math.abs(g.dy - 6) <= 1, "lifted about six: " + g.dy);
        int lifted = g.dy;
        assertEquals(4, g.dx, "sideways kept");
        assertEquals(2, g.dz);
        g.update(eye, toward(eye, p0.add(4, 6, 2)), false);
        assertEquals(lifted, g.dy, "back to sideways does not undo the lift");
    }

    @Test
    void changingBetweenSidewaysAndLiftDoesNotJump() {
        Vec3 eye = new Vec3(0, 20, 0);
        Vec3 p0 = new Vec3(30, 15, 0);
        Grab g = Grab.start(eye, toward(eye, p0), p0, 0);
        g.update(eye, toward(eye, p0.add(4, 0, 2)), false);
        g.update(eye, toward(eye, p0.add(4, 0, 2)), true);
        g.update(eye, toward(eye, p0.add(0, 6, 0)), true);
        int lifted = g.dy;
        assertTrue(Math.abs(lifted - 6) <= 1, "lifted about six: " + lifted);
        // Shift let go while the view is up there: the sideways part stays what it was
        g.update(eye, toward(eye, p0.add(0, 6, 0)), false);
        assertEquals(4, g.dx);
        assertEquals(2, g.dz);
        g.update(eye, toward(eye, p0.add(0, 6, 0)), true);
        assertEquals(lifted, g.dy, "and back to lifting carries on from where it was");
        g.update(eye, toward(eye, p0.add(0, 9, 0)), true);
        assertTrue(g.dy >= lifted + 2 && g.dy <= lifted + 4, "three more: " + g.dy);
    }

    @Test
    void aGrabbedPointLevelWithTheEyeUsesTheFloorInstead() {
        // standing inside a huge build, looking at a wall at eye height: the plane at that height could never be met
        Vec3 eye = new Vec3(0, 1.6, 0);
        Vec3 p0 = new Vec3(20, 1.7, 0);
        Vec3 look = toward(eye, new Vec3(20, -0.4, 0)).normalize();
        Grab g = Grab.start(eye, look, p0, 0);
        assertEquals(0, g.planeY, 1e-9);
        g.update(eye, toward(eye, new Vec3(20, -0.4, 6)), false);
        assertTrue(g.dz > 0 && g.dx == 0 || g.dx != 0 || g.dz != 0, "it follows the floor");
        assertTrue(g.moved());
    }

    @Test
    void lookingLevelAtAWallStartsOnTheFirstFrameTheViewMeetsTheFloor() {
        Vec3 eye = new Vec3(0, 61.6, 0);
        Vec3 p0 = new Vec3(150, 61.6, 0);
        Grab g = Grab.start(eye, new Vec3(1, 0, 0), p0, 0);
        g.update(eye, new Vec3(1, 0, 0), false);
        assertFalse(g.moved(), "level view: nothing yet");
        g.update(eye, toward(eye, new Vec3(100, 0, 0)), false);
        assertFalse(g.moved(), "the first floor hit is the start, no jump");
        g.update(eye, toward(eye, new Vec3(100, 0, 12)), false);
        assertEquals(12, g.dz);
    }

    @Test
    void lookingTowardTheHorizonCannotThrowTheBuildFarAway() {
        // grabbed low, 12 blocks off, from standing height; then the view lifts toward the horizon and the sky at once
        Vec3 eye = new Vec3(0, 1.6, 0);
        Vec3 p0 = new Vec3(12, 0, 0);
        Grab g = Grab.start(eye, toward(eye, p0), p0, 0);
        double worst = 0;
        for (double pitch = -0.13; pitch < -0.0001; pitch *= 0.8) {
            g.update(eye, new Vec3(Math.cos(pitch), Math.sin(pitch), 0), false);
            worst = Math.max(worst, Math.abs(g.dx));
        }
        assertTrue(worst <= 12 * 1.5 + 30 - 12 + 1, "never farther than the reach limit: " + worst);
        g.update(eye, new Vec3(0.9, 0.3, 0).normalize(), false);
        assertTrue(Math.abs(g.dx) <= 48, "the sky: stays put or comes back, never flies: " + g.dx);
        // moving 10 blocks is still 10: the squeeze starts only past the grab distance and is gentle near it
        Grab h = Grab.start(eye, toward(eye, p0), p0, 0);
        h.update(eye, toward(eye, new Vec3(22, 0, 0)), false);
        assertTrue(h.dx >= 9 && h.dx <= 10, "ten away is about ten: " + h.dx);
    }

    @Test
    void liftingIsBoundedToo() {
        Vec3 eye = new Vec3(0, 20, 0);
        Vec3 p0 = new Vec3(30, 15, 0);
        Grab g = Grab.start(eye, toward(eye, p0), p0, 0);
        g.update(eye, toward(eye, p0), true);
        g.update(eye, new Vec3(1, 4, 0).normalize(), true);
        assertTrue(Math.abs(g.dy) < 80, "bounded: " + g.dy);
    }

    @Test
    void aViewThatNeverMeetsThePlaneLeavesTheBuildWhereItWas() {
        Vec3 eye = new Vec3(0, 70, 0);
        Vec3 p0 = new Vec3(40, 10, 0);
        Grab g = Grab.start(eye, toward(eye, p0), p0, 10);
        g.update(eye, toward(eye, p0.add(3, 0, 0)), false);
        assertEquals(3, g.dx);
        g.update(eye, new Vec3(1, 0.3, 0).normalize(), false);
        assertEquals(3, g.dx, "looking up, away from the plane: unchanged");
        g.update(eye, new Vec3(1, -0.00001, 0).normalize(), false);
        assertEquals(3, g.dx, "a grazing view far beyond the cap: unchanged");
    }

    @Test
    void aRoofGrabbedFromTheGroundStillFollowsWhenTheViewLowersToTheGround() {
        // standing at the foot of a tall build, picking it up by its roof, then looking down to where it should go
        Vec3 eye = new Vec3(0, 1.6, 0);
        Vec3 roof = new Vec3(10, 9, 0);
        Grab g = Grab.start(eye, toward(eye, roof), roof, 0);
        assertEquals(9, g.planeY, 1e-9);
        g.update(eye, toward(eye, roof.add(0, 0, 3)), false);
        assertEquals(3, g.dz, "looking up at the roof level: the grabbed point follows");
        // the view goes down to the ground: the roof's plane can no longer be met, the floor takes over without a jump
        g.update(eye, toward(eye, new Vec3(10, 0, 4)), false);
        assertEquals(3, g.dz, "no jump when the floor takes over");
        g.update(eye, toward(eye, new Vec3(10, 0, 9)), false);
        assertEquals(8, g.dz, "five blocks of floor is five blocks of build");
        assertEquals(0, g.dx);
        // and back up to the roof's height: the roof's plane takes over again, again without a jump
        g.update(eye, toward(eye, roof.add(0, 0, 5)), false);
        assertEquals(8, g.dz, "no jump going back");
        g.update(eye, toward(eye, roof.add(0, 0, 7)), false);
        assertEquals(10, g.dz);
    }

    @Test
    void aViewRestingBetweenTwoBlocksDoesNotFlicker() {
        Vec3 eye = new Vec3(0, 40, 0);
        Vec3 p0 = new Vec3(20, 10, 0);
        Grab g = Grab.start(eye, toward(eye, p0), p0, 10);
        g.update(eye, toward(eye, p0.add(2.1, 0, 0)), false);
        assertEquals(2, g.dx);
        int flips = 0, last = g.dx;
        for (double wobble : new double[]{2.45, 2.55, 2.48, 2.57, 2.5, 2.52, 2.46}) {
            g.update(eye, toward(eye, p0.add(wobble, 0, 0)), false);
            if (g.dx != last) flips++;
            last = g.dx;
        }
        assertEquals(0, flips, "a wobble around the line between two blocks changes nothing");
        g.update(eye, toward(eye, p0.add(3.0, 0, 0)), false);
        assertEquals(3, g.dx, "clearly past it: the next block");
    }

    @Test
    void wordsSaysWhatMoved() {
        Vec3 eye = new Vec3(0, 70, 0);
        Vec3 p0 = new Vec3(30, 10, 0);
        Grab g = Grab.start(eye, toward(eye, p0), p0, 10);
        assertEquals("not moved", g.words());
        assertFalse(g.moved());
        g.update(eye, toward(eye, p0.add(-4, 0, -2)), false);
        assertEquals("4 west, 2 north", g.words());
    }
}
