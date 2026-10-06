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
import static io.github.profetgit.cyanotype.demo.PlaceScenes.hold;
import static io.github.profetgit.cyanotype.demo.PlaceScenes.release;
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
 * Smart Pick in the real client: two houses tied by a fence on a lawn, a tree growing against one of them, a sapling and
 * grass inside one. Click a wall and a box fits itself round that house (not the tree, the lawn or the other house), the sides
 * of the box can be dragged like the Save area's, the Save screen has the switches (ground, trees and plants, other
 * buildings); save with and without the plants and lay the file back over the house.
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
        // a sapling and a tuft of grass growing inside the house: in the box, so they are saved (trees and plants switch)
        cmds.add("setblock " + (AX + 2) + " " + (G + 2) + " 22 minecraft:oak_sapling");
        cmds.add("setblock " + (AX + 4) + " " + (G + 2) + " 22 minecraft:short_grass");
        // a fence along the lawn from one house to the other
        cmds.add("fill " + (AX + 7) + " " + (G + 1) + " 21 " + (BX - 1) + " " + (G + 1) + " 21 minecraft:oak_fence");
        // a tree growing against the first house
        cmds.add("fill " + (AX + 7) + " " + (G + 1) + " 22 " + (AX + 7) + " " + (G + 5) + " 22 minecraft:oak_log");
        cmds.add("fill " + (AX + 7) + " " + (G + 4) + " 20 " + (AX + 10) + " " + (G + 7) + " 24 minecraft:oak_leaves");
        cmds.add("fill " + (AX + 7) + " " + (G + 1) + " 22 " + (AX + 7) + " " + (G + 5) + " 22 minecraft:oak_log");
        act(() -> Director.run(mc(), cmds.toArray(new String[0])));
        waitTicks(30);
    }

    private static boolean boxIs(int x0, int y0, int z0, int x1, int y1, int z1) {
        var b = io.github.profetgit.cyanotype.interaction.Selecting.box();
        return b != null && b.x0() == x0 && b.y0() == y0 && b.z0() == z0 && b.x1() == x1 && b.y1() == y1 && b.z1() == z1;
    }

    static void pick() {
        Director.clean();
        act(() -> {
            Director.hideHud(mc(), false);
            SaveScreen.lastSaved = null;
            SaveScenes.deleteTestFiles("Test House");
        });
        // grass dies under a roof when blocks get random ticks, which would change the ground between saving and checking
        cmd("gamerule random_tick_speed 0");
        buildWorld();
        camera(23.5, G + 7, 32, 180, 28);
        waitTicks(24);
        long[] houseA = new long[1];
        act(() -> houseA[0] = SaveScenes.worldBlocks(SelectionBox.of(AX, G + 1, Z0, AX + 6, G + 4, Z1)));

        act(() -> {
            var grass = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
            var planks = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
            check("pick/blocks have their real textures in the preview", grass.face()[2].px() != null && planks.face()[2].px() != null && grass.face()[2].w() >= 16, "grass top " + grass.face()[2].w() + "x" + grass.face()[2].h());
            var leaves = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
            boolean holes = false;
            for (int px : leaves.face()[2].px()) if ((px >>> 24) < 128) holes = true;
            check("pick/leaves are solid cubes with the right green: no see-through texels", leaves.cube() && !holes && ((leaves.face()[2].avg() >> 8) & 255) > ((leaves.face()[2].avg() >> 16) & 255), "cube " + leaves.cube() + ", holes " + holes);
            var cherry = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.CHERRY_LEAVES.defaultBlockState());
            int cavg = cherry.face()[2].avg();
            check("pick/cherry leaves are pink, not green", ((cavg >> 16) & 255) > ((cavg >> 8) & 255), Integer.toHexString(cavg));
            var azalea = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState());
            check("pick/oak leaves are green", ((leaves.face()[2].avg() >> 8) & 255) > ((leaves.face()[2].avg() >> 16) & 255) && azalea.face()[2].avg() != leaves.face()[2].avg(), Integer.toHexString(leaves.face()[2].avg()) + " vs azalea " + Integer.toHexString(azalea.face()[2].avg()));
            var torch = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
            var grassPlant = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.SHORT_GRASS.defaultBlockState());
            var stair = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState());
            var stone = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            check("pick/a torch, grass and a stair are drawn from their models, stone is a cube", !torch.cube() && !grassPlant.cube() && !stair.cube() && stone.cube(), "torch " + torch.cube() + ", grass " + grassPlant.cube() + ", stair " + stair.cube() + ", stone " + stone.cube());
            check("pick/the grass top is green and its side is not", ((grass.face()[2].avg() >> 8) & 255) > ((grass.face()[2].avg() >> 16) & 255) && grass.face()[2].avg() != grass.face()[4].avg(), Integer.toHexString(grass.face()[2].avg()) + " / " + Integer.toHexString(grass.face()[4].avg()));
        });
        // ---- the wheel starts it; the crosshair says what a click would do
        SaveScenes.startPickThroughTheWheel();
        act(() -> check("pick/the wheel's Smart pick starts the tool", Placements.mode() == Placements.Mode.PICK && !Picking.working(), "mode " + Placements.mode()));
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
        act(() -> check("pick/a click on the lawn does not pick it", Placements.mode() == Placements.Mode.PICK && !Picking.working(), "mode " + Placements.mode()));

        // ---- a click on the wall fits a box round the house
        aimCell(AX + 2, G + 3, Z1);
        click();
        until("pick/the box is fitted", 400, () -> Placements.mode() == Placements.Mode.SELECT && io.github.profetgit.cyanotype.interaction.Selecting.stage() == io.github.profetgit.cyanotype.interaction.Selecting.Stage.ADJUST);
        act(() -> {
            check("pick/the box is the house: not the tree, the lawn or the other house", boxIs(AX, G + 1, Z0, AX + 6, G + 4, Z1), String.valueOf(io.github.profetgit.cyanotype.interaction.Selecting.box()));
            check("pick/Smart Pick itself is done", !Picking.active() && !Picking.working(), "mode " + Placements.mode());
        });
        camera(27, G + 8, 35, 180, 25);
        waitTicks(14);
        shot("pick_2_box");

        // ---- a right click opens Save with the house's switches: no ground, trees and plants in, other buildings out
        rightClick();
        waitTicks(8);
        act(() -> {
            check("pick/a right click opens Save", screen() instanceof SaveScreen, String.valueOf(screen()));
            if (screen() instanceof SaveScreen s) {
                check("pick/the switches start: no ground, plants in, other buildings out", !s.groundOn() && s.natureOn() && s.onlyBuildOn(), "ground " + s.groundOn() + ", plants " + s.natureOn() + ", only " + s.onlyBuildOn());
                s.setName("Test House");
                s.setAuthor("Tester");
                s.setTags("house, oak");
            }
        });
        until("pick/the box is read and drawn for the preview", 400, () -> screen() instanceof SaveScreen sv && sv.readyToSave() && sv.preview().faces() > 0);
        waitTicks(12);
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            check("pick/the preview is what will be saved", sv.captured() != null && sv.captured().totalBlocks() == houseA[0], sv.captured() == null ? "nothing" : sv.captured().totalBlocks() + " blocks, wanted " + houseA[0]);
            // the game numbers the mouse buttons left 1, middle 2, right 3 (not 0, 1, 2)
            var left = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0);
            var right = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT, 0);
            int[] at = sv.anchor("preview");
            var home = sv.preview().view();
            sv.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], left), false);
            sv.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(at[0] + 30, at[1] + 6, left), 30, 6);
            sv.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0] + 30, at[1] + 6, left));
            check("pick/a left drag turns the preview", sv.preview().view().yaw() != home.yaw() && sv.preview().view().pitch() != home.pitch() && sv.preview().view().panX() == home.panX(), sv.preview().view().toString());
            check("pick/and a plain click on a block removes nothing", sv.removedCount() == 0, sv.removedCount() + " removed");
            sv.mouseScrolled(at[0], at[1], 0, 3);
            check("pick/scrolling over the preview zooms it", sv.preview().view().zoom() > 1.5, sv.preview().view().toString());
            double panBefore = sv.preview().view().panX(), yawBefore = sv.preview().view().yaw();
            sv.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], right), false);
            sv.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(at[0] + 20, at[1], right), 20, 0);
            sv.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0] + 20, at[1], right));
            check("pick/a right drag moves it and does not turn it", sv.preview().view().panX() > panBefore + 10 && sv.preview().view().yaw() == yawBefore, sv.preview().view().toString());
        });
        waitTicks(14);
        shot("pick_5b_preview_turned");
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            var left = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0);
            int[] at = sv.anchor("reset");
            sv.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], left), false);
            sv.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], left));
            check("pick/the Reset view button starts the view over", sv.preview().view().equals(io.github.profetgit.cyanotype.ui.PreviewRaster.View.HOME), sv.preview().view().toString());
            // Ctrl held: the block under the pointer lights up
            Interaction.testModifiers = 2;
            io.github.profetgit.cyanotype.ui.Ui.testMouse = sv.anchor("preview");
        });
        waitTicks(16);
        act(() -> check("pick/with Ctrl held, pointing at the picture finds a block", ((SaveScreen) screen()).preview().hovered() >= 0, "hovered " + ((SaveScreen) screen()).preview().hovered()));
        shot("pick_5c_remove_hover");
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            int[] at = sv.anchor("preview");
            var ctrlClick = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 192);
            sv.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], ctrlClick), false);
            sv.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], ctrlClick));
            check("pick/Ctrl+click takes the block under the pointer out of what is saved", sv.removedCount() == 1 && sv.captured().totalBlocks() == houseA[0] - 1, sv.removedCount() + " removed, " + sv.captured().totalBlocks() + " blocks");
        });
        waitTicks(14);
        shot("pick_5d_block_removed");
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            int ctrl = 192;
            sv.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_Z, 0, ctrl));
            check("pick/Ctrl+Z puts the block back", sv.removedCount() == 0 && sv.captured().totalBlocks() == houseA[0], sv.removedCount() + " removed, " + sv.captured().totalBlocks() + " blocks");
            sv.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_Y, 0, ctrl));
            check("pick/Ctrl+Y takes it out again", sv.removedCount() == 1 && sv.captured().totalBlocks() == houseA[0] - 1, sv.removedCount() + " removed");
            sv.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_Z, 0, ctrl));
            Interaction.testModifiers = -1;
            io.github.profetgit.cyanotype.ui.Ui.testMouse = null;
            check("pick/undone: what is saved is the whole build again", sv.captured().totalBlocks() == houseA[0], sv.captured().totalBlocks() + " blocks");
            var banner = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.BANNER.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
            var wallBanner = io.github.profetgit.cyanotype.ui.BlockLook.of(net.minecraft.world.level.block.Blocks.WALL_BANNER.pick(net.minecraft.world.item.DyeColor.BLUE).defaultBlockState());
            check("pick/banners are drawn as a pole and a flag in their colour, not a block", !banner.cube() && banner.model() != null && !wallBanner.cube() && ((banner.face()[0].avg() >> 16) & 255) > ((banner.face()[0].avg()) & 255) && (wallBanner.face()[0].avg() & 255) > ((wallBanner.face()[0].avg() >> 16) & 255), "red " + Integer.toHexString(banner.face()[0].avg()) + ", blue wall " + Integer.toHexString(wallBanner.face()[0].avg()));
        });
        waitTicks(12);
        shot("pick_5_save");
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        until("pick/saved", 300, () -> SaveScreen.lastSaved != null);
        waitTicks(6);
        act(() -> check("pick/the tool ends after saving", screen() == null && Placements.mode() == Placements.Mode.IDLE, "mode " + Placements.mode()));
        act(() -> {
            try {
                Path file = SaveScreen.lastSaved;
                Blueprint bp = LitematicReader.read(file);
                check("pick/saved: the house and the two plants inside it, nothing else", file.getFileName().toString().equals("Test House.litematic") && bp.totalBlocks() == houseA[0] && bp.sizeX == 7 && bp.sizeY == 4 && bp.sizeZ == 5, file.getFileName() + ", " + bp.totalBlocks() + " blocks, " + bp.sizeX + "x" + bp.sizeY + "x" + bp.sizeZ + ", wanted " + houseA[0]);
                Region r = bp.regions.get(0);
                int wrong = 0;
                for (int y = 0; y < r.sy; y++) for (int z = 0; z < r.sz; z++) for (int x = 0; x < r.sx; x++) {
                    BlockState w = mc().level.getBlockState(new BlockPos(AX + x, G + 1 + y, Z0 + z));
                    BlockState f = r.at(x, y, z).state();
                    if (w.isAir() ? !f.isAir() : !w.equals(f)) wrong++;
                }
                check("pick/every saved block is the one in the world", wrong == 0, wrong + " differ");
                check("pick/the sapling and the grass came along", r.at(2, 1, 2).state().getBlock() == net.minecraft.world.level.block.Blocks.OAK_SAPLING && r.at(4, 1, 2).state().getBlock() == net.minecraft.world.level.block.Blocks.SHORT_GRASS, r.at(2, 1, 2).state() + " / " + r.at(4, 1, 2).state());
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
                Placement p = new Placement("picked house", bp, "cyanotype:Test House.litematic", Director.DIM, new BlockPos(AX, G + 1, Z0), Orientation.NONE);
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

        // ---- save again with trees and plants switched off: the same house without them, under a numbered name
        SaveScenes.startPickThroughTheWheel();
        act(() -> SaveScreen.lastSaved = null);
        aimCell(AX + 2, G + 3, Z1);
        click();
        until("pick/the box is fitted again", 400, () -> io.github.profetgit.cyanotype.interaction.Selecting.box() != null);
        rightClick();
        waitTicks(8);
        act(() -> {
            SaveScreen s = (SaveScreen) screen();
            s.setName("Test House");
            s.setKeeps(false, false, true);
        });
        waitTicks(4);
        shot("pick_6_save_no_plants");
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        until("pick/saved again", 300, () -> SaveScreen.lastSaved != null);
        act(() -> {
            try {
                Path file = SaveScreen.lastSaved;
                Blueprint bp = LitematicReader.read(file);
                check("pick/without trees and plants the house alone, under a new name", file.getFileName().toString().equals("Test House 2.litematic") && bp.totalBlocks() == houseA[0] - 2, file.getFileName() + ", " + bp.totalBlocks() + " blocks, wanted " + (houseA[0] - 2));
                check("pick/and the sapling is not in it", bp.regions.get(0).at(2, 1, 2).state().isAir(), String.valueOf(bp.regions.get(0).at(2, 1, 2).state()));
            } catch (IOException e) {
                check("pick/second file", false, e.toString());
            }
        });

        // ---- a bare block of the lawn, asked twice, floods the whole lawn: finishes, sliced, without a hitch
        SaveScenes.startPickThroughTheWheel();
        camera(23.5, G + 7, 32, 180, 28);
        waitTicks(10);
        aimCell(AX + 2, G, 28);
        click();
        act(() -> check("pick/terrain asks first", !Picking.working() && Placements.mode() == Placements.Mode.PICK, "working " + Picking.working()));
        long[] t0 = new long[1];
        act(() -> {
            Director.frameNs.clear();
            Director.measuring = true;
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
            check("pick/no frame hitch while it floods 84 000 blocks", max < 250, String.format(Locale.ROOT, "%d frames, p99 %.1f ms, worst %.1f ms", sorted.size(), p99, max));
        });
        act(() -> {
            double ms = (System.nanoTime() - t0[0]) / 1e6;
            check("pick/a flood over the whole lawn ends in a box or a message, not a hang", Placements.mode() == Placements.Mode.SELECT || Placements.mode() == Placements.Mode.PICK, "mode " + Placements.mode() + ", " + Math.round(ms) + " ms");
        });
        waitTicks(20);
        shot("pick_7_lawn");
        act(() -> tap(Keys.MAIN));
        waitTicks(4);
        act(() -> check("pick/V cancels and forgets", Placements.mode() == Placements.Mode.IDLE, "mode " + Placements.mode()));

        // ---- taking out whole things in the Save preview: a tree, the ground, one block, a stroke of the eraser
        act(() -> {
            SaveScreen sv = new SaveScreen(io.github.profetgit.cyanotype.interaction.SelectionBox.of(AX + 5, G, 18, AX + 12, G + 8, 26));
            mc().gui.setScreen(sv);
        });
        waitTicks(4);
        act(() -> ((SaveScreen) screen()).setKeeps(true, true, false));
        until("pick/the tree box is read and drawn", 400, () -> screen() instanceof SaveScreen sv && sv.readyToSave() && sv.preview().faces() > 0);
        waitTicks(14);
        int[][] pointer = new int[1][];
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            pointer[0] = sv.anchorOfGroup(io.github.profetgit.cyanotype.ui.Groups.Type.TREE);
            check("pick/the picture shows a tree and the ground as things", pointer[0] != null && sv.anchorOfGroup(io.github.profetgit.cyanotype.ui.Groups.Type.GROUND) != null, "tree " + java.util.Arrays.toString(pointer[0]));
            Interaction.testModifiers = 2;
            io.github.profetgit.cyanotype.ui.Ui.testMouse = pointer[0];
        });
        waitTicks(16);
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            var pv = sv.preview();
            check("pick/Ctrl over a tree says what a click takes", pv.groupType(pv.hovered()) == io.github.profetgit.cyanotype.ui.Groups.Type.TREE && pv.groupSize(pv.hovered()) == 83 && sv.bannerText().contains("this tree (83 blocks)"), sv.bannerText() + ", size " + pv.groupSize(pv.hovered()));
        });
        shot("pick_8_tree_hover");
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            int[] at = pointer[0];
            long total = sv.captured().totalBlocks();
            var ctrlClick = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 192);
            sv.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], ctrlClick), false);
            check("pick/nothing is taken until the button goes up", sv.removedCount() == 0, sv.removedCount() + " removed");
            sv.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], ctrlClick));
            check("pick/Ctrl+click takes the whole tree out in one step", sv.removedCount() == 83 && sv.removalSteps() == 1 && sv.captured().totalBlocks() == total - 83, sv.removedCount() + " removed in " + sv.removalSteps() + " steps");
        });
        until("pick/the picture without the tree is drawn", 200, () -> ((SaveScreen) screen()).preview().current());
        waitTicks(6);
        shot("pick_8_tree_gone");
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            sv.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_Z, 0, 192));
            check("pick/Ctrl+Z puts the whole tree back at once", sv.removedCount() == 0, sv.removedCount() + " removed");
        });
        until("pick/the tree is drawn again", 200, () -> ((SaveScreen) screen()).preview().current());
        waitTicks(6);
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            pointer[0] = sv.anchorOfGroup(io.github.profetgit.cyanotype.ui.Groups.Type.GROUND);
            io.github.profetgit.cyanotype.ui.Ui.testMouse = pointer[0];
        });
        waitTicks(14);
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            var pv = sv.preview();
            check("pick/Ctrl over the grass says it takes the ground", pv.groupType(pv.hovered()) == io.github.profetgit.cyanotype.ui.Groups.Type.GROUND && pv.groupSize(pv.hovered()) > 50 && sv.bannerText().contains("the ground"), sv.bannerText() + " at " + java.util.Arrays.toString(pointer[0]) + " hovered " + pv.hovered());
        });
        shot("pick_8_ground_hover");
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            int[] at = pointer[0];
            int size = sv.preview().groupSize(sv.preview().hovered());
            // Ctrl+Shift+click: one block only
            Interaction.testModifiers = 3;
            var one = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 195);
            sv.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], one), false);
            sv.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], one));
            check("pick/Ctrl+Shift+click takes one block of the ground only", sv.removedCount() == 1 && size > 1, sv.removedCount() + " removed, ground was " + size);
            Interaction.testModifiers = 2;
            // a stroke of the eraser across the picture takes what it passes over, as one step
            var ctrl = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 192);
            int before = sv.removedCount(), steps = sv.removalSteps();
            sv.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], ctrl), false);
            for (int i = 1; i <= 8; i++) sv.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(at[0] + i * 6, at[1] - i * 2, ctrl), 6, -2);
            sv.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0] + 48, at[1] - 16, ctrl));
            check("pick/a Ctrl stroke erases the blocks under it in one step", sv.removedCount() > before + 3 && sv.removalSteps() == steps + 1, sv.removedCount() + " removed (before " + before + "), " + sv.removalSteps() + " steps");
        });
        waitTicks(16);
        shot("pick_8_stroke");
        act(() -> {
            SaveScreen sv = (SaveScreen) screen();
            // a plain Ctrl+click on the ground takes all of it
            Interaction.testModifiers = 2;
            var ctrl = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 192);
            int[] at = sv.anchorOfGroup(io.github.profetgit.cyanotype.ui.Groups.Type.GROUND);
            int before = sv.removedCount();
            if (at != null) {
                sv.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], ctrl), false);
                sv.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], ctrl));
            }
            check("pick/Ctrl+click on what is left of the ground takes all of it", at != null && sv.removedCount() > before + 30, sv.removedCount() + " removed, was " + before);
            Interaction.testModifiers = -1;
            io.github.profetgit.cyanotype.ui.Ui.testMouse = null;
            mc().gui.setScreen(null);
        });
        waitTicks(4);

        // ---- the Save area box: blocks inside light up by their own shape while a side moves; scroll grows and shrinks the side you face
        camera(27, G + 8, 35, 180, 25);
        waitTicks(10);
        act(() -> {
            Director.hideHud(mc(), false);
            Placements.clear();
            io.github.profetgit.cyanotype.interaction.Selecting.testSet(SelectionBox.of(AX, G + 1, Z0, AX + 6, G + 4, Z1));
        });
        waitTicks(16);
        act(() -> check("pick/with no side being moved no block is lit", io.github.profetgit.cyanotype.interaction.Selecting.highlighted().isEmpty(), String.valueOf(io.github.profetgit.cyanotype.interaction.Selecting.highlighted().size())));
        shot("pick_9_idle_box");
        grabSide("top");
        act(() -> {
            var lit = io.github.profetgit.cyanotype.interaction.Selecting.highlighted();
            boolean half = false;
            for (var a : lit) if (a.maxY - a.minY < 0.6) half = true;
            check("pick/dragging a side lights the blocks inside, the roof slabs by their half-block shape", lit.size() > 20 && half, lit.size() + " shapes, half-height " + half);
        });
        shot("pick_9_lit");
        letGo();
        act(() -> check("pick/letting go puts the light out", io.github.profetgit.cyanotype.interaction.Selecting.highlighted().isEmpty(), String.valueOf(io.github.profetgit.cyanotype.interaction.Selecting.highlighted().size())));

        // scroll: the side you look at grows (up) or shrinks (down); looking north here
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        act(() -> {
            var b = io.github.profetgit.cyanotype.interaction.Selecting.box();
            check("pick/scroll up grows the side the player faces (north) by one", b != null && b.z0() == Z0 - 1 && b.z1() == Z1 && b.x0() == AX && b.y1() == G + 4, String.valueOf(b));
            check("pick/and the blocks light up while it moves", !io.github.profetgit.cyanotype.interaction.Selecting.highlighted().isEmpty(), "none lit");
        });
        shot("pick_9_scrolled");
        act(() -> scroll(mc(), -1));
        waitTicks(3);
        act(() -> {
            var b = io.github.profetgit.cyanotype.interaction.Selecting.box();
            check("pick/scroll down shrinks it back", b != null && b.z0() == Z0, String.valueOf(b));
            Interaction.testModifiers = 1;
            scroll(mc(), 1);
        });
        waitTicks(3);
        act(() -> {
            var b = io.github.profetgit.cyanotype.interaction.Selecting.box();
            check("pick/Shift+scroll moves it five blocks", b != null && b.z0() == Z0 - 5, String.valueOf(b));
            Interaction.testModifiers = -1;
            scroll(mc(), -5);
        });
        waitTicks(3);
        act(() -> {
            var b = io.github.profetgit.cyanotype.interaction.Selecting.box();
            check("pick/five notches down shrink it five blocks", b != null && b.z0() == Z0, String.valueOf(b));
        });
        waitTicks(40);
        act(() -> check("pick/the light goes out a moment after the last notch", io.github.profetgit.cyanotype.interaction.Selecting.highlighted().isEmpty(), String.valueOf(io.github.profetgit.cyanotype.interaction.Selecting.highlighted().size())));

        // a roof of stairs and slabs lights by its shapes too
        act(() -> {
            java.util.List<String> roof = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                roof.add("fill " + (42 + i) + " " + (G + 1 + i) + " 26 " + (42 + i) + " " + (G + 1 + i) + " 28 " + (i % 2 == 0 ? "minecraft:oak_stairs[facing=east]" : "minecraft:oak_slab"));
            }
            Director.run(mc(), roof.toArray(new String[0]));
        });
        camera(46, G + 8, 38, 180, 22);
        waitTicks(20);
        act(() -> io.github.profetgit.cyanotype.interaction.Selecting.testSet(SelectionBox.of(42, G + 1, 26, 45, G + 8, 28)));
        waitTicks(16);
        grabSide("east");
        act(() -> {
            var lit = io.github.profetgit.cyanotype.interaction.Selecting.highlighted();
            int slabs = 0;
            for (var a : lit) if (a.maxY - a.minY < 0.6 && a.maxY - a.minY > 0.4) slabs++;
            check("pick/stairs and slabs in the box are lit by their shapes", lit.size() >= 12 && slabs >= 3, lit.size() + " shapes, " + slabs + " half-height");
        });
        shot("pick_9_stairs");
        letGo();
        act(() -> io.github.profetgit.cyanotype.interaction.Selecting.cancel(mc()));

        // far from the world's origin the blocks are still read
        camera(3003, G + 10, 3014, 180, 20);
        until("pick/the far chunks are loaded", 600, () -> mc().level != null && mc().level.getChunkSource().hasChunk(3000 >> 4, 3000 >> 4) && mc().level.getChunkSource().hasChunk(3005 >> 4, 3005 >> 4));
        waitTicks(10);
        act(() -> Director.run(mc(), "fill 3000 " + (G + 1) + " 3000 3005 " + (G + 6) + " 3005 minecraft:oak_planks"));
        waitTicks(20);
        act(() -> io.github.profetgit.cyanotype.interaction.Selecting.testSet(SelectionBox.of(3000, G + 1, 3000, 3005, G + 3, 3005)));
        waitTicks(16);
        grabSide("top");
        act(() -> check("pick/far from the origin the blocks inside are lit", io.github.profetgit.cyanotype.interaction.Selecting.highlighted().size() > 20, String.valueOf(io.github.profetgit.cyanotype.interaction.Selecting.highlighted().size())));
        letGo();
        act(() -> {
            io.github.profetgit.cyanotype.interaction.Selecting.cancel(mc());
            Director.hideHud(mc(), true);
            Director.run(mc(), "fill 3000 " + (G + 1) + " 3000 3005 " + (G + 6) + " 3005 minecraft:air");
        });
        // back to the spawn area for the scenes that follow
        camera(23.5, G + 7, 32, 180, 28);
        until("pick/the spawn chunks are loaded again", 600, () -> mc().level != null && mc().level.getChunkSource().hasChunk(1, 1));
        waitTicks(20);
        act(() -> Director.hideHud(mc(), true));
    }

    /** Takes hold of a side of the Save area box by its arrow, the way a player does, and keeps hold. */
    private static void grabSide(String face) {
        act(() -> {
            var a = io.github.profetgit.cyanotype.interaction.Selecting.faceAnchor(face);
            check("pick/the " + face + " arrow is there", a != null, String.valueOf(a));
            if (a != null) aim(mc(), a.x, a.y, a.z);
        });
        waitTicks(5);
        act(() -> hold(mc().options.keyAttack));
        waitTicks(5);
        act(() -> check("pick/the " + face + " side is being dragged", io.github.profetgit.cyanotype.interaction.Selecting.dragging(), "dragging " + io.github.profetgit.cyanotype.interaction.Selecting.dragging()));
    }

    private static void letGo() {
        act(() -> release(mc().options.keyAttack));
        waitTicks(6);
    }

    private static void aimAt(int x, int y, int z) {
        aim(Minecraft.getInstance(), x + 0.5, y + 0.5, z + 0.5);
    }
}
