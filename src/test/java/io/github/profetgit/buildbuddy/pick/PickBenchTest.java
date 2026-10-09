package io.github.profetgit.buildbuddy.pick;

import static io.github.profetgit.buildbuddy.pick.TestWorld.key;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.buildbuddy.TestBootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PickBenchTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    /** How long the longest single slice of a big pick takes on the thread that would be the client thread. */
    @Test
    void aBigFlatBuildNeverTakesLongInOneSlice() {
        TestWorld w = new TestWorld();
        w.flat = false;
        // 90 000 cells: a plaza of stone bricks
        w.fill(0, 1, 0, 299, 1, 299, Blocks.STONE_BRICKS);
        PickSet s = new PickSet(w, Picker.DEFAULT_LIMIT);
        Picker.resetWorst();
        s.start(key(10, 1, 10), null);
        long worst = 0;
        int slices = 0;
        long total = System.nanoTime();
        while (true) {
            long t = System.nanoTime();
            boolean idle = s.step(3_000_000L);
            long d = System.nanoTime() - t;
            worst = Math.max(worst, d);
            slices++;
            if (idle) break;
        }
        total = System.nanoTime() - total;
        System.out.println("[bench] 90k cells: " + slices + " slices, worst slice " + worst / 1e6 + " ms, total " + total / 1e6 + " ms; phases: " + Picker.worstPhases());
        assertTrue(s.picked().size() == 90_000);
        assertTrue(worst < 150_000_000L, "worst slice " + worst / 1e6 + " ms");
    }
}
