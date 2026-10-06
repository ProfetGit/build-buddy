package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;

import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Keys;
import io.github.profetgit.cyanotype.paste.Paste;
import io.github.profetgit.cyanotype.paste.PasteJob;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.ChoiceScreen;
import io.github.profetgit.cyanotype.ui.Tool;
import io.github.profetgit.cyanotype.verify.Verifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Creative paste in the real client (a singleplayer world, so the client reaches the server in the same process): the Build
 * panel offers it only in creative, asks first, puts the whole sample house into the world in slices, the verifier calls it
 * done, and Ctrl+Z takes it out again. Survival says why it is not offered.
 */
final class PasteScenes {
    private PasteScenes() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void mode(String m) {
        act(() -> Director.run(mc(), "gamemode " + m + " " + mc().player.getName().getString()));
        waitTicks(8);
    }

    static void paste() {
        UiScenes.setup();
        mode("creative");
        until("paste/the placement is baked and looked at", 600, () -> GhostRenderer.verifierOf(UiScenes.house) != null && GhostRenderer.verifierOf(UiScenes.house).scanned() && GhostRenderer.verifierOf(UiScenes.house).settled());
        long[] before = new long[2];
        act(() -> {
            check("paste/creative in a world you host: allowed", Paste.unavailable(mc()).isEmpty(), Paste.unavailable(mc()));
            var c = GhostRenderer.verifierOf(UiScenes.house).counts();
            before[0] = c.todo();
            before[1] = c.correct();
            check("paste/nothing of the house stands yet", c.todo() > 100, c.toString());
        });

        // ---- the Build panel's fourth row, then the question
        SaveScenes.chooseThroughTheWheel(Tool.BUILD, 3);
        act(() -> check("paste/the row asks before it does anything", mc().gui.screen() instanceof ChoiceScreen cs && cs.heading().startsWith("Paste it into the world"), String.valueOf(mc().gui.screen())));
        waitTicks(6);
        shot("paste_0_ask");
        act(() -> check("paste/nothing has been placed while asking", Paste.job() == null, String.valueOf(Paste.job())));

        // ---- yes: it goes in, in slices, with a progress panel
        act(() -> SaveScenes.clickScreen(((ChoiceScreen) mc().gui.screen()).anchor(1)));
        waitTicks(3);
        shot("paste_1_running");
        until("paste/finished", 1200, () -> Paste.job() != null && Paste.job().state() == PasteJob.State.DONE);
        until("paste/the verifier has seen it", 600, () -> GhostRenderer.verifierOf(UiScenes.house).settled());
        act(() -> {
            var c = GhostRenderer.verifierOf(UiScenes.house).counts();
            check("paste/every block of the house is in the world", c.todo() == 0 && c.wrong() == 0 && c.missing() == 0, c.toString());
            check("paste/it put in the blocks it needed to", Paste.job().placed() >= before[0] - 5, Paste.job().placed() + " placed, " + before[0] + " were to do");
            Verifier v = GhostRenderer.verifierOf(UiScenes.house);
            BlockState expected = v.expectedAt(UiScenes.house.origin.getX() + 2, UiScenes.house.origin.getY(), UiScenes.house.origin.getZ() + 2);
            BlockState actual = mc().level.getBlockState(new BlockPos(UiScenes.house.origin.getX() + 2, UiScenes.house.origin.getY(), UiScenes.house.origin.getZ() + 2));
            check("paste/a block from inside the house is the right one", expected.isAir() || expected.getBlock() == actual.getBlock(), expected + " vs " + actual);
        });
        waitTicks(30);
        act(() -> check("paste/the progress panel calls it done", GhostRenderer.verifierOf(UiScenes.house).done(), GhostRenderer.verifierOf(UiScenes.house).phase().name()));
        shot("paste_2_done");

        // ---- Ctrl+Z takes it out
        PlaceScenes.ctrlTap(Keys.UNDO, false);
        until("paste/undone", 1200, () -> Paste.job() != null && Paste.job().state() == PasteJob.State.UNDONE);
        until("paste/the verifier sees it gone", 600, () -> GhostRenderer.verifierOf(UiScenes.house).settled());
        act(() -> {
            var c = GhostRenderer.verifierOf(UiScenes.house).counts();
            check("paste/Ctrl+Z puts the world back: the house is missing again", c.todo() >= before[0] - 5 && c.correct() <= before[1] + 5, c + ", before " + before[0] + " to do, " + before[1] + " right");
        });
        shot("paste_3_undone");

        // ---- survival: not offered, with the reason
        mode("survival");
        SaveScenes.chooseThroughTheWheel(Tool.BUILD, 3);
        act(() -> check("paste/in survival the panel says why not", Paste.unavailable(mc()).contains("creative"), Paste.unavailable(mc())));
        waitTicks(4);
        shot("paste_4_survival");
        act(() -> mc().gui.setScreen(null));
        waitTicks(3);
        mode("creative");
        act(() -> {
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            Director.hideHud(mc(), true);
        });
        waitTicks(4);
    }
}
