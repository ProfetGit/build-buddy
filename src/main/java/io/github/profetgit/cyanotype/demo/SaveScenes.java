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
import static io.github.profetgit.cyanotype.demo.PlaceScenes.tap;

import com.google.gson.JsonParser;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.LitematicReader;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Keys;
import io.github.profetgit.cyanotype.interaction.Selecting;
import io.github.profetgit.cyanotype.interaction.SelectionBox;
import io.github.profetgit.cyanotype.placement.BlueprintLibrary;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.SaveScreen;
import io.github.profetgit.cyanotype.ui.Tool;
import io.github.profetgit.cyanotype.ui.Ui;
import io.github.profetgit.cyanotype.ui.WheelScreen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Save area tool in the real client: a small hut is built with commands, two corners are clicked through the real
 * attack path, a face is dragged, the Save screen is filled in, and the file that comes out is read back and laid over
 * the hut: every block must be right.
 */
final class SaveScenes {
    private SaveScenes() {
    }

    static final int X0 = 20, X1 = 26, Z0 = 20, Z1 = 24;

    static Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static String saved(String stem) {
        return stem + ".litematic";
    }

    static void click() {
        act(() -> hold(mc().options.keyAttack));
        waitTicks(3);
        act(() -> release(mc().options.keyAttack));
        waitTicks(3);
    }

    static void rightClick() {
        act(() -> tap(mc().options.keyUse));
        waitTicks(4);
    }

    static void aimCell(double x, double y, double z) {
        act(() -> aim(mc(), x + 0.5, y + 0.5, z + 0.5));
        waitTicks(4);
    }

    /** Opens the Save area tool the way a player does: hold the tool key, point at the segment, let go. */
    static void startThroughTheWheel() {
        startThroughTheWheel(Tool.SAVE);
    }

    static void startThroughTheWheel(Tool tool) {
        act(() -> {
            WheelScreen.testHeld = true;
            Interaction.testMainDown = true;
        });
        waitTicks(8);
        act(() -> Ui.testMouse = UiScenes.segmentPoint(tool.ordinal()));
        waitTicks(8);
        act(() -> {
            WheelScreen.testHeld = false;
            Interaction.testMainDown = false;
        });
        waitTicks(12);
        act(() -> {
            Ui.testMouse = null;
            WheelScreen.testHeld = null;
            Interaction.testMainDown = null;
        });
    }

    static void buildHut() {
        cmd("fill 16 " + (G + 1) + " 16 30 " + (G + 9) + " 28 air");
        waitTicks(6);
        cmd(
            "fill " + X0 + " " + (G + 1) + " " + Z0 + " " + X1 + " " + (G + 1) + " " + Z1 + " minecraft:stone_bricks",
            "fill " + X0 + " " + (G + 2) + " " + Z0 + " " + X1 + " " + (G + 3) + " " + Z1 + " minecraft:oak_planks hollow",
            "setblock 23 " + (G + 3) + " " + Z0 + " minecraft:glass_pane",
            "setblock 23 " + (G + 2) + " " + Z1 + " minecraft:air",
            "setblock 23 " + (G + 3) + " " + Z1 + " minecraft:air",
            "fill " + X0 + " " + (G + 4) + " " + Z0 + " " + X1 + " " + (G + 4) + " " + Z1 + " minecraft:oak_slab",
            "setblock 21 " + (G + 2) + " 21 minecraft:oak_wall_sign[facing=south]",
            "setblock 25 " + (G + 2) + " 23 minecraft:chest");
        waitTicks(20);
    }

    static long worldBlocks(SelectionBox b) {
        long n = 0;
        for (int y = b.y0(); y <= b.y1(); y++) for (int z = b.z0(); z <= b.z1(); z++) for (int x = b.x0(); x <= b.x1(); x++) if (!mc().level.getBlockState(new BlockPos(x, y, z)).isAir()) n++;
        return n;
    }

    static void save() {
        Director.clean();
        act(() -> {
            Director.hideHud(mc(), false);
            SaveScreen.lastSaved = null;
            try (var files = Files.list(BlueprintLibrary.ownDir())) {
                for (Path f : (Iterable<Path>) files::iterator) if (f.getFileName().toString().startsWith("Test Hut")) Files.deleteIfExists(f);
            } catch (IOException e) {
                // no folder yet: nothing to delete
            }
        });

        // ---- safety: clicking a corner must not break the block in creative
        cmd("setblock 40 " + (G + 1) + " 3 minecraft:stone", "gamemode creative Builder");
        camera(40.5, G + 1, 6.5, 0, 0);
        waitTicks(20);
        startThroughTheWheel();
        act(() -> check("save/the wheel's Save area starts the tool", Placements.mode() == Placements.Mode.SELECT && Selecting.stage() == Selecting.Stage.FIRST, "mode " + Placements.mode() + ", stage " + Selecting.stage()));
        aimCell(40, G + 1, 3);
        waitTicks(2);
        shot("save_0_first_corner");
        click();
        act(() -> check("save/a click sets the first corner", Selecting.stage() == Selecting.Stage.SECOND, "stage " + Selecting.stage()));
        act(() -> check("save/the click did not break the block (creative)", mc().level.getBlockState(new BlockPos(40, G + 1, 3)).is(Blocks.STONE), "block " + mc().level.getBlockState(new BlockPos(40, G + 1, 3)).getBlock()));
        rightClick();
        act(() -> check("save/right click steps back to the first corner", Selecting.stage() == Selecting.Stage.FIRST && Placements.mode() == Placements.Mode.SELECT, "stage " + Selecting.stage()));
        act(() -> tap(Keys.MAIN));
        waitTicks(4);
        act(() -> check("save/V cancels and forgets the box", Placements.mode() == Placements.Mode.IDLE && Selecting.box() == null, "mode " + Placements.mode()));
        cmd("setblock 40 " + (G + 1) + " 3 minecraft:air", "gamemode spectator Builder");
        waitTicks(6);

        // ---- the hut and two corners
        buildHut();
        camera(23.5, G + 11, 33, 180, 40);
        waitTicks(20);
        startThroughTheWheel();
        // first corner: the roof's north-west corner, seen from above
        aimCell(X0, G + 4, Z0);
        shot("save_1_roof_corner");
        click();
        act(() -> check("save/first corner set on the roof", Selecting.stage() == Selecting.Stage.SECOND, "stage " + Selecting.stage()));
        // second corner: the south foot, one block short of the hut's east wall (x 25)
        camera(31, G + 3, 30, -140, 12);
        waitTicks(12);
        aimCell(X1 - 1, G + 1, Z1);
        shot("save_2_second_corner_following");
        click();
        SelectionBox[] box = new SelectionBox[1];
        act(() -> {
            box[0] = Selecting.box();
            check("save/second corner sets the box", Selecting.stage() == Selecting.Stage.ADJUST && box[0] != null && box[0].equals(SelectionBox.of(X0, G + 1, Z0, X1 - 1, G + 4, Z1)),
                "box " + box[0]);
        });
        waitTicks(10);
        shot("save_3_box_adjust");

        // ---- drag the east side out by one block: the box now holds the whole hut
        act(() -> {
            Vec3 a = Selecting.faceAnchor("east");
            check("save/the east arrow is there", a != null, "anchor " + a);
            if (a != null) aim(mc(), a.x, a.y, a.z);
        });
        waitTicks(4);
        act(() -> check("save/aiming at an arrow hovers it", Selecting.hovering(), "hover " + Selecting.hovering()));
        act(() -> hold(mc().options.keyAttack));
        waitTicks(3);
        act(() -> check("save/grabbing starts a drag", Selecting.dragging(), "dragging " + Selecting.dragging()));
        Vec3[] grab = new Vec3[1];
        act(() -> grab[0] = Selecting.faceAnchor("east"));
        act(() -> aim(mc(), grab[0].x + 1, grab[0].y, grab[0].z));
        waitTicks(5);
        shot("save_4_dragging");
        act(() -> release(mc().options.keyAttack));
        waitTicks(4);
        act(() -> {
            SelectionBox now = Selecting.box();
            check("save/dragging the east arrow one block out grows the box", now != null && now.equals(SelectionBox.of(X0, G + 1, Z0, X1, G + 4, Z1)) && !Selecting.dragging(), "box " + now);
        });
        // a face cannot be pulled past the one opposite
        act(() -> {
            SelectionBox shrunk = Selecting.box().moved(net.minecraft.core.Direction.UP, -9);
            check("save/a face stops at one block", shrunk.sizeY() == 1, "height " + shrunk.sizeY());
        });

        // ---- the Save screen
        rightClick();
        act(() -> check("save/right click opens the Save screen", screen() instanceof SaveScreen, String.valueOf(screen())));
        waitTicks(10);
        act(() -> {
            SaveScreen s = (SaveScreen) screen();
            s.setName("Test Hut");
            s.setAuthor("Tester");
            s.setTags("hut, Wood");
        });
        waitTicks(6);
        shot("save_5_screen");
        // no name: Enter does nothing
        act(() -> ((SaveScreen) screen()).setName(""));
        waitTicks(3);
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        waitTicks(4);
        act(() -> check("save/without a name Save does nothing", screen() instanceof SaveScreen s && !s.busy() && SaveScreen.lastSaved == null, "screen " + screen()));
        act(() -> ((SaveScreen) screen()).setName("Test Hut"));
        waitTicks(3);
        // Esc goes back to the box, Enter saves
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0)));
        waitTicks(4);
        act(() -> check("save/Esc goes back to the box", screen() == null && Selecting.box() != null && Placements.mode() == Placements.Mode.SELECT, "screen " + screen() + ", mode " + Placements.mode()));
        rightClick();
        waitTicks(8);
        act(() -> {
            SaveScreen s = (SaveScreen) screen();
            check("save/the Save screen opens again after Back", s != null, "screen " + screen());
            s.setName("Test Hut");
            s.setAuthor("Tester");
            s.setTags("hut, Wood");
        });
        waitTicks(4);
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        until("save/saved", 200, () -> SaveScreen.lastSaved != null);
        waitTicks(6);
        act(() -> check("save/the tool ends and the screen closes", screen() == null && Placements.mode() == Placements.Mode.IDLE && Selecting.box() == null, "screen " + screen() + ", mode " + Placements.mode()));
        shot("save_6_done");

        // ---- the file
        act(() -> {
            try {
                Path file = SaveScreen.lastSaved;
                check("save/the file is in the library folder", file.getParent().equals(BlueprintLibrary.ownDir()) && file.getFileName().toString().equals(saved("Test Hut")), String.valueOf(file));
                var tags = JsonParser.parseString(Files.readString(BlueprintLibrary.ownDir().resolve("Test Hut.cyanotype.json"))).getAsJsonObject().getAsJsonArray("tags");
                check("save/the tags are in the sidecar", tags.size() == 2 && tags.get(0).getAsString().equals("hut") && tags.get(1).getAsString().equals("wood"), tags.toString());
                Blueprint bp;
                try (var in = Files.newInputStream(file)) {
                    bp = LitematicReader.read(in, "Test Hut", false);
                }
                check("save/name and author are in the file", bp.meta.name().equals("Test Hut") && bp.meta.author().equals("Tester"), bp.meta.name() + " by " + bp.meta.author());
                SelectionBox full = SelectionBox.of(X0, G + 1, Z0, X1, G + 4, Z1);
                check("save/the size is the hut's", bp.sizeX == 7 && bp.sizeY == 4 && bp.sizeZ == 5, bp.sizeX + "x" + bp.sizeY + "x" + bp.sizeZ);
                long world = worldBlocks(full);
                check("save/as many blocks as the world has there", bp.totalBlocks() == world, bp.totalBlocks() + " in the file, " + world + " in the world");
                Region r = bp.regions.get(0);
                int wrong = 0;
                for (int y = 0; y < r.sy; y++) for (int z = 0; z < r.sz; z++) for (int x = 0; x < r.sx; x++) {
                    BlockState w = mc().level.getBlockState(new BlockPos(X0 + x, G + 1 + y, Z0 + z));
                    BlockState f = r.at(x, y, z).state();
                    if (w.isAir() ? !f.isAir() : !w.equals(f)) wrong++;
                }
                check("save/every block is the one in the world", wrong == 0, wrong + " differ");
                boolean sign = false, chest = false, itemsKept = false;
                for (CompoundTag te : r.blockEntities) {
                    String id = te.getStringOr("id", "");
                    if (id.contains("sign")) sign = true;
                    if (id.contains("chest")) {
                        chest = true;
                        itemsKept = te.contains("Items");
                    }
                }
                check("save/the sign and the chest came along, the chest empty", sign && chest && !itemsKept, "sign " + sign + ", chest " + chest + ", items kept " + itemsKept + ", entities " + r.blockEntities.size());
            } catch (IOException e) {
                check("save/the file can be read", false, e.toString());
            }
        });

        // ---- lay the saved file over the hut: it must be all correct
        act(() -> {
            Placement p = new Placement("saved hut", SaveScreen.lastBlueprint, "cyanotype:Test Hut.litematic", Director.DIM, new BlockPos(X0, G + 1, Z0), Orientation.NONE);
            p.locked = true;
            Placements.add(p);
            UiScenes.house = p;
        });
        until("save/compared with the world", 400, () -> GhostRenderer.verifierOf(UiScenes.house) != null && GhostRenderer.verifierOf(UiScenes.house).settled());
        act(() -> {
            var c = GhostRenderer.verifierOf(UiScenes.house).counts();
            check("save/laid back over the hut every block is correct", c.missing() == 0 && c.wrong() == 0 && c.correct() == UiScenes.house.blueprint.totalBlocks(), c.toString());
        });
        camera(23.5, G + 7, 36, 180, 20);
        waitTicks(12);
        shot("save_7_over_the_hut");
        act(() -> Placements.remove(UiScenes.house));

        // ---- a second save with the same name and the whole box: nothing replaced, nothing trimmed
        act(() -> {
            SaveScreen.lastSaved = null;
            Selecting.testSet(SelectionBox.of(18, G + 1, 18, 28, G + 6, 26));
            mc().gui.setScreen(new SaveScreen(Selecting.box()));
        });
        waitTicks(8);
        act(() -> {
            SaveScreen s = (SaveScreen) screen();
            s.setName("Test Hut");
            s.setOptions(false, false);
            check("save/the screen says it will not replace the first file", true, "target is chosen from the free names");
        });
        waitTicks(4);
        shot("save_8_second");
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        until("save/second saved", 200, () -> SaveScreen.lastSaved != null);
        act(() -> {
            try {
                Path file = SaveScreen.lastSaved;
                check("save/the same name gets a number, the first stays", file.getFileName().toString().equals(saved("Test Hut 2")) && Files.exists(BlueprintLibrary.ownDir().resolve("Test Hut.litematic")), String.valueOf(file.getFileName()));
                Blueprint bp = LitematicReader.read(file);
                check("save/without trimming the whole box is kept", bp.sizeX == 11 && bp.sizeY == 6 && bp.sizeZ == 9, bp.sizeX + "x" + bp.sizeY + "x" + bp.sizeZ);
                check("save/without block data there are none", bp.regions.get(0).blockEntities.isEmpty(), "entities " + bp.regions.get(0).blockEntities.size());
            } catch (IOException e) {
                check("save/second file readable", false, e.toString());
            }
        });

        // ---- the Library lists what was saved
        act(() -> mc().gui.setScreen(new io.github.profetgit.cyanotype.ui.LibraryScreen()));
        until("save/library reads the new files", 200, () -> screen() instanceof io.github.profetgit.cyanotype.ui.LibraryScreen ls
            && ls.entries().stream().filter(e -> e.fileName.startsWith("Test Hut")).allMatch(e -> e.loaded() || e.error != null)
            && ls.entries().stream().anyMatch(e -> e.fileName.startsWith("Test Hut")));
        act(() -> {
            var ls = (io.github.profetgit.cyanotype.ui.LibraryScreen) screen();
            long n = ls.entries().stream().filter(e -> e.fileName.startsWith("Test Hut") && e.loaded()).count();
            boolean tagged = ls.entries().stream().anyMatch(e -> e.fileName.equals("Test Hut.litematic") && e.tags.contains("wood"));
            check("save/the Library lists both, with the tags", n == 2 && tagged, n + " files, tagged " + tagged);
        });
        waitTicks(10);
        shot("save_9_library");
        act(() -> {
            mc().gui.setScreen(null);
            Director.hideHud(mc(), true);
        });
        waitTicks(4);
    }
}
