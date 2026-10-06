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
import io.github.profetgit.cyanotype.ui.Ui;
import io.github.profetgit.cyanotype.ui.WheelScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** The screens and the wheel in the real client, with the mouse and the tool key driven through the seams. */
final class UiScenes {
    private UiScenes() {
    }

    static Placement house;

    static Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    /** Places the sample house locked, with the HUD on. */
    static void setup() {
        Director.clean();
        act(() -> {
            Director.hideHud(Minecraft.getInstance(), false);
            DevCommands.run("/cyanotype sample");
        });
        waitTicks(10);
        act(() -> {
            DevCommands.run("/cyanotype place 0 " + (G + 1) + " 6");
            house = Placements.active();
        });
        camera(5.5, G + 6, -16, 0, 12);
        until("ui/baked", 400, () -> GhostRenderer.verifierOf(house) != null && GhostRenderer.verifierOf(house).settled());
        waitTicks(10);
    }

    static void teardown() {
        act(() -> {
            Ui.testMouse = null;
            WheelScreen.testHeld = null;
            Interaction.testMainDown = null;
            Minecraft.getInstance().gui.setScreen(null);
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            Director.hideHud(Minecraft.getInstance(), true);
        });
        waitTicks(4);
    }

    /** Where the mouse has to be to point at wheel segment {@code i}. */
    static int[] segmentPoint(int i) {
        Screen s = screen();
        int n = ((io.github.profetgit.cyanotype.ui.WheelScreen) s).toolCount();
        double[] at = io.github.profetgit.cyanotype.ui.WheelGeometry.pointAt(i, n, 40);
        return new int[]{(int) Math.round(s.width / 2.0 + at[0]), (int) Math.round(s.height / 2.0 + at[1])};
    }

    static void library() {
        Director.clean();
        act(() -> {
            try {
                java.nio.file.Files.createDirectories(io.github.profetgit.cyanotype.placement.BlueprintLibrary.ownDir());
                java.nio.file.Path dir = io.github.profetgit.cyanotype.placement.BlueprintLibrary.ownDir();
                // start from a known library: the sample files only
                try (var files = java.nio.file.Files.list(dir)) {
                    for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) java.nio.file.Files.deleteIfExists(f);
                }
                java.nio.file.Path other = io.github.profetgit.cyanotype.placement.BlueprintLibrary.litematicaDir();
                if (java.nio.file.Files.isDirectory(other)) {
                    try (var files = java.nio.file.Files.list(other)) {
                        for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) java.nio.file.Files.deleteIfExists(f);
                    }
                }
                write(dir, "sample-house", Samples.house());
                write(dir, "watch-tower", Samples.tower());
                write(dir, "pyramid", Samples.pyramid());
                write(dir, "arch-bridge", Samples.bridge());
                write(dir, "big-noise", Samples.noise(40, 0.45));
                java.nio.file.Files.write(dir.resolve("watch-tower.litematic"), java.nio.file.Files.readAllBytes(dir.resolve("watch-tower.litematic")));
                io.github.profetgit.cyanotype.placement.LibraryIndex.put(dir.resolve("watch-tower.litematic"), java.util.List.of("castle", "medieval"), null);
                java.nio.file.Files.writeString(dir.resolve("broken.litematic"), "this is not a litematic file");
            } catch (java.io.IOException e) {
                check("library/files written", false, e.toString());
            }
            Director.hideHud(Minecraft.getInstance(), false);
            Minecraft.getInstance().gui.setScreen(new io.github.profetgit.cyanotype.ui.LibraryScreen());
        });
        waitTicks(8);
        until("library/everything read", 200, () -> screen() instanceof io.github.profetgit.cyanotype.ui.LibraryScreen ls && ls.entries().stream().allMatch(e -> e.loaded() || e.error != null));
        act(() -> {
            io.github.profetgit.cyanotype.ui.LibraryScreen ls = (io.github.profetgit.cyanotype.ui.LibraryScreen) screen();
            check("library/six files listed, the broken one marked", ls.entries().size() == 6 && ls.entries().stream().filter(e -> e.error != null).count() == 1, ls.entries().size() + " entries");
        });
        waitTicks(10);
        shot("library_0");
        act(() -> {
            io.github.profetgit.cyanotype.ui.LibraryScreen ls = (io.github.profetgit.cyanotype.ui.LibraryScreen) screen();
            int idx = 0;
            for (int i = 0; i < ls.entries().size(); i++) if (ls.entries().get(i).title().toLowerCase().contains("tower")) idx = i;
            Ui.testMouse = ls.cardCenter(idx);
        });
        waitTicks(10);
        shot("library_1_hover");
        waitTicks(5);
        shot("library_2_hover_turned");
        act(() -> {
            io.github.profetgit.cyanotype.ui.LibraryScreen ls = (io.github.profetgit.cyanotype.ui.LibraryScreen) screen();
            ls.searchFor("tower");
            Ui.testMouse = new int[]{0, 0};
        });
        waitTicks(8);
        act(() -> {
            io.github.profetgit.cyanotype.ui.LibraryScreen ls = (io.github.profetgit.cyanotype.ui.LibraryScreen) screen();
            check("library/search finds by name and tag", ls.entries().size() == 1 && ls.entries().get(0).title().toLowerCase().contains("tower"), ls.entries().size() + " shown");
        });
        shot("library_3_search");
        act(() -> {
            io.github.profetgit.cyanotype.ui.LibraryScreen ls = (io.github.profetgit.cyanotype.ui.LibraryScreen) screen();
            ls.searchFor("castle");
        });
        waitTicks(4);
        act(() -> check("library/search finds by tag", ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).entries().size() == 1, "tag castle"));
        act(() -> ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).searchFor("zzzz"));
        waitTicks(6);
        shot("library_4_empty_search");
        act(() -> ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).searchFor(""));
        waitTicks(4);
        // sort by size: the biggest build first
        act(() -> ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).sortBy("SIZE"));
        waitTicks(8);
        act(() -> {
            var list = ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).entries();
            boolean ordered = true;
            for (int i = 1; i < list.size(); i++) {
                long a = list.get(i - 1).info == null ? -1 : list.get(i - 1).info.blocks(), b = list.get(i).info == null ? -1 : list.get(i).info.blocks();
                if (a < b) ordered = false;
            }
            check("library/sorted by size", ordered, list.get(0).title());
        });
        shot("library_5_size");
        // drop a file on the window
        act(() -> {
            try {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("dropped", ".litematic");
                io.github.profetgit.cyanotype.blueprint.LitematicWriter.write(Samples.uniform(5, 4, 5), tmp);
                ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).onFilesDrop(java.util.List.of(tmp));
            } catch (java.io.IOException e) {
                check("library/drop", false, e.toString());
            }
        });
        waitTicks(10);
        act(() -> check("library/a dropped file is added", ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).entries().size() == 7, ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).entries().size() + " entries"));
        shot("library_6_dropped");
        // the library follows its folder: a file added or removed shows up (or goes) by itself, an older tags file moves into the index, a .schematic says why it cannot open
        act(() -> {
            try {
                java.nio.file.Path dir = io.github.profetgit.cyanotype.placement.BlueprintLibrary.saveDir();
                write(dir, "late-arrival", Samples.uniform(3, 3, 3));
                java.nio.file.Files.writeString(dir.resolve("late-arrival.cyanotype.json"), "{\"tags\":[\"fresh\"]}");
                // an old MCEdit .schematic (stone, gold block, wool) and a Sponge .schem (planks), as other tools write them
                net.minecraft.nbt.CompoundTag legacy = new net.minecraft.nbt.CompoundTag(), root = new net.minecraft.nbt.CompoundTag();
                legacy.putShort("Width", (short) 3);
                legacy.putShort("Height", (short) 1);
                legacy.putShort("Length", (short) 1);
                legacy.putString("Materials", "Alpha");
                legacy.putByteArray("Blocks", new byte[]{1, 41, 35});
                legacy.putByteArray("Data", new byte[]{0, 0, 14});
                root.put("Schematic", legacy);
                net.minecraft.nbt.NbtIo.writeCompressed(root, dir.resolve("old-house.schematic"));
                net.minecraft.nbt.CompoundTag sp = new net.minecraft.nbt.CompoundTag(), pal = new net.minecraft.nbt.CompoundTag(), spRoot = new net.minecraft.nbt.CompoundTag();
                pal.putInt("minecraft:air", 0);
                pal.putInt("minecraft:oak_planks", 1);
                sp.putInt("Version", 2);
                sp.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
                sp.putShort("Width", (short) 2);
                sp.putShort("Height", (short) 2);
                sp.putShort("Length", (short) 2);
                sp.put("Palette", pal);
                sp.putByteArray("BlockData", new byte[]{1, 1, 1, 1, 0, 1, 1, 0});
                spRoot.put("Schematic", sp);
                net.minecraft.nbt.NbtIo.writeCompressed(spRoot, dir.resolve("sponge-shed.schem"));
            } catch (java.io.IOException e) {
                check("library/live files written", false, e.toString());
            }
        });
        waitTicks(30);
        act(() -> {
            var list = ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).entries();
            var late = list.stream().filter(e -> e.fileName.equals("late-arrival.litematic")).findFirst().orElse(null);
            var old = list.stream().filter(e -> e.fileName.equals("old-house.schematic")).findFirst().orElse(null);
            var spongy = list.stream().filter(e -> e.fileName.equals("sponge-shed.schem")).findFirst().orElse(null);
            check("library/a file added to the folder appears by itself", late != null && list.size() == 10, list.size() + " entries");
            check("library/its older tags file moved into the index and is gone", late != null && late.tags.contains("fresh") && !java.nio.file.Files.exists(io.github.profetgit.cyanotype.placement.BlueprintLibrary.saveDir().resolve("late-arrival.cyanotype.json")), late == null ? "none" : String.valueOf(late.tags));
            check("library/an old .schematic opens: three blocks, no error", old != null && old.error == null && old.info != null && old.info.blocks() == 3, old == null ? "none" : old.error + " / " + old.info);
            check("library/a Sponge .schem opens: six blocks, no error", spongy != null && spongy.error == null && spongy.info != null && spongy.info.blocks() == 6 && spongy.info.sx() == 2, spongy == null ? "none" : spongy.error + " / " + spongy.info);
        });
        shot("library_6b_live");
        act(() -> {
            try {
                java.nio.file.Path dir = io.github.profetgit.cyanotype.placement.BlueprintLibrary.saveDir();
                java.nio.file.Files.deleteIfExists(dir.resolve("late-arrival.litematic"));
                java.nio.file.Files.deleteIfExists(dir.resolve("old-house.schematic"));
                java.nio.file.Files.deleteIfExists(dir.resolve("sponge-shed.schem"));
            } catch (java.io.IOException e) {
                check("library/live files removed", false, e.toString());
            }
        });
        waitTicks(30);
        act(() -> check("library/files removed from the folder go by themselves", ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).entries().size() == 7, ((io.github.profetgit.cyanotype.ui.LibraryScreen) screen()).entries().size() + " entries"));
        // pick the pyramid: the library closes and placing starts
        act(() -> {
            io.github.profetgit.cyanotype.ui.LibraryScreen ls = (io.github.profetgit.cyanotype.ui.LibraryScreen) screen();
            ls.sortBy("NAME");
        });
        waitTicks(6);
        act(() -> {
            io.github.profetgit.cyanotype.ui.LibraryScreen ls = (io.github.profetgit.cyanotype.ui.LibraryScreen) screen();
            int idx = -1;
            for (int i = 0; i < ls.entries().size(); i++) if (ls.entries().get(i).title().toLowerCase().contains("pyramid")) idx = i;
            int[] c = ls.cardCenter(idx);
            var info = new net.minecraft.client.input.MouseButtonInfo(0, 0);
            ls.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(c[0], c[1], info), false);
            ls.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(c[0], c[1], info));
        });
        waitTicks(12);
        act(() -> {
            Placement p = Placements.active();
            check("library/clicking a card starts placing it", screen() == null && Placements.mode() == Placements.Mode.PLACING && p != null && p.name.toLowerCase().contains("pyramid"), "screen " + screen() + ", mode " + Placements.mode() + ", " + (p == null ? "none" : p.name));
        });
        camera(5.5, G + 5, -14, 0, 14);
        waitTicks(14);
        shot("library_7_placing");
        act(() -> {
            Ui.testMouse = null;
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            Director.hideHud(Minecraft.getInstance(), true);
        });
        waitTicks(4);
    }

    static void write(java.nio.file.Path dir, String name, io.github.profetgit.cyanotype.blueprint.Blueprint bp) throws java.io.IOException {
        io.github.profetgit.cyanotype.blueprint.LitematicWriter.write(bp, dir.resolve(name + ".litematic"));
    }

    private static void click(int[] at) {
        Screen s = screen();
        var info = new net.minecraft.client.input.MouseButtonInfo(0, 0);
        s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info), false);
        s.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info));
    }

    private static void key(int code) {
        screen().keyPressed(new net.minecraft.client.input.KeyEvent(code, 0, 0));
    }

    /** The wheel cut for other numbers of tools, to see that the lines stay between the icons. */
    static void wheelCounts() {
        setup();
        for (int n : new int[]{3, 5, 12}) {
            io.github.profetgit.cyanotype.ui.Tool[] all = io.github.profetgit.cyanotype.ui.Tool.values();
            io.github.profetgit.cyanotype.ui.Tool[] set = new io.github.profetgit.cyanotype.ui.Tool[n];
            for (int i = 0; i < n; i++) set[i] = all[i % all.length];
            act(() -> {
                WheelScreen.testTools = set;
                WheelScreen.testHeld = true;
                Interaction.testMainDown = true;
            });
            waitTicks(8);
            act(() -> Ui.testMouse = segmentPoint(1));
            waitTicks(12);
            shot("wheel_n" + n);
            act(() -> {
                Ui.testMouse = new int[]{screen().width / 2, screen().height / 2};
                WheelScreen.testHeld = false;
                Interaction.testMainDown = false;
            });
            waitTicks(10);
            act(() -> check("wheel/" + n + " tools: the wheel opened with that many segments and closed again", screen() == null, "screen " + screen()));
        }
        act(() -> WheelScreen.testTools = null);
        teardown();
    }

    private static String at(Placement p) {
        return p.origin.getX() + " " + p.origin.getY() + " " + p.origin.getZ();
    }

    /** Panes, bars, fences and walls written with plain states must show up connected. */
    static void shapes() {
        Director.clean();
        act(() -> {
            try {
                java.nio.file.Path file = Director.OUT.resolve("shapes.litematic");
                java.nio.file.Files.createDirectories(Director.OUT);
                io.github.profetgit.cyanotype.blueprint.LitematicWriter.write(Samples.shapes(), file);
                io.github.profetgit.cyanotype.blueprint.Blueprint bp = io.github.profetgit.cyanotype.blueprint.LitematicReader.read(file);
                Placement p = Director.locked(new Placement("shapes", bp, "cyanotype:shapes.litematic", Director.DIM, new net.minecraft.core.BlockPos(0, G + 1, 6), io.github.profetgit.cyanotype.placement.Orientation.NONE));
                Placements.add(p);
                house = p;
                // the pane in the middle of the first row, the fence in its row, the bars' centre
                var pane = bp.regions.get(0).at(2, 1, 0).state();
                var fence = bp.regions.get(0).at(3, 0, 2).state();
                var bars = bp.regions.get(0).at(4, 0, 4).state();
                check("shapes/a pane between pillars meets both sides", pane.getValue(net.minecraft.world.level.block.IronBarsBlock.EAST) && pane.getValue(net.minecraft.world.level.block.IronBarsBlock.WEST), pane.toString());
                check("shapes/a fence meets the fences on both sides", fence.getValue(net.minecraft.world.level.block.FenceBlock.EAST) && fence.getValue(net.minecraft.world.level.block.FenceBlock.WEST), fence.toString());
                check("shapes/the bars' crossing meets all four", bars.getValue(net.minecraft.world.level.block.IronBarsBlock.EAST) && bars.getValue(net.minecraft.world.level.block.IronBarsBlock.WEST)
                    && bars.getValue(net.minecraft.world.level.block.IronBarsBlock.NORTH) && bars.getValue(net.minecraft.world.level.block.IronBarsBlock.SOUTH), bars.toString());
            } catch (java.io.IOException e) {
                check("shapes/read", false, e.toString());
            }
        });
        camera(4.5, G + 3.5, 0.5, 0, 25);
        until("shapes/baked", 400, GhostRenderer::settled);
        waitTicks(6);
        shot("shapes_front");
        camera(-2.5, G + 7, 2.5, -55, 45);
        waitTicks(12);
        shot("shapes_above");
        act(() -> Placements.remove(house));
        waitTicks(4);
    }

    /** The Layers tool must show the faces that the layers around it would have covered. */
    static void layerCaps() {
        Director.clean();
        act(() -> {
            Placement p = Director.locked(new Placement("slab", Samples.uniform(3, 2, 3), "cyanotype:caps.litematic", io.github.profetgit.cyanotype.demo.Director.DIM, new net.minecraft.core.BlockPos(0, G + 1, 6), io.github.profetgit.cyanotype.placement.Orientation.NONE));
            Placements.add(p);
            house = p;
        });
        camera(1.5, G + 6, 2.5, 0, 50);
        until("caps/baked", 400, GhostRenderer::settled);
        // a 3 x 2 x 3 solid box: a layer shows its four sides (12 quads) and one open face per block (9), whole or cut
        act(() -> {
            house.layerLo = house.layerHi = 0;
        });
        until("caps/the lower layer alone shows its top faces (12 sides + 9 bottoms + 9 tops)", 300, () -> GhostRenderer.Stats.quadsDrawn == 30);
        waitTicks(6);
        shot("caps_layer0");
        act(() -> {
            house.layerLo = house.layerHi = 1;
        });
        until("caps/the upper layer alone shows its bottom faces (12 sides + 9 tops + 9 bottoms)", 300, () -> GhostRenderer.Stats.quadsDrawn == 30);
        waitTicks(6);
        camera(1.5, G - 2, 2.5, 0, -50);
        waitTicks(10);
        shot("caps_layer1_from_below");
        camera(1.5, G + 6, 2.5, 0, 50);
        act(() -> {
            house.layerLo = 0;
            house.layerHi = 1;
        });
        until("caps/both layers: the faces between them stay hidden (12 + 12 sides, 9 tops, 9 bottoms)", 300, () -> GhostRenderer.Stats.quadsDrawn == 42);
        act(() -> {
            house.layerLo = house.layerHi = -1;
        });
        until("caps/no layer focus: the same 42", 300, () -> GhostRenderer.Stats.quadsDrawn == 42);
        // moving the window by one finds the next caps ready
        act(() -> {
            house.layerLo = house.layerHi = 0;
        });
        until("caps/back to the lower layer", 300, () -> GhostRenderer.Stats.quadsDrawn == 30);
        act(() -> {
            house.layerLo = house.layerHi = -1;
            Placements.remove(house);
        });
        waitTicks(4);
    }

    /** Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z in the real client: moves, a removal, redo, and the wheel's Undo and Redo. */
    static void undo() {
        setup();
        act(() -> DevCommands.run("/cyanotype move 4 " + (G + 1) + " 6"));
        waitTicks(4);
        act(() -> check("undo/the move happened", house.origin.getX() == 4, at(house)));
        // a plain Z does nothing: the Ctrl is part of the shortcut
        act(() -> PlaceScenes.tap(io.github.profetgit.cyanotype.interaction.Keys.UNDO));
        waitTicks(4);
        act(() -> check("undo/a plain Z does nothing", house.origin.getX() == 4, at(house)));
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.UNDO, false);
        act(() -> check("undo/Ctrl+Z puts it back", house.origin.getX() == 0, at(house)));
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.REDO, false);
        act(() -> check("undo/Ctrl+Y does the move again", house.origin.getX() == 4, at(house)));
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.UNDO, false);
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.UNDO, true);
        act(() -> check("undo/Ctrl+Shift+Z redoes too", house.origin.getX() == 4, at(house)));
        // a new change ends the redo history
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.UNDO, false);
        act(() -> DevCommands.run("/cyanotype move 0 " + (G + 1) + " 9"));
        waitTicks(3);
        act(() -> check("undo/a new change ends redo", !Placements.canRedo() && house.origin.getZ() == 9, "can redo " + Placements.canRedo() + ", " + at(house)));
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.UNDO, false);

        // a removal can be undone and redone
        act(() -> Minecraft.getInstance().gui.setScreen(new io.github.profetgit.cyanotype.ui.RemoveScreen(house)));
        waitTicks(4);
        act(() -> key(com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT));
        act(() -> key(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN));
        waitTicks(6);
        act(() -> check("undo/removed", Placements.all().isEmpty(), "placements " + Placements.all().size()));
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.UNDO, false);
        waitTicks(6);
        act(() -> check("undo/Ctrl+Z brings the removed placement back, active and unlocked state kept", Placements.all().contains(house) && Placements.active() == house && house.locked, "placements " + Placements.all().size()));
        until("undo/its ghost is drawn again and counted", 400, () -> GhostRenderer.verifierOf(house) != null && GhostRenderer.verifierOf(house).settled() && GhostRenderer.drawn(house));
        shot("undo_restored");
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.REDO, false);
        act(() -> check("undo/Ctrl+Y removes it again", Placements.all().isEmpty(), "placements " + Placements.all().size()));
        PlaceScenes.ctrlTap(io.github.profetgit.cyanotype.interaction.Keys.UNDO, false);
        waitTicks(6);

        // the Placed screen has buttons for them, greyed out when there is nothing to do
        act(() -> DevCommands.run("/cyanotype move 8 " + (G + 1) + " 6"));
        waitTicks(4);
        act(() -> Minecraft.getInstance().gui.setScreen(new io.github.profetgit.cyanotype.ui.PlacedScreen()));
        waitTicks(8);
        shot("undo_placed");
        act(() -> click(((io.github.profetgit.cyanotype.ui.PlacedScreen) screen()).anchor("undo")));
        waitTicks(6);
        act(() -> check("undo/the Placed screen's Undo takes the last change back", screen() instanceof io.github.profetgit.cyanotype.ui.PlacedScreen && Placements.canRedo() && house.origin.getX() != 8, "screen " + screen() + ", can redo " + Placements.canRedo() + ", " + at(house)));
        act(() -> click(((io.github.profetgit.cyanotype.ui.PlacedScreen) screen()).anchor("redo")));
        waitTicks(6);
        act(() -> check("undo/its Redo does it again", !Placements.canRedo() && house.origin.getX() == 8, "can redo " + Placements.canRedo() + ", " + at(house)));
        act(() -> Minecraft.getInstance().gui.setScreen(null));
        waitTicks(4);
        teardown();
    }

    /** Removing a placement: the wheel's Remove tool, the question, Keep, and Remove with the keyboard. */
    static void remove() {
        setup();
        // the Delete key: on the ghost under the crosshair, or on the one being edited, and never while hidden
        act(() -> PlaceScenes.tap(io.github.profetgit.cyanotype.interaction.Keys.REMOVE));
        waitTicks(6);
        act(() -> check("remove/Delete on the aimed ghost asks first", screen() instanceof io.github.profetgit.cyanotype.ui.RemoveScreen rs && rs.asks().get(0) == house, String.valueOf(screen())));
        act(() -> key(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE));
        waitTicks(4);
        act(() -> check("remove/Esc keeps it", screen() == null && Placements.all().contains(house), "screen " + screen()));
        act(() -> PlaceScenes.tap(io.github.profetgit.cyanotype.interaction.Keys.MAIN));
        waitTicks(6);
        act(() -> check("remove/editing: the hints say Delete removes", Placements.mode() == Placements.Mode.EDIT, "mode " + Placements.mode()));
        shot("remove_key_chips");
        act(() -> PlaceScenes.tap(io.github.profetgit.cyanotype.interaction.Keys.REMOVE));
        waitTicks(6);
        act(() -> check("remove/Delete while editing asks about the edited placement", screen() instanceof io.github.profetgit.cyanotype.ui.RemoveScreen rs && rs.asks().get(0) == house, String.valueOf(screen())));
        act(() -> key(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE));
        waitTicks(4);
        act(() -> PlaceScenes.tap(io.github.profetgit.cyanotype.interaction.Keys.TOGGLE));
        waitTicks(4);
        act(() -> PlaceScenes.tap(io.github.profetgit.cyanotype.interaction.Keys.REMOVE));
        waitTicks(4);
        act(() -> check("remove/Delete with the ghosts hidden does nothing", screen() == null && Placements.all().contains(house), "screen " + screen()));
        act(() -> PlaceScenes.tap(io.github.profetgit.cyanotype.interaction.Keys.TOGGLE));
        waitTicks(3);
        act(() -> PlaceScenes.tap(io.github.profetgit.cyanotype.interaction.Keys.MAIN));
        waitTicks(4);
        act(() -> {
            io.github.profetgit.cyanotype.interaction.CellHighlight.show(GhostRenderer.verifierOf(house), java.util.List.of(new int[]{0, 1}), "k", "something", -1, -1);
        });
        waitTicks(6);
        act(() -> check("remove/a highlight is showing before", io.github.profetgit.cyanotype.interaction.CellHighlight.shown() > 0, "cells " + io.github.profetgit.cyanotype.interaction.CellHighlight.shown()));
        act(() -> Minecraft.getInstance().gui.setScreen(new io.github.profetgit.cyanotype.ui.PlacedScreen()));
        waitTicks(8);
        shot("remove_0_placed");
        act(() -> click(((io.github.profetgit.cyanotype.ui.PlacedScreen) screen()).anchor("trash:0")));
        waitTicks(8);
        act(() -> check("remove/the trash button in the Placed list opens the question for the placement", screen() instanceof io.github.profetgit.cyanotype.ui.RemoveScreen rs && rs.asks().get(0) == house, String.valueOf(screen())));
        waitTicks(6);
        shot("remove_1_confirm");
        // Keep it: by mouse, nothing changes
        act(() -> click(((io.github.profetgit.cyanotype.ui.RemoveScreen) screen()).anchor("keep")));
        waitTicks(4);
        act(() -> check("remove/Keep it leaves the placement alone", screen() == null && Placements.all().contains(house), "screen " + screen() + ", placements " + Placements.all().size()));
        // Enter with the first focus is Keep too
        act(() -> Minecraft.getInstance().gui.setScreen(new io.github.profetgit.cyanotype.ui.RemoveScreen(house)));
        waitTicks(4);
        act(() -> key(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN));
        waitTicks(3);
        act(() -> check("remove/Enter on the first focus keeps it", screen() == null && Placements.all().contains(house), "screen " + screen()));
        // arrow right moves to Remove, Enter removes
        act(() -> Minecraft.getInstance().gui.setScreen(new io.github.profetgit.cyanotype.ui.RemoveScreen(house)));
        waitTicks(8);
        act(() -> key(com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT));
        waitTicks(8);
        shot("remove_2_focus_remove");
        act(() -> key(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN));
        waitTicks(6);
        act(() -> check("remove/Remove takes the placement away", screen() == null && Placements.all().isEmpty() && Placements.mode() == Placements.Mode.IDLE, "screen " + screen() + ", placements " + Placements.all().size()));
        act(() -> check("remove/its material highlight goes with it", io.github.profetgit.cyanotype.interaction.CellHighlight.shown() == 0, "cells " + io.github.profetgit.cyanotype.interaction.CellHighlight.shown()));
        waitTicks(10);
        act(() -> check("remove/its ghost is gone", GhostRenderer.ghosts().isEmpty(), "ghosts " + GhostRenderer.ghosts().size()));
        // with nothing placed the list says so
        act(() -> Minecraft.getInstance().gui.setScreen(new io.github.profetgit.cyanotype.ui.PlacedScreen()));
        waitTicks(8);
        shot("remove_3_nothing_placed");
        act(() -> check("remove/with nothing placed the Placed list is empty", Placements.all().isEmpty() && screen() instanceof io.github.profetgit.cyanotype.ui.PlacedScreen, "screen " + screen()));
        act(() -> Minecraft.getInstance().gui.setScreen(null));
        waitTicks(4);
        teardown();
    }

    static void wheel() {
        setup();
        // a short press of the tool key is a tap: it starts editing and no wheel opens
        act(() -> Interaction.testMainDown = true);
        waitTicks(2);
        act(() -> Interaction.testMainDown = false);
        waitTicks(3);
        act(() -> check("wheel/a tap edits, no wheel", screen() == null && Placements.mode() == Placements.Mode.EDIT, "mode " + Placements.mode() + ", screen " + screen()));
        act(() -> Placements.setMode(Placements.Mode.IDLE));

        // held long enough, the wheel opens
        act(() -> {
            WheelScreen.testHeld = true;
            Interaction.testMainDown = true;
        });
        waitTicks(8);
        act(() -> check("wheel/a hold opens the wheel", screen() instanceof WheelScreen, String.valueOf(screen())));
        waitTicks(6);
        shot("wheel_0_open");
        for (int seg : new int[]{0, 2, 4, 6}) {
            int s = seg;
            act(() -> Ui.testMouse = segmentPoint(s));
            waitTicks(8);
            shot("wheel_seg_" + seg);
        }
        // let go on Layers (segment 2): the wheel closes and the Layers tool starts
        act(() -> Ui.testMouse = segmentPoint(2));
        waitTicks(6);
        act(() -> {
            WheelScreen.testHeld = false;
            Interaction.testMainDown = false;
        });
        waitTicks(12);
        act(() -> check("wheel/letting go on Layers starts the layer tool", screen() == null && Placements.mode() == Placements.Mode.LAYERS && house.layered(), "mode " + Placements.mode() + ", layers " + house.layerLo + "-" + house.layerHi + ", screen " + screen()));
        shot("wheel_layers");
        // scroll moves the layer window
        act(() -> PlaceScenes.scroll(Minecraft.getInstance(), 1));
        waitTicks(4);
        act(() -> PlaceScenes.scroll(Minecraft.getInstance(), 1));
        waitTicks(4);
        act(() -> check("wheel/scroll moves the layer", house.layerLo == 2 && house.layerHi == 2, "layers " + house.layerLo + "-" + house.layerHi));
        act(() -> {
            Interaction.testModifiers = 1;
            PlaceScenes.scroll(Minecraft.getInstance(), 2);
            Interaction.testModifiers = -1;
        });
        waitTicks(4);
        act(() -> check("wheel/shift+scroll thickens it", house.layerLo == 2 && house.layerHi == 4, "layers " + house.layerLo + "-" + house.layerHi));
        shot("wheel_layers_scrolled");
        // a click ends it, keeping the layers; right click shows everything again
        act(() -> PlaceScenes.hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> PlaceScenes.release(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> check("wheel/a click ends the layer tool and keeps the layers", Placements.mode() == Placements.Mode.IDLE && house.layered(), "mode " + Placements.mode()));
        act(() -> Interaction.startLayers(Minecraft.getInstance()));
        act(() -> PlaceScenes.hold(Minecraft.getInstance().options.keyUse));
        waitTicks(3);
        act(() -> PlaceScenes.release(Minecraft.getInstance().options.keyUse));
        waitTicks(3);
        act(() -> check("wheel/right click shows all layers", Placements.mode() == Placements.Mode.IDLE && !house.layered(), "layers " + house.layerLo + "-" + house.layerHi));

        // Build has nothing to work on when nothing is placed: choosing it does nothing
        act(() -> {
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
        });
        waitTicks(6);
        act(() -> {
            WheelScreen.testHeld = true;
            Interaction.testMainDown = true;
        });
        waitTicks(8);
        act(() -> Ui.testMouse = segmentPoint(io.github.profetgit.cyanotype.ui.Tool.BUILD.ordinal()));
        waitTicks(6);
        shot("wheel_build_disabled");
        act(() -> {
            WheelScreen.testHeld = false;
            Interaction.testMainDown = false;
        });
        waitTicks(8);
        act(() -> check("wheel/an unavailable tool closes the wheel and does nothing", screen() == null && Placements.mode() == Placements.Mode.IDLE, "screen " + screen() + ", mode " + Placements.mode()));
        act(() -> {
            var mc = Minecraft.getInstance();
            for (var t : io.github.profetgit.cyanotype.ui.Tool.values()) {
                boolean needsBuild = t == io.github.profetgit.cyanotype.ui.Tool.EDIT || t == io.github.profetgit.cyanotype.ui.Tool.LAYERS || t == io.github.profetgit.cyanotype.ui.Tool.BUILD || t == io.github.profetgit.cyanotype.ui.Tool.MATERIALS;
                check("wheel/with nothing placed " + t.label + (needsBuild ? " says why it cannot be used" : " is available"), needsBuild ? !t.why(mc).isEmpty() : t.why(mc).isEmpty(), "'" + t.why(mc) + "'");
            }
            check("wheel/the reason points to the Library", io.github.profetgit.cyanotype.ui.Tool.LAYERS.why(mc).contains("Library"), io.github.profetgit.cyanotype.ui.Tool.LAYERS.why(mc));
        });
        // a blueprint that is still being placed (not put down) is not a build to work on yet
        act(() -> {
            DevCommands.run("/cyanotype sample");
            DevCommands.run("/cyanotype place 0 " + (G + 1) + " 6");
        });
        waitTicks(10);
        act(() -> {
            var mc = Minecraft.getInstance();
            boolean placing = Placements.mode() == Placements.Mode.PLACING;
            check("wheel/while a blueprint is still being placed the build tools say to put it down", placing ? io.github.profetgit.cyanotype.ui.Tool.MATERIALS.why(mc).contains("put the blueprint down") : true, "mode " + Placements.mode() + ", '" + io.github.profetgit.cyanotype.ui.Tool.MATERIALS.why(mc) + "'");
        });
        act(() -> {
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            Placements.setMode(Placements.Mode.IDLE);
        });
        waitTicks(6);

        // letting go in the middle closes it without choosing
        act(() -> {
            WheelScreen.testHeld = true;
            Interaction.testMainDown = true;
            Ui.testMouse = null;
        });
        waitTicks(8);
        act(() -> Ui.testMouse = new int[]{screen().width / 2, screen().height / 2});
        waitTicks(4);
        act(() -> {
            WheelScreen.testHeld = false;
            Interaction.testMainDown = false;
        });
        waitTicks(8);
        act(() -> check("wheel/letting go in the middle chooses nothing", screen() == null && Placements.mode() == Placements.Mode.IDLE, "screen " + screen() + ", mode " + Placements.mode()));
        teardown();
    }
}
