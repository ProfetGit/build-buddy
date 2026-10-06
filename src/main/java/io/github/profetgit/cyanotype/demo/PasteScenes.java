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

    /** How many of a placement's solid blocks stand in the world as the blueprint has them: {right, total}. */
    private static int[] worldMatch(Placement p) {
        var r = p.blueprint.regions.get(0);
        int ok = 0, total = 0;
        for (int y = 0; y < r.sy; y++) for (int z = 0; z < r.sz; z++) for (int x = 0; x < r.sx; x++) {
            BlockState want = r.at(x, y, z).state();
            if (want.isAir()) continue;
            total++;
            if (mc().level.getBlockState(new BlockPos(p.origin.getX() + r.x + x, p.origin.getY() + r.y + y, p.origin.getZ() + r.z + z)).getBlock() == want.getBlock()) ok++;
        }
        return new int[]{ok, total};
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
        waitTicks(6);
        act(() -> {
            int[] m = worldMatch(UiScenes.house);
            check("paste/the ghost is gone once its build is in the world", !Placements.all().contains(UiScenes.house) && Placements.active() == null, "placements " + Placements.all().size());
            check("paste/every block of the house is in the world", m[1] > 100 && m[0] >= m[1] - 5, m[0] + " of " + m[1]);
            check("paste/it put in the blocks it needed to", Paste.job().placed() >= before[0] - 5, Paste.job().placed() + " placed, " + before[0] + " were to do");
        });
        shot("paste_2_done");

        // ---- Ctrl+Z takes it out and brings the ghost back
        PlaceScenes.ctrlTap(Keys.UNDO, false);
        until("paste/undone", 1200, () -> Paste.job() != null && Paste.job().state() == PasteJob.State.UNDONE);
        waitTicks(6);
        until("paste/the ghost is back and looked at", 600, () -> Placements.all().contains(UiScenes.house) && GhostRenderer.verifierOf(UiScenes.house) != null && GhostRenderer.verifierOf(UiScenes.house).settled());
        act(() -> {
            var c = GhostRenderer.verifierOf(UiScenes.house).counts();
            check("paste/Ctrl+Z puts the world back: the house is missing again", c.todo() >= before[0] - 5 && c.correct() <= before[1] + 5, c + ", before " + before[0] + " to do, " + before[1] + " right");
            check("paste/and the ghost is selected again", Placements.active() == UiScenes.house, String.valueOf(Placements.active()));
        });
        shot("paste_3_undone");

        // ---- the P key: no menu, no question; the ghost goes when the build is in
        act(() -> PlaceScenes.tap(Keys.PASTE));
        waitTicks(4);
        act(() -> check("paste/P opens no screen", mc().gui.screen() == null, String.valueOf(mc().gui.screen())));
        until("paste/P pastes the placed ghost", 1200, () -> Paste.job() != null && Paste.job().state() == PasteJob.State.DONE && !Placements.all().contains(UiScenes.house));
        act(() -> {
            int[] m = worldMatch(UiScenes.house);
            check("paste/P: the build is in the world", m[1] > 100 && m[0] >= m[1] - 5, m[0] + " of " + m[1]);
        });
        PlaceScenes.ctrlTap(Keys.UNDO, false);
        until("paste/P: undone, ghost back", 1200, () -> Placements.all().contains(UiScenes.house) && Paste.job().state() == PasteJob.State.UNDONE);
        waitTicks(10);

        // ---- P while the ghost still follows the crosshair: it is put down where it is and pasted at once
        act(() -> {
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            io.github.profetgit.cyanotype.interaction.Interaction.startPlacing("p test", io.github.profetgit.cyanotype.demo.Samples.house(), "cyanotype:none.litematic");
        });
        waitTicks(20);
        PasteJob[] firstJob = new PasteJob[1];
        act(() -> {
            firstJob[0] = Paste.job();
            check("paste/a ghost is following the crosshair", Placements.mode() == Placements.Mode.PLACING && Placements.active() != null && !Placements.active().locked, Placements.mode() + "");
            PlaceScenes.tap(Keys.PASTE);
        });
        until("paste/P while placing pastes it", 1500, () -> Paste.job() != null && Paste.job() != firstJob[0] && Paste.job().state() == PasteJob.State.DONE);
        waitTicks(6);
        act(() -> check("paste/the ghost is gone and nothing is left being placed", Placements.all().isEmpty() && Placements.mode() == Placements.Mode.IDLE && Paste.job().placed() > 100, Placements.all().size() + " placements, mode " + Placements.mode() + ", placed " + Paste.job().placed()));
        shot("paste_5_placing_p");
        PlaceScenes.ctrlTap(Keys.UNDO, false);
        until("paste/undone again", 1200, () -> Paste.job().state() == PasteJob.State.UNDONE);
        waitTicks(10);

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
