package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.DIM;
import static io.github.profetgit.cyanotype.demo.Director.G;
import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.camera;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.CellHighlight;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.Materials;
import io.github.profetgit.cyanotype.ui.MaterialsScreen;
import io.github.profetgit.cyanotype.ui.Ui;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The Materials screen in the real client: the list for a placed house, a layer, grouped variants, the empty states, the highlight in the world. */
final class MaterialsScenes {
    private MaterialsScenes() {
    }

    static MaterialsScreen screen() {
        return Minecraft.getInstance().gui.screen() instanceof MaterialsScreen s ? s : null;
    }

    static int cells(Blueprint bp, Block block) {
        int n = 0;
        for (Region r : bp.regions) {
            for (short id : r.blocks) if (r.palette[id & 0xFFFF].state().getBlock() == block) n++;
        }
        return n;
    }

    static Materials.Row row(String key) {
        return screen().rows().stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
    }

    static void click(int[] at) {
        MaterialsScreen s = screen();
        MouseButtonInfo info = new MouseButtonInfo(0, 0);
        s.mouseClicked(new MouseButtonEvent(at[0], at[1], info), false);
        s.mouseReleased(new MouseButtonEvent(at[0], at[1], info));
    }

    static void give(String... commands) {
        Director.add(mc -> {
            String me = mc.player.getGameProfile().name();
            String[] full = new String[commands.length];
            for (int i = 0; i < full.length; i++) full[i] = commands[i].replace("@me", me);
            Director.run(mc, full);
            return true;
        });
    }

    /** Chat lines from the setup commands would sit in every still. */
    static void snap(String name) {
        act(() -> Minecraft.getInstance().gui.hud.getChat().clearMessages(false));
        waitTicks(2);
        shot(name);
    }

    static void materials() {
        Director.clean();
        act(() -> {
            io.github.profetgit.cyanotype.ui.Settings.get().groupVariants = false;
        });
        // the first pickup of each item pops a recipe or advancement toast: let those pass before anything is filmed
        give("clear @me", "give @me minecraft:oak_planks 1", "give @me minecraft:cobblestone 1", "give @me minecraft:glass_pane 1", "give @me minecraft:oak_log 1",
            "give @me minecraft:spruce_planks 1", "give @me minecraft:stone_bricks 1", "give @me minecraft:oak_slab 1", "give @me minecraft:oak_stairs 1");
        waitTicks(140);
        Blueprint houseBp = Samples.house();
        // 1. nothing placed: the empty state, with a way forward
        act(() -> {
            CellHighlight.clear();
            Director.hideHud(Minecraft.getInstance(), false);
            Minecraft.getInstance().gui.setScreen(new MaterialsScreen());
        });
        waitTicks(14);
        act(() -> check("materials/no placement: an empty state, no rows", screen() != null && screen().rows().isEmpty(), "rows " + (screen() == null ? "no screen" : screen().rows().size())));
        snap("materials_0_empty");
        act(() -> Minecraft.getInstance().gui.setScreen(null));

        // 2. placed, not locked yet: asks to lock it
        act(() -> {
            Placements.add(new Placement("Sample house", Samples.house(), "cyanotype:materials-house.litematic", DIM, new BlockPos(0, G + 1, 6), Orientation.NONE));
            Minecraft.getInstance().gui.setScreen(new MaterialsScreen());
        });
        waitTicks(14);
        act(() -> check("materials/not locked: no rows", screen() != null && screen().rows().isEmpty(), "rows " + screen().rows().size()));
        snap("materials_1_not_locked");
        act(() -> {
            Minecraft.getInstance().gui.setScreen(null);
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
        });
        waitTicks(4);

        // 3. the house, locked in an empty world, with a few things in the inventory
        UiScenes.setup();
        give("clear @me", "give @me minecraft:oak_planks 200", "give @me minecraft:cobblestone 30", "give @me minecraft:glass_pane 5", "give @me minecraft:oak_log 64");
        waitTicks(14);
        act(() -> Minecraft.getInstance().gui.setScreen(new MaterialsScreen()));
        waitTicks(14);
        act(() -> {
            MaterialsScreen s = screen();
            var v = GhostRenderer.verifierOf(UiScenes.house);
            int todo = v.counts().todo();
            Materials.Result r = s.result();
            // the house has one door: its top half is not an item of its own
            check("materials/every block to place is accounted for", r.blocks() + r.noItem() + 1 == todo, r.blocks() + " in rows + " + r.noItem() + " without item + 1 door top = " + (r.blocks() + r.noItem() + 1) + ", verifier says " + todo);
            Materials.Row planks = row("oak_planks"), cobble = row("cobblestone"), stairs = row("oak_stairs"), door = row("oak_door");
            check("materials/oak planks counted from the blueprint", planks != null && planks.needed() == cells(houseBp, Blocks.OAK_PLANKS), planks == null ? "none" : planks.needed() + " vs " + cells(houseBp, Blocks.OAK_PLANKS));
            check("materials/cobblestone counted from the blueprint", cobble != null && cobble.needed() == cells(houseBp, Blocks.COBBLESTONE), cobble == null ? "none" : cobble.needed() + " vs " + cells(houseBp, Blocks.COBBLESTONE));
            check("materials/stairs counted from the blueprint", stairs != null && stairs.needed() == cells(houseBp, Blocks.OAK_STAIRS), stairs == null ? "none" : stairs.needed() + " vs " + cells(houseBp, Blocks.OAK_STAIRS));
            check("materials/a door is one item, not two halves", door != null && door.needed() == 1, door == null ? "none" : String.valueOf(door.needed()));
            check("materials/the inventory is subtracted", cobble != null && cobble.have() == 30 && cobble.missing() == cobble.needed() - 30, cobble == null ? "none" : "have " + cobble.have() + ", missing " + cobble.missing());
            check("materials/enough planks is no shortage", planks != null && planks.have() == 200 && planks.missing() == Math.max(0, planks.needed() - 200), planks == null ? "none" : "have " + planks.have() + ", missing " + planks.missing());
            boolean sorted = true;
            for (int i = 1; i < s.rows().size(); i++) if (s.rows().get(i - 1).missing() < s.rows().get(i).missing()) sorted = false;
            check("materials/biggest shortage first", sorted, s.rows().get(0).name() + " " + s.rows().get(0).missing());
            check("materials/stack format", cobble != null && cobble.missingText().startsWith("1 stack + "), cobble == null ? "none" : cobble.missingText());
        });
        snap("materials_2_list");
        act(() -> Ui.testMouse = screen().rowCenter(2));
        waitTicks(10);
        snap("materials_3_hover");
        // a click on a row lights up where it goes
        act(() -> {
            Ui.testMouse = null;
            click(screen().rowCenter(2));
        });
        waitTicks(6);
        act(() -> check("materials/clicking a row highlights its cells", CellHighlight.showing(screen().rows().get(2).key()) && CellHighlight.shown() > 0, "cells " + CellHighlight.shown() + ", row " + screen().rows().get(2).name()));
        snap("materials_4_selected");
        act(() -> Minecraft.getInstance().gui.setScreen(null));
        camera(5.5, G + 6, -16, 0, 12);
        waitTicks(20);
        snap("materials_5_world");
        act(() -> {
            Minecraft.getInstance().gui.setScreen(new MaterialsScreen());
        });
        waitTicks(10);
        act(() -> click(screen().rowCenter(2)));
        waitTicks(4);
        act(() -> check("materials/the same row again clears it", CellHighlight.shown() == 0, "cells " + CellHighlight.shown()));

        // layers: the floor is cobblestone only
        act(() -> screen().layers(0, 0));
        waitTicks(10);
        act(() -> {
            Materials.Row cobble = row("cobblestone");
            check("materials/layer 1 is the cobblestone floor", screen().rows().size() == 1 && cobble != null && cobble.needed() == 11 * 13, screen().rows().size() + " rows, " + (cobble == null ? "none" : cobble.needed()));
        });
        snap("materials_6_layer");
        act(() -> screen().layers(1, 4));
        waitTicks(10);
        act(() -> check("materials/layers 2-5 are the walls, no floor", row("cobblestone") == null && row("oak_planks") != null, screen().rows().size() + " rows"));
        act(() -> screen().layers(-1, -1));
        waitTicks(6);

        // the shopping list
        act(() -> {
            String text = screen().shoppingText();
            check("materials/the shopping list names what is missing", text.startsWith("Shopping list: " + UiScenes.house.name) && text.contains("Cobblestone") && !text.contains("Oak Planks"), text.replace("\n", " / "));
        });
        act(() -> {
            try {
                Path file = FabricLoader.getInstance().getConfigDir().resolve("cyanotype").resolve("shopping").resolve(UiScenes.house.name.replaceAll("[^A-Za-z0-9 _.-]", "_").trim() + ".txt");
                Files.deleteIfExists(file);
                screen().saveList();
                check("materials/the list is saved as a text file", Files.isRegularFile(file) && Files.readString(file).contains("Shopping list"), file.toString());
            } catch (java.io.IOException e) {
                check("materials/the list is saved as a text file", false, e.toString());
            }
        });
        waitTicks(4);
        snap("materials_7_saved");
        act(() -> {
            Minecraft.getInstance().gui.setScreen(null);
            CellHighlight.clear();
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
        });
        waitTicks(4);

        // 4. wood variants grouped: the tower has spruce and dark oak planks
        act(() -> {
            Placement t = Director.locked(new Placement("Watch tower", Samples.tower(), "cyanotype:materials-tower.litematic", DIM, new BlockPos(0, G + 1, 6), Orientation.NONE));
            Placements.add(t);
            UiScenes.house = t;
        });
        camera(5.5, G + 6, -16, 0, 12);
        until("materials/tower baked", 400, () -> GhostRenderer.verifierOf(UiScenes.house) != null && GhostRenderer.verifierOf(UiScenes.house).settled());
        give("clear @me", "give @me minecraft:spruce_planks 20", "give @me minecraft:stone_bricks 64");
        waitTicks(14);
        act(() -> {
            io.github.profetgit.cyanotype.ui.Settings.get().groupVariants = false;
            Minecraft.getInstance().gui.setScreen(new MaterialsScreen());
        });
        waitTicks(12);
        act(() -> check("materials/ungrouped lists each wood", row("spruce_planks") != null && row("dark_oak_planks") != null, screen().rows().size() + " rows"));
        snap("materials_8_ungrouped");
        act(() -> screen().group(true));
        waitTicks(12);
        act(() -> {
            Materials.Row g = screen().rows().stream().filter(Materials.Row::group).findFirst().orElse(null);
            check("materials/grouped: one line for both planks, what you hold covers it", g != null && g.name().equals("Planks (any wood)") && g.needed() == 90 && g.have() == 20 && g.missing() == 70, g == null ? "no group row" : g.name() + " need " + g.needed() + " have " + g.have() + " missing " + g.missing());
        });
        snap("materials_9_grouped");
        act(() -> {
            io.github.profetgit.cyanotype.ui.Settings.get().groupVariants = false;
            Minecraft.getInstance().gui.setScreen(null);
            CellHighlight.clear();
        });
        UiScenes.teardown();

        // 5. everything built: nothing left to build
        act(() -> {
            Director.hideHud(Minecraft.getInstance(), false);
            Placement p = Director.locked(new Placement("Little step", Samples.uniform(3, 1, 3), "cyanotype:materials-step.litematic", DIM, new BlockPos(0, G + 1, 6), Orientation.NONE));
            Placements.add(p);
            UiScenes.house = p;
        });
        waitTicks(8);
        Director.cmd("fill 0 " + (G + 1) + " 6 2 " + (G + 1) + " 8 minecraft:stone_bricks");
        until("materials/step baked and built", 400, () -> GhostRenderer.verifierOf(UiScenes.house) != null && GhostRenderer.verifierOf(UiScenes.house).settled() && GhostRenderer.verifierOf(UiScenes.house).counts().todo() == 0);
        act(() -> Minecraft.getInstance().gui.setScreen(new MaterialsScreen()));
        waitTicks(12);
        act(() -> check("materials/a finished build needs nothing", screen().rows().isEmpty() && screen().result().blocks() == 0, "rows " + screen().rows().size()));
        snap("materials_10_done");
        act(() -> {
            Minecraft.getInstance().gui.setScreen(null);
            Director.hideHud(Minecraft.getInstance(), true);
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
        });
        Director.cmd("fill 0 " + (G + 1) + " 6 2 " + (G + 1) + " 8 minecraft:air");
        waitTicks(4);
    }
}
