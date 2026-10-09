package io.github.profetgit.buildbuddy.demo;

import static io.github.profetgit.buildbuddy.demo.Director.G;
import static io.github.profetgit.buildbuddy.demo.Director.act;
import static io.github.profetgit.buildbuddy.demo.Director.camera;
import static io.github.profetgit.buildbuddy.demo.Director.check;
import static io.github.profetgit.buildbuddy.demo.Director.until;
import static io.github.profetgit.buildbuddy.demo.Director.waitTicks;

import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.placement.Orientation;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Blocks put down 10 to 20 times a second under a ghost: the ghost's quad count may only step down by the faces of the placed blocks, never dip and come back. */
final class FlickerScenes {
    private static final int SX = 20, SY = 4, SZ = 20;
    private static final long WINDOW_NS = 500_000_000L;
    private static final double MIN_RATIO = 0.7;

    private FlickerScenes() {
    }

    static void flicker() {
        run("all layers, 10 blocks a second", -1, -1, 2, 100);
        run("one layer, 10 blocks a second", 1, 1, 2, 100);
        run("one layer, 20 blocks a second", 1, 1, 1, 160);
        run("window of two layers, 10 blocks a second", 1, 2, 2, 100);
    }

    private static void run(String label, int lo, int hi, int period, int count) {
        Director.clean();
        Placement[] pl = new Placement[1];
        Director.cmd("fill -2 " + (G + 1) + " 4 " + (SX + 2) + " " + (G + SY + 2) + " " + (SZ + 10) + " air");
        act(() -> {
            pl[0] = Director.locked(new Placement("flicker", Samples.uniform(SX, SY, SZ), "buildbuddy:flicker.litematic", Director.DIM, new BlockPos(0, G + 1, 6), Orientation.NONE));
            Placements.add(pl[0]);
        });
        camera(SX / 2.0, G + 18, 6 + SZ / 2.0, 0, 90);
        until("flicker/" + label + ": baked", 600, () -> GhostRenderer.settled() && GhostRenderer.verifierOf(pl[0]) != null && GhostRenderer.verifierOf(pl[0]).settled());
        act(() -> {
            pl[0].layerLo = lo;
            pl[0].layerHi = hi;
        });
        until("flicker/" + label + ": window settled", 400, GhostRenderer::settled);
        waitTicks(40);
        int[] placed = {0};
        List<long[]> rec = new ArrayList<>();
        act(() -> GhostRenderer.Stats.record = rec);
        waitTicks(10);
        int[] t = {0};
        int firstLayer = Math.max(lo, 0);
        Director.add(mc -> {
            if (t[0]++ % period != 0) return false;
            if (placed[0] >= count) return true;
            int i = placed[0]++;
            Director.run(mc, String.format(Locale.ROOT, "setblock %d %d %d stone_bricks", i % SX, G + 1 + firstLayer, 6 + i / SX));
            return false;
        });
        waitTicks(30);
        act(() -> {
            GhostRenderer.Stats.record = null;
            analyse(label, rec, pl[0]);
            Placements.remove(pl[0]);
        });
        Director.cmd("fill -2 " + (G + 1) + " 4 " + (SX + 2) + " " + (G + SY + 2) + " " + (SZ + 10) + " air");
        waitTicks(6);
    }

    private static void analyse(String label, List<long[]> rec, Placement p) {
        int n = rec.size();
        double worst = 10;
        int worstAt = -1, dipFrames = 0;
        long minQ = Long.MAX_VALUE, maxQ = 0, minCaps = Long.MAX_VALUE, maxCaps = 0;
        int zeroFrames = 0;
        for (int i = 0; i < n; i++) {
            long[] r = rec.get(i);
            minQ = Math.min(minQ, r[1]);
            maxQ = Math.max(maxQ, r[1]);
            minCaps = Math.min(minCaps, r[3]);
            maxCaps = Math.max(maxCaps, r[3]);
            if (r[1] == 0) zeroFrames++;
            long prev = -1, next = -1;
            for (int j = i - 1; j >= 0 && r[0] - rec.get(j)[0] <= WINDOW_NS; j--) prev = Math.max(prev, rec.get(j)[1]);
            for (int j = i + 1; j < n && rec.get(j)[0] - r[0] <= WINDOW_NS; j++) next = Math.max(next, rec.get(j)[1]);
            if (prev < 0 || next < 0) continue;
            double base = Math.min(prev, next);
            if (base <= 0) continue;
            double ratio = r[1] / base;
            if (ratio < MIN_RATIO) dipFrames++;
            if (ratio < worst) {
                worst = ratio;
                worstAt = i;
            }
        }
        String detail = String.format(Locale.ROOT, "%d frames, quads %d..%d, caps %d..%d, worst dip ratio %.3f at frame %d, %d frames below %.0f%%, %d frames with nothing drawn",
            n, minQ, maxQ, minCaps, maxCaps, worst, worstAt, dipFrames, MIN_RATIO * 100, zeroFrames);
        check("flicker/" + label + ": enough frames recorded", n >= 100 && maxQ > 0, detail);
        check("flicker/" + label + ": the quad count never dips and comes back", dipFrames == 0 && zeroFrames == 0, detail);
        if (p.layered()) check("flicker/" + label + ": caps are drawn", maxCaps > 0 && minCaps > 0, detail);
    }
}
