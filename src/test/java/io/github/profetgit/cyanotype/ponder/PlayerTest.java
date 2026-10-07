package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerTest {
    private static Scene demo() {
        return SceneReader.read(SceneReaderTest.GOOD);
    }

    @Test
    void playsFromTheStartAndLoops() {
        Player p = new Player(demo(), false);
        assertTrue(p.playing());
        assertEquals(0, p.time(), 1e-9);
        for (int i = 0; i < 100; i++) p.update(0.05);
        assertEquals(5, p.time(), 1e-6);
        for (int i = 0; i < 110; i++) p.update(0.05);
        assertEquals(0.5, p.time(), 1e-6, "past 10 s it is back at the start and counting");
        assertEquals(1, p.loops());
    }

    @Test
    void aLongFrameDoesNotJumpTheLesson() {
        Player p = new Player(demo(), false);
        p.update(5.0);
        assertEquals(0.25, p.time(), 1e-9, "a stall of five seconds moves it a quarter second at most");
    }

    @Test
    void pausedDoesNotMoveAndSpeedSlowsIt() {
        Player p = new Player(demo(), false);
        p.pause();
        p.update(1);
        assertEquals(0, p.time(), 1e-9);
        p.play();
        p.cycleSpeed();
        p.update(0.2);
        assertEquals(0.1, p.time(), 1e-9);
        p.cycleSpeed();
        assertEquals(1.0, p.speed(), 1e-9);
    }

    @Test
    void stepsJumpToTheirStart() {
        Player p = new Player(demo(), false);
        p.next();
        assertEquals(1, p.step());
        assertEquals(4, p.time(), 1e-9);
        p.next();
        assertEquals(0, p.step(), "after the last step it goes round to the first");
        assertEquals(0, p.time(), 1e-9);
    }

    @Test
    void previousRestartsAStepThatHasBeenGoingThenGoesBack() {
        Player p = new Player(demo(), false);
        p.seek(6);
        p.previous();
        assertEquals(4, p.time(), 1e-9, "step two had been going for two seconds: it starts over");
        p.previous();
        assertEquals(0, p.step(), "at its start the step before plays");
        assertEquals(0, p.time(), 1e-9);
    }

    @Test
    void reducedMotionOpensPausedOnTheStillOfTheFirstStep() {
        Player p = new Player(demo(), true);
        assertFalse(p.playing());
        assertEquals(3.95, p.time(), 1e-9);
        assertEquals(0, p.step());
        p.next();
        assertEquals(1, p.step());
        assertEquals(9.95, p.time(), 1e-9);
        assertFalse(p.playing());
        p.play();
        p.update(0.1);
        assertTrue(p.time() > 9.95 || p.loops() > 0, "play still plays");
    }

    @Test
    void restartGoesBackToTheStart() {
        Player p = new Player(demo(), false);
        p.seek(7);
        p.restart();
        assertEquals(0, p.time(), 1e-9);
        assertTrue(p.playing());
        assertEquals(0, p.loops());
    }

    @Test
    void seekStaysInsideTheLesson() {
        Player p = new Player(demo(), false);
        p.seek(-3);
        assertEquals(0, p.time(), 1e-9);
        p.seek(99);
        assertTrue(p.time() < 10);
    }
}
