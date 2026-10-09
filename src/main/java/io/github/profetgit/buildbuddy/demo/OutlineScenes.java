package io.github.profetgit.buildbuddy.demo;

import static io.github.profetgit.buildbuddy.demo.Director.G;
import static io.github.profetgit.buildbuddy.demo.Director.act;
import static io.github.profetgit.buildbuddy.demo.Director.check;
import static io.github.profetgit.buildbuddy.demo.Director.shootViews;
import static io.github.profetgit.buildbuddy.demo.Director.until;
import static io.github.profetgit.buildbuddy.demo.Director.waitTicks;

import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.interaction.Interaction;
import io.github.profetgit.buildbuddy.interaction.Selecting;
import io.github.profetgit.buildbuddy.placement.Orientation;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import io.github.profetgit.buildbuddy.interaction.SelectionBox;
import io.github.profetgit.buildbuddy.verify.Verifier;
import net.minecraft.core.BlockPos;

/** The boxes drawn round a placement, from several sides: they must show with and without a shader pack. */
final class OutlineScenes {
    private static final double[][] VIEWS = {
        {5, G + 34, 12, 0, 85}, {5, G + 5, -14, 0, 8}, {5, G + 2, -6, 0, -5}, {-5, G + 4, 12, -90, 5}, {5, G - 4, 12, 0, -60}, {5, G + 3, 12, 20, 5},
    };

    private OutlineScenes() {
    }

    static void outline() {
        Director.clean();
        act(() -> {
            Placement p = Director.locked(new Placement("house", Samples.house(), "buildbuddy:house.litematic", Director.DIM, new BlockPos(0, G + 1, 6), Orientation.NONE));
            Placements.add(p);
            Director.house = p;
        });
        until("outline/ghost baked", 600, GhostRenderer::settled);
        act(() -> Placements.setMode(Placements.Mode.IDLE));
        shootViews("outline_idle", VIEWS);
        act(() -> Placements.setMode(Placements.Mode.EDIT));
        shootViews("outline_edit", VIEWS);
        act(() -> {
            Placements.setMode(Placements.Mode.IDLE);
            Director.house.layerLo = Director.house.layerHi = 2;
        });
        shootViews("outline_layer", VIEWS);
        act(() -> {
            Director.house.layerLo = Director.house.layerHi = -1;
            Interaction.guide = true;
        });
        waitTicks(20);
        act(() -> check("outline/the next block marker has a target", Interaction.guideTarget() != Verifier.NO_TARGET, "target " + Interaction.guideTarget()));
        shootViews("outline_next", VIEWS);
        act(() -> {
            Interaction.guide = false;
            Selecting.testSet(SelectionBox.of(-3, G + 1, 4, 14, G + 9, 22));
        });
        shootViews("outline_save", VIEWS);
        act(() -> {
            Placements.setMode(Placements.Mode.IDLE);
            Placements.remove(Director.house);
        });
        waitTicks(4);
    }
}
