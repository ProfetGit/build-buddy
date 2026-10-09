package io.github.profetgit.buildbuddy.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateTest {
    private static int placed(int perSecond, int ticks) {
        Rate r = Rate.create();
        int n = 0;
        for (int i = 0; i < ticks; i++) if (r.take(perSecond)) n++;
        return n;
    }

    @Test
    void theDefaultIsFourASecond() {
        assertEquals(Rate.DEFAULT, 4);
        // the first block is free: 20 in the first 100 ticks, 21 at most
        int n = placed(4, 100);
        assertTrue(n >= 20 && n <= 21, "placed " + n);
    }

    @Test
    void everyRateHoldsOverTenSeconds() {
        for (int rate = Rate.MIN; rate <= Rate.MAX; rate++) {
            int n = placed(rate, 200);
            assertTrue(n >= rate * 10 && n <= rate * 10 + 1, "rate " + rate + " placed " + n);
        }
    }

    @Test
    void noSecondHoldsMoreThanTheRateAndOne() {
        for (int rate = Rate.MIN; rate <= Rate.MAX; rate++) {
            Rate r = Rate.create();
            boolean[] went = new boolean[400];
            for (int i = 0; i < went.length; i++) went[i] = r.take(rate);
            for (int start = 0; start + 20 <= went.length; start++) {
                int n = 0;
                for (int i = start; i < start + 20; i++) if (went[i]) n++;
                assertTrue(n <= rate + 1, "rate " + rate + ": " + n + " in the second from tick " + start);
            }
        }
    }

    @Test
    void theCeilingCannotBeRaised() {
        assertEquals(20 * 10, placed(1000, 200));
        assertEquals(20 * 10, placed(Integer.MAX_VALUE, 200));
        assertTrue(placed(0, 200) <= 11);
        assertTrue(placed(-5, 200) <= 11);
        assertEquals(Rate.MAX, Rate.clamp(99));
        assertEquals(Rate.MIN, Rate.clamp(0));
    }

    @Test
    void theFirstBlockComesAtOnceAndAPauseSavesNothingUp() {
        Rate r = Rate.create();
        assertTrue(r.take(4));
        assertFalse(r.take(4));
        // a long wait does not let a burst through afterwards
        for (int i = 0; i < 1000; i++) r.tick(4);
        assertTrue(r.ready());
        r.spend();
        assertFalse(r.ready());
        int n = 0;
        for (int i = 0; i < 5; i++) if (r.take(4)) n++;
        assertEquals(1, n);
    }

    @Test
    void atTheCeilingItIsOneBlockEveryTick() {
        // one call a tick places at most one block, so 20 a second is every tick and no more
        assertEquals(100, placed(20, 100));
    }

    @Test
    void resetLetsTheNextOneGoAtOnce() {
        Rate r = Rate.create();
        assertTrue(r.take(2));
        assertFalse(r.take(2));
        r.reset();
        assertTrue(r.take(2));
    }
}
