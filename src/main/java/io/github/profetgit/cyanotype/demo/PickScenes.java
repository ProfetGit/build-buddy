package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.G;
import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.camera;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.cmd;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;
import static io.github.profetgit.cyanotype.demo.PlaceScenes.aim;
import static io.github.profetgit.cyanotype.demo.PlaceScenes.scroll;
import static io.github.profetgit.cyanotype.demo.PlaceScenes.tap;
import static io.github.profetgit.cyanotype.demo.SaveScenes.aimCell;
import static io.github.profetgit.cyanotype.demo.SaveScenes.click;
import static io.github.profetgit.cyanotype.demo.SaveScenes.mc;
import static io.github.profetgit.cyanotype.demo.SaveScenes.rightClick;
import static io.github.profetgit.cyanotype.demo.SaveScenes.screen;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.LitematicReader;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Keys;
import io.github.profetgit.cyanotype.interaction.Picking;
import io.github.profetgit.cyanotype.interaction.SelectionBox;
import io.github.profetgit.cyanotype.placement.BlueprintLibrary;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.SaveScreen;
import io.github.profetgit.cyanotype.ui.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Smart Pick in the real client: two houses tied by a fence on a lawn, a tree growing against one of them. Click a wall and
 * the house is picked, not the tree, the lawn or the other house; add the other house, take it out, change the reach,
 * start over; save the pick (with and without the ground) and lay the file back over the house.
 */
final class PickScenes {
    /** What the mouse handler does when a button goes down with Shift (3) or Ctrl (192) held. */
    static final class PickModifiers {
        static void press(int mods) {
            Interaction.noteClick(mods);
        }
    }

    private PickScenes() {
    }

    static final int AX = 20, BX = 34, Z0 = 20, Z1 = 24;

    private static void hut(int x0, java.util.List<String> out) {
        int x1 = x0 + 6;
        out.add("fill " + x0 + " " + (G + 1) + " " + Z0 + " " + x1 + " " + (G + 1) + " " + Z1 + " minecraft:stone_bricks");
        out.add("fill " + x0 + " " + (G + 2) + " " + Z0 + " " + x1 + " " + (G + 3) + " " + Z1 + " minecraft:oak_planks hollow");
        out.add("setblock " + (x0 + 3) + " " + (G + 3) + " " + Z0 + " minecraft:glass_pane");
        out.add("setblock " + (x0 + 3) + " " + (G + 2) + " " + Z1 + " minecraft:air");
        out.add("setblock " + (x0 + 3) + " " + (G + 3) + " " + Z1 + " minecraft:air");
        out.add("fill " + x0 + " " + (G + 4) + " " + Z0 + " " + x1 + " " + (G + 4) + " " + Z1 + " minecraft:oak_slab");
    }

    static void buildWorld() {
        cmd("fill 16 " + (G + 1) + " 14 50 " + (G + 12) + " 30 air");
        waitTicks(8);
        java.util.List<String> cmds = new java.util.ArrayList<>();
        hut(AX, cmds);
        hut(BX, cmds);
        cmds.add("setblock " + (AX + 1) + " " + (G + 2) + " 21 minecraft:oak_wall_sign[facing=south]");
        cmds.add("setblock " + (AX + 5) + " " + (G + 2) + " 23 minecraft:chest");
        // a fence along the lawn from one house to the other
        cmds.add("fill " + (AX + 7) + " " + (G + 1) + " 21 " + (BX - 1) + " " + (G + 1) + " 21 minecraft:oak_fence");
        // a tree growing against the first house
        cmds.add("fill " + (AX + 7) + " " + (G + 1) + " 22 " + (AX + 7) + " " + (G + 5) + " 22 minecraft:oak_log");
        cmds.add("fill " + (AX + 7) + " " + (G + 4) + " 20 " + (AX + 10) + " " + (G + 7) + " 24 minecraft:oak_leaves");
        cmds.add("fill " + (AX + 7) + " " + (G + 1) + " 22 " + (AX + 7) + " " + (G + 5) + " 22 minecraft:oak_log");
        act(() -> Director.run(mc(), cmds.toArray(new String[0])));
        waitTicks(30);
    }

    static boolean has(int x, int y, int z) {
        return Picking.picked().contains(BlockPos.asLong(x, y, z));
    }

    static void settled() {
        until("pick/finished", 400, () -> !Picking.working() && Picking.meshCurrent());
    }

    static void pick() {
        Director.clean();
        act(() -> {
            Director.hideHud(mc(), false);
            SaveScreen.lastSaved = null;
            try (var files = Files.list(BlueprintLibrary.ownDir())) {
                for (Path f : (Iterable<Path>) files::iterator) if (f.getFileName().toString().startsWith("Test House")) Files.deleteIfExists(f);
            } catch (IOException e) {
                // no folder yet
            }
        });
        // grass dies under a roof when blocks get random ticks, which would change the ground between saving and checking
        cmd("gamerule random_tick_speed 0");
        buildWorld();
        camera(23.5, G + 7, 32, 180, 28);
        waitTicks(24);
        long[] houseA = new long[1];
        act(() -> houseA[0] = SaveScenes.worldBlocks(SelectionBox.of(AX, G + 1, Z0, AX + 6, G + 4, Z1)));

        // ---- the wheel starts it; the crosshair says what a click would do
        SaveScenes.startPickThroughTheWheel();
        act(() -> check("pick/the wheel's Smart pick starts the tool", Placements.mode() == Placements.Mode.PICK && Picking.stage() == Picking.Stage.AIM, "mode " + Placements.mode()));
        aimCell(AX + 2, G + 3, Z1);
        shot("pick_0_aim_wall");
        // right click with nothing picked leaves the tool
        rightClick();
        act(() -> check("pick/right click with nothing picked leaves the tool", Placements.mode() == Placements.Mode.IDLE, "mode " + Placements.mode()));
        SaveScenes.startPickThroughTheWheel();

        // ---- terrain asks twice
        aimCell(AX + 2, G, 28);
        shot("pick_1_aim_grass");
        click();
        act(() -> check("pick/a click on the lawn does not pick it", Picking.stage() == Picking.Stage.AIM && !Picking.working() && Picking.picked().isEmpty(), "stage " + Picking.stage()));

        // ---- a click on the wall picks the house
        aimCell(AX + 2, G + 3, Z1);
        click();
        settled();
        act(() -> {
            check("pick/a click on a wall picks a build", Picking.stage() == Picking.Stage.RESULT, "stage " + Picking.stage());
            for (long c : Picking.picked()) if (BlockPos.getX(c) > AX + 6) System.out.println("[cydemo] EXTRA " + BlockPos.of(c) + " " + mc().level.getBlockState(BlockPos.of(c)));
            check("pick/it is the house, block for block", Picking.picked().size() == houseA[0], Picking.picked().size() + " picked, " + houseA[0] + " in the house");
            check("pick/the tree against the wall is not in it", !has(AX + 7, G + 2, 22) && !has(AX + 8, G + 5, 22), "trunk " + has(AX + 7, G + 2, 22));
            check("pick/the lawn is not in it", !has(AX + 2, G, Z1 + 1) && !has(AX + 2, G, Z0 - 1), "lawn");
            check("pick/the other house and the fence are not in it", !has(BX + 2, G + 3, Z0) && !has(AX + 10, G + 1, 21), "other house " + has(BX + 2, G + 3, Z0));
            check("pick/it is drawn", Picking.drawnQuads() > 20, Picking.drawnQuads() + " rectangles");
        });
        camera(27, G + 8, 35, 180, 25);
        waitTicks(14);
        shot("pick_2_house");

        // ---- add the other house with shift, take it out with ctrl
        act(() -> aimAt(BX + 2, G + 3, Z1));
        waitTicks(6);
        shot("pick_3_other_offered");
        // the modifiers come with the click, as the window system reports them (what a real press carries)
        act(() -> PickModifiers.press(3));
        click();
        waitTicks(6);
        settled();
        act(() -> check("pick/shift+click adds the other house", has(BX + 2, G + 3, Z1) && has(AX + 2, G + 3, Z1), "other " + has(BX + 2, G + 3, Z1)));
        waitTicks(6);
        shot("pick_4_both");
        act(() -> PickModifiers.press(192));
        click();
        waitTicks(6);
        settled();
        act(() -> check("pick/ctrl+click takes it out again", !has(BX + 2, G + 3, Z1) && has(AX + 2, G + 3, Z1) && Picking.picked().size() == houseA[0], Picking.picked().size() + " picked"));

        // ---- precise edits: Ctrl+scroll sets a cube size, Ctrl+click takes the cube out, Shift+click puts it back
        act(() -> {
            Interaction.testModifiers = 2;
            scroll(mc(), 2);
        });
        waitTicks(4);
        act(() -> check("pick/ctrl+scroll picks the 3x3x3 cut", Picking.detail() == 2, "detail " + Picking.detail()));
        act(() -> aimAt(AX + 2, G + 3, Z1));
        waitTicks(6);
        shot("pick_3b_cube_preview");
        click();
        waitTicks(6);
        act(() -> {
            long n = houseA[0] - Picking.picked().size();
            check("pick/ctrl+click with a cut size takes only that cube out", n > 0 && n <= 27 && !has(AX + 2, G + 3, Z1) && has(AX + 2, G + 3, Z0), n + " blocks went");
        });
        shot("pick_3c_cube_out");
        act(() -> Interaction.testModifiers = 1);
        click();
        waitTicks(6);
        act(() -> check("pick/shift+click puts the cube back", Picking.picked().size() == houseA[0] && has(AX + 2, G + 3, Z1), Picking.picked().size() + " picked"));
        act(() -> {
            Interaction.testModifiers = 2;
            scroll(mc(), -5);
        });
        waitTicks(2);
        act(() -> {
            Interaction.testModifiers = -1;
            check("pick/ctrl+scroll back down returns to whole parts", Picking.detail() == 0, "detail " + Picking.detail());
        });

        // ---- the reach: scroll up and down, the pick stays the house
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        settled();
        act(() -> check("pick/scroll widens the reach", Picking.reach() == 2 && has(AX + 2, G + 3, Z1), "reach " + Picking.reach()));
        act(() -> scroll(mc(), -5));
        waitTicks(4);
        settled();
        act(() -> check("pick/scroll down stops at zero", Picking.reach() == 0 && has(AX + 2, G + 3, Z1), "reach " + Picking.reach()));
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        settled();

        // ---- right click starts over; a second click picks a lone block of terrain, if asked twice
        rightClick();
        act(() -> check("pick/right click starts over", Picking.stage() == Picking.Stage.AIM && Picking.picked().isEmpty() && Placements.mode() == Placements.Mode.PICK, "stage " + Picking.stage()));

        // ---- pick again and save it, with the ground
        aimCell(AX + 2, G + 3, Z1);
        click();
        settled();
        act(() -> check("pick/picked again", Picking.picked().size() == houseA[0], Picking.picked().size() + " picked"));
        aimCell(AX + 2, G + 3, Z1);
        click();
        waitTicks(8);
        act(() -> check("pick/a click on the pick opens Save", screen() instanceof SaveScreen, String.valueOf(screen())));
        act(() -> {
            SaveScreen s = (SaveScreen) screen();
            s.setName("Test House");
            s.setAuthor("Tester");
            s.setTags("house, oak");
        });
        waitTicks(6);
        shot("pick_5_save");
        act(() -> {
            var s = (SaveScreen) screen();
            int[] at = s.anchor("ground");
            var info = new net.minecraft.client.input.MouseButtonInfo(0, 0);
            s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info), false);
            s.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info));
            check("pick/the ground under it can be included", s.groundOn(), "ground " + s.groundOn());
        });
        waitTicks(4);
        shot("pick_6_save_ground");
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        until("pick/saved", 300, () -> SaveScreen.lastSaved != null);
        waitTicks(6);
        act(() -> check("pick/the tool ends after saving", screen() == null && Placements.mode() == Placements.Mode.IDLE && Picking.picked().isEmpty(), "mode " + Placements.mode()));
        act(() -> {
            try {
                Path file = SaveScreen.lastSaved;
                Blueprint bp;
                try (var in = Files.newInputStream(file)) {
                    bp = LitematicReader.read(in, "t", false);
                }
                check("pick/saved with the ground: the house and one layer of grass", bp.totalBlocks() == houseA[0] + 35 && bp.sizeX == 7 && bp.sizeY == 5 && bp.sizeZ == 5, bp.totalBlocks() + " blocks, " + bp.sizeX + "x" + bp.sizeY + "x" + bp.sizeZ);
                Region r = bp.regions.get(0);
                int wrong = 0, trees = 0;
                for (int y = 0; y < r.sy; y++) for (int z = 0; z < r.sz; z++) for (int x = 0; x < r.sx; x++) {
                    BlockState w = mc().level.getBlockState(new BlockPos(AX + x, G + y, Z0 + z));
                    BlockState f = r.at(x, y, z).state();
                    if (w.isAir() ? !f.isAir() : !w.equals(f)) wrong++;
                }
                check("pick/every saved block is the one in the world", wrong == 0, wrong + " differ");
                boolean sign = false, chest = false;
                for (var te : r.blockEntities) {
                    String id = te.getStringOr("id", "");
                    if (id.contains("sign")) sign = true;
                    if (id.contains("chest")) chest = true;
                }
                check("pick/the sign and chest data came along", sign && chest, "sign " + sign + ", chest " + chest);
            } catch (IOException e) {
                check("pick/the file can be read", false, e.toString());
            }
        });

        // ---- laid back over the house it is all correct
        act(() -> {
            try {
                Blueprint bp = LitematicReader.read(SaveScreen.lastSaved);
                Placement p = new Placement("picked house", bp, "cyanotype:Test House.litematic", Director.DIM, new BlockPos(AX, G, Z0), Orientation.NONE);
                p.locked = true;
                Placements.add(p);
                UiScenes.house = p;
            } catch (IOException e) {
                check("pick/laid back", false, e.toString());
            }
        });
        until("pick/compared with the world", 400, () -> GhostRenderer.verifierOf(UiScenes.house) != null && GhostRenderer.verifierOf(UiScenes.house).settled());
        act(() -> {
            var c = GhostRenderer.verifierOf(UiScenes.house).counts();
            check("pick/laid back over the house every block is correct", c.missing() == 0 && c.wrong() == 0 && c.correct() == UiScenes.house.blueprint.totalBlocks(), c.toString());
            Placements.remove(UiScenes.house);
        });

        // ---- save again without the ground: the same house, no grass, a numbered name
        SaveScenes.startPickThroughTheWheel();
        act(() -> SaveScreen.lastSaved = null);
        aimCell(AX + 2, G + 3, Z1);
        click();
        settled();
        aimCell(AX + 2, G + 3, Z1);
        click();
        waitTicks(8);
        act(() -> {
            SaveScreen s = (SaveScreen) screen();
            s.setName("Test House");
        });
        waitTicks(4);
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        until("pick/saved again", 300, () -> SaveScreen.lastSaved != null);
        act(() -> {
            try {
                Path file = SaveScreen.lastSaved;
                Blueprint bp = LitematicReader.read(file);
                check("pick/without the ground just the house, under a new name", file.getFileName().toString().equals("Test House 2.litematic") && bp.totalBlocks() == houseA[0] && bp.sizeY == 4, file.getFileName() + ", " + bp.totalBlocks() + " blocks, height " + bp.sizeY);
            } catch (IOException e) {
                check("pick/second file", false, e.toString());
            }
        });

        // ---- a bare block of the lawn, asked twice, picks the whole lawn as one big flood: finishes, sliced, without a hitch
        SaveScenes.startPickThroughTheWheel();
        camera(23.5, G + 7, 32, 180, 28);
        waitTicks(10);
        aimCell(AX + 2, G, 28);
        click();
        act(() -> check("pick/terrain asks first", Picking.picked().isEmpty() && !Picking.working(), "working " + Picking.working()));
        long[] t0 = new long[1];
        act(() -> {
            Director.frameNs.clear();
            Director.measuring = true;
            Picking.worstSliceNs = 0;
            Picking.worstStepNs = 0;
            io.github.profetgit.cyanotype.pick.Picker.resetWorst();
            t0[0] = System.nanoTime();
        });
        click();
        until("pick/the lawn flood ends", 1500, () -> !Picking.working());
        act(() -> {
            Director.measuring = false;
            java.util.List<Long> sorted = new java.util.ArrayList<>(Director.frameNs);
            sorted.sort(null);
            double p99 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.99)) / 1e6, max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1) / 1e6;
            Director.perf.add(String.format(Locale.ROOT, "{\"measure\":\"pick/lawn flood\",\"frames\":%d,\"p99FrameMs\":%.3f,\"maxFrameMs\":%.3f}", sorted.size(), p99, max));
            check("pick/no frame hitch while it floods 84 000 blocks", max < 250, String.format(Locale.ROOT, "%d frames, p99 %.1f ms, worst %.1f ms, longest pick tick %.1f ms (the work itself %.1f ms); longest call per phase: %s", sorted.size(), p99, max, Picking.worstSliceNs / 1e6, Picking.worstStepNs / 1e6, io.github.profetgit.cyanotype.pick.Picker.worstPhases()));
        });
        act(() -> {
            double ms = (System.nanoTime() - t0[0]) / 1e6;
            check("pick/a flood over the whole lawn ends and says what it found", Picking.stage() == Picking.Stage.RESULT || Picking.picked().isEmpty(), "stage " + Picking.stage() + ", " + Picking.picked().size() + " blocks, " + Math.round(ms) + " ms");
        });
        waitTicks(20);
        shot("pick_7_lawn");
        act(() -> tap(Keys.MAIN));
        waitTicks(4);
        act(() -> check("pick/V cancels and forgets", Placements.mode() == Placements.Mode.IDLE && Picking.picked().isEmpty(), "mode " + Placements.mode()));
        act(() -> Director.hideHud(mc(), true));
    }

    private static void aimAt(int x, int y, int z) {
        aim(Minecraft.getInstance(), x + 0.5, y + 0.5, z + 0.5);
    }
}
