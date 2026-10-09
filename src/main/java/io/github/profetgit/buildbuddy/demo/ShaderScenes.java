package io.github.profetgit.buildbuddy.demo;

import static io.github.profetgit.buildbuddy.demo.Director.G;
import static io.github.profetgit.buildbuddy.demo.Director.act;
import static io.github.profetgit.buildbuddy.demo.Director.camera;
import static io.github.profetgit.buildbuddy.demo.Director.check;
import static io.github.profetgit.buildbuddy.demo.Director.shot;
import static io.github.profetgit.buildbuddy.demo.Director.until;
import static io.github.profetgit.buildbuddy.demo.Director.waitTicks;

import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.placement.Orientation;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import net.minecraft.core.BlockPos;

/** The ghost through a shader pack being switched off and on (needs PROFILE=1 SHADERS=1: Iris, Sodium and a pack). */
final class ShaderScenes {
    private ShaderScenes() {
    }

    /** Turns Iris's shaders on or off the way its screen does, and reloads; false when Iris is not there. */
    private static boolean shaders(boolean on) {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, on);
            config.getClass().getMethod("save").invoke(config);
            iris.getMethod("reload").invoke(null);
            return true;
        } catch (ReflectiveOperationException e) {
            System.out.println("[cydemo] no Iris: " + e);
            return false;
        }
    }

    static void swap() {
        Director.clean();
        act(() -> {
            Placement p = new Placement("house", Samples.house(), "buildbuddy:house.litematic", Director.DIM, new BlockPos(0, G + 1, 6), Orientation.NONE);
            p.locked = true;
            Placements.add(p);
            Director.house = p;
        });
        until("swap/ghost baked", 600, GhostRenderer::settled);
        camera(5, G + 4, -10, 0, 12);
        waitTicks(20);
        shot("swap_0_start");
        for (int round = 1; round <= 2; round++) {
            final int r = round;
            act(() -> check("swap/shaders switched off " + r, shaders(false), "iris"));
            waitTicks(100);
            act(() -> check("swap/after switching off " + r + ": no mesh of the old layout is left, and the ghost is drawn", GhostRenderer.staleSections() == 0 && GhostRenderer.Stats.quadsDrawn > 0, "stale " + GhostRenderer.staleSections() + ", quads " + GhostRenderer.Stats.quadsDrawn));
            shot("swap_" + r + "_off");
            act(() -> check("swap/shaders switched on " + r, shaders(true), "iris"));
            waitTicks(140);
            act(() -> check("swap/after switching on " + r + ": no mesh of the old layout is left, and the ghost is drawn", GhostRenderer.staleSections() == 0 && GhostRenderer.Stats.quadsDrawn > 0, "stale " + GhostRenderer.staleSections() + ", quads " + GhostRenderer.Stats.quadsDrawn));
            shot("swap_" + r + "_on");
        }
        act(() -> {
            check("swap/the ghost is still there", GhostRenderer.drawn(Director.house), "drawn " + GhostRenderer.drawn(Director.house));
            Placements.clear();
        });
    }
}
