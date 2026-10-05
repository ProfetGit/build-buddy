package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.auto.AutoBuilder;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Picking;
import io.github.profetgit.cyanotype.interaction.Selecting;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import net.minecraft.client.Minecraft;

/**
 * The six tools of the wheel, clockwise from the top (PRD 7.9): the ones a builder reaches for all the time. What is used
 * less often lives next to what it belongs to: Settings and the list of placed blueprints in the Library, Undo and Redo on
 * Ctrl+Z and Ctrl+Y (and in the placed list), Show/hide on H, Remove on Delete, Next block and Auto-place under Build, Smart
 * Pick and the box under Save.
 */
public enum Tool {
    LIBRARY("Library", "folder", "Pick a blueprint to place") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(new LibraryScreen());
        }
    },
    EDIT("Edit", "move", "Move, turn and flip the placement") {
        @Override
        void run(Minecraft mc) {
            Interaction.enterEdit(mc);
        }
    },
    LAYERS("Layers", "layers", "Show only some layers") {
        @Override
        void run(Minecraft mc) {
            Interaction.startLayers(mc);
        }
    },
    BUILD("Build", "hammer", "Help with building: next block, auto-place") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(buildScreen(mc));
        }

        @Override
        boolean enabled(Minecraft mc) {
            return locked() != null || AutoBuilder.on() || Interaction.guide;
        }
    },
    MATERIALS("Materials", "list", "What is still needed") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(new MaterialsScreen());
        }
    },
    SAVE("Save", "save", "Save a build of yours as a blueprint") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(saveScreen());
        }
    };

    public final String label, icon, hint;

    Tool(String label, String icon, String hint) {
        this.label = label;
        this.icon = icon;
        this.hint = hint;
    }

    abstract void run(Minecraft mc);

    /** Tools that cannot be used right now are drawn dim and answer with an error sound. */
    boolean enabled(Minecraft mc) {
        return true;
    }

    static Placement locked() {
        Placement p = Placements.active();
        return p != null && p.locked && p.ready() && GhostRenderer.verifierOf(p) != null ? p : null;
    }

    /** What Build offers: auto-placing off, Assist or Sweep, and the next-block marker. */
    static ChoiceScreen buildScreen(Minecraft mc) {
        boolean ready = locked() != null;
        String need = ready ? "" : "Place a blueprint first.  ";
        java.util.List<ChoiceScreen.Choice> choices = java.util.List.of(
            new ChoiceScreen.Choice("cube", "Build it myself", "Nothing is placed for you.", true, !AutoBuilder.on(), () -> AutoBuilder.request(mc, AutoBuilder.Mode.OFF)),
            new ChoiceScreen.Choice("hammer", "Place what I look at", "Hold use on a ghost block and it goes down at once. Nothing else can be placed.", ready, AutoBuilder.mode() == AutoBuilder.Mode.ASSIST,
                () -> AutoBuilder.request(mc, AutoBuilder.Mode.ASSIST)),
            new ChoiceScreen.Choice("sweep", "Place everything in reach", "Builds what is near you, lowest layer first, while you walk.", ready, AutoBuilder.mode() == AutoBuilder.Mode.SWEEP,
                () -> AutoBuilder.request(mc, AutoBuilder.Mode.SWEEP)));
        ChoiceScreen.Toggle next = new ChoiceScreen.Toggle("Mark the next block to build", "A marker and an arrow show where to build next.", () -> Interaction.guide, v -> {
            Interaction.reveal();
            Interaction.guide = v;
            Interaction.say(mc, v ? "Showing the next block to build." : "Next-block marker off.");
        });
        return new ChoiceScreen("Build", need + "It stops when you are hurt or open a screen.", choices, ready ? next : null);
    }

    /** What Save offers: pick a whole build with one click, or select a box. */
    static ChoiceScreen saveScreen() {
        Minecraft mc = Minecraft.getInstance();
        java.util.List<ChoiceScreen.Choice> choices = java.util.List.of(
            new ChoiceScreen.Choice("wand", "Pick a build", "Click any part of a build and the whole of it is picked.", true, false, () -> Picking.start(mc)),
            new ChoiceScreen.Choice("select", "Select a box", "Click two corners, then drag the sides to fit.", true, false, () -> Selecting.start(mc)));
        return new ChoiceScreen("Save a build", "Either way you name it next and it goes into your Library.", choices, null);
    }
}
