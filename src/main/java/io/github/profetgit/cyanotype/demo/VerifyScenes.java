package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.G;
import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.camera;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;

import io.github.profetgit.cyanotype.command.DevCommands;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.verify.Counts;
import io.github.profetgit.cyanotype.verify.Verifier;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** The verifier in the real client: build things in the world with commands and watch the counts and the ghost follow. */
final class VerifyScenes {
    private VerifyScenes() {
    }

    static Placement house;

    static Verifier v() {
        return house == null ? null : GhostRenderer.verifierOf(house);
    }

    static Counts counts() {
        Verifier v = v();
        return v == null ? Counts.EMPTY : v.counts();
    }

    static void cmd(String c) {
        act(() -> Director.run(Minecraft.getInstance(), c));
    }

    static void verify() {
        Director.clean();
        cmd("fill -2 " + (G + 1) + " 3 14 " + (G + 12) + " 22 air");
        act(() -> DevCommands.run("/cyanotype sample"));
        until("verify/blueprint loaded", 200, () -> DevCommands.loadedBlueprint() != null);
        act(() -> {
            DevCommands.run("/cyanotype place 0 " + (G + 1) + " 6");
            house = Placements.active();
        });
        camera(5.5, G + 7, -14, 0, 14);
        until("verify/compared with the world", 400, () -> v() != null && v().settled());
        act(() -> {
            int total = (int) house.blueprint.totalBlocks();
            Counts c = counts();
            check("verify/nothing built yet", c.missing() == total && c.correct() == 0 && c.wrong() == 0, c + " of " + total);
            DevCommands.run("/cyanotype status");
        });
        waitTicks(10);
        shot("verify_0_empty");

        // the foundation: 11 x 13 cobblestone at the base
        cmd("fill 0 " + (G + 1) + " 6 10 " + (G + 1) + " 18 cobblestone");
        until("verify/foundation counted", 200, () -> counts().correct() == 143);
        act(() -> check("verify/foundation is correct", counts().correct() == 143 && counts().wrong() == 0, counts().toString()));
        waitTicks(20);
        shot("verify_1_foundation");

        // a wrong block and a right one in the first wall layer
        cmd("setblock 1 " + (G + 2) + " 6 dirt");
        cmd("setblock 2 " + (G + 2) + " 6 oak_planks");
        until("verify/wrong block seen", 200, () -> counts().wrong() == 1);
        act(() -> {
            Counts c = counts();
            check("verify/dirt where planks belong is wrong", c.wrong() == 1 && c.correct() == 144, c.toString());
            check("verify/status of the cells", v().statusAt(1, G + 2, 6) == Verifier.WRONG && v().statusAt(2, G + 2, 6) == Verifier.CORRECT, "dirt " + v().statusAt(1, G + 2, 6) + ", planks " + v().statusAt(2, G + 2, 6));
        });
        waitTicks(25);
        shot("verify_2_wrong");

        // mend it
        cmd("setblock 1 " + (G + 2) + " 6 oak_planks");
        until("verify/mended", 200, () -> counts().wrong() == 0 && counts().correct() == 145);
        act(() -> check("verify/mending turns it correct", counts().wrong() == 0 && counts().correct() == 145, counts().toString()));

        // break one of the foundation again: missing, and the lowest unfinished layer is layer 0 again
        cmd("setblock 5 " + (G + 1) + " 12 air");
        until("verify/break seen", 200, () -> counts().correct() == 144);
        act(() -> {
            Interaction.guide = true;
        });
        waitTicks(12);
        act(() -> {
            long t = Interaction.guideTarget();
            check("verify/next block is the hole in the lowest layer", t == BlockPos.asLong(5, G + 1, 12), t == Verifier.NO_TARGET ? "none" : BlockPos.of(t).toShortString());
        });
        shot("verify_3_next");
        cmd("setblock 5 " + (G + 1) + " 12 cobblestone");
        until("verify/hole mended", 200, () -> counts().correct() == 145);
        waitTicks(12);
        act(() -> {
            long t = Interaction.guideTarget();
            check("verify/next block moves up a layer", t != Verifier.NO_TARGET && BlockPos.getY((long) t) == G + 2, t == Verifier.NO_TARGET ? "none" : BlockPos.of(t).toShortString());
            Interaction.guide = false;
        });

        // layer focus
        double[] quads = new double[3];
        act(() -> {
            DevCommands.run("/cyanotype layer all");
        });
        waitTicks(8);
        act(() -> quads[0] = GhostRenderer.Stats.quadsDrawn);
        act(() -> DevCommands.run("/cyanotype layer 3 4"));
        waitTicks(8);
        act(() -> quads[1] = GhostRenderer.Stats.quadsDrawn);
        shot("verify_4_layers");
        act(() -> DevCommands.run("/cyanotype layer 1"));
        waitTicks(8);
        act(() -> quads[2] = GhostRenderer.Stats.quadsDrawn);
        act(() -> {
            check("verify/layers limit what is drawn", quads[0] > quads[1] && quads[1] > 0, String.format(Locale.ROOT, "all %.0f, layers 3-4 %.0f", quads[0], quads[1]));
            check("verify/a finished layer draws nothing", quads[2] == 0, "layer 1: " + quads[2] + " quads");
            DevCommands.run("/cyanotype layer all");
        });
        waitTicks(6);

        // build the whole house with one fill per kind: the ghost should vanish into the real thing
        cmd("fill 0 " + (G + 2) + " 6 10 " + (G + 5) + " 18 oak_planks");
        waitTicks(30);
        shot("verify_5_walls");
        act(() -> {
            Counts c = counts();
            check("verify/a plank fill is right for planks and wrong for logs, glass and doors", c.wrong() >= 20 && c.correct() > 250, c.toString());
        });
        // clean up the world
        cmd("fill -2 " + (G + 1) + " 3 14 " + (G + 12) + " 22 air");
        waitTicks(20);
        act(() -> {
            Counts c = counts();
            check("verify/clearing it all makes it all missing", c.correct() == 0 && c.wrong() == 0 && c.missing() == (int) house.blueprint.totalBlocks(), c.toString());
            Placements.remove(house);
        });
    }

    /**
     * A large uniform build, to see what verifying costs: the scan of a placement, then a mass edit that makes all of it
     * correct at once. Frame times are collected while it runs.
     */
    static void perf() {
        Director.clean();
        int sx = 80, sy = 20, sz = 80;
        cmd("gamerule max_block_modifications 5000000");
        cmd("fill -10 " + (G + 1) + " 10 " + (sx + 10) + " " + (G + sy + 2) + " " + (sz + 20) + " air");
        Placement[] big = new Placement[1];
        act(() -> {
            io.github.profetgit.cyanotype.blueprint.Blueprint bp = Samples.uniform(sx, sy, sz);
            big[0] = new Placement("uniform", bp, "cyanotype:uniform.litematic", Director.DIM, new BlockPos(0, G + 1, 20), io.github.profetgit.cyanotype.placement.Orientation.NONE);
            big[0].locked = true;
            Placements.add(big[0]);
            System.out.println("[cydemo] verify perf: " + bp.totalBlocks() + " blocks");
        });
        camera(sx / 2.0, G + 12, 20 - 40, 0, 12);
        Director.measure("verify perf, ghost baking");
        until("verify-perf/compared", 1200, () -> GhostRenderer.verifierOf(big[0]) != null && GhostRenderer.verifierOf(big[0]).settled());
        act(() -> {
            Counts c = GhostRenderer.verifierOf(big[0]).counts();
            check("verify-perf/everything missing", c.missing() == sx * sy * sz && c.correct() == 0, c.toString());
        });
        Director.measure("verify perf, nothing built");
        // build all of it in one go
        cmd("fill 0 " + (G + 1) + " 20 " + (sx - 1) + " " + (G + sy) + " " + (sz + 19) + " stone_bricks");
        Director.measure("verify perf, while it is built");
        until("verify-perf/all correct", 2400, () -> GhostRenderer.verifierOf(big[0]).counts().correct() == sx * sy * sz);
        act(() -> check("verify-perf/the mass edit was picked up", GhostRenderer.verifierOf(big[0]).counts().done(), GhostRenderer.verifierOf(big[0]).counts().toString()));
        waitTicks(40);
        Director.measure("verify perf, all built");
        cmd("fill -10 " + (G + 1) + " 10 " + (sx + 10) + " " + (G + sy + 2) + " " + (sz + 20) + " air");
        waitTicks(40);
        act(() -> Placements.remove(big[0]));
    }
}
