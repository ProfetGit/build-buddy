package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.auto.AutoBuilder;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Picking;
import io.github.profetgit.cyanotype.interaction.Selecting;
import io.github.profetgit.cyanotype.paste.Paste;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ponder.Lessons;
import net.minecraft.client.Minecraft;

/**
 * The six tools of the wheel, clockwise from the top (PRD 7.9): the ones a builder reaches for all the time. What is used
 * less often lives next to what it belongs to: Settings and the list of placed blueprints in the Library, Undo and Redo on
 * Ctrl+Z and Ctrl+Y (and in the placed list), Show/hide on H, Remove on Delete, Next block and Auto-place under Build, Smart
 * Pick and the box under Save.
 */
public enum Tool {
    LIBRARY("Library", "folder", "Pick a blueprint to place", "place") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(new LibraryScreen());
        }
    },
    EDIT("Edit", "move", "Move, turn and mirror the placement", "edit") {
        @Override
        void run(Minecraft mc) {
            Lessons.firstUse(mc, "edit", () -> Interaction.enterEdit(mc));
        }

        @Override
        public String why(Minecraft mc) {
            // any placed blueprint can be edited, aimed at or the active one
            boolean any = false, loading = false;
            for (Placement p : Placements.all()) {
                if (p.locked && p.ready()) return "";
                any = true;
                if (p.locked) loading = true;
            }
            if (!any) return "Nothing placed yet. Open the Library and place a blueprint.";
            return loading ? "The blueprint is still loading." : "Click to put the blueprint down first.";
        }
    },
    LAYERS("Layers", "layers", "Show only some layers", "layers") {
        @Override
        void run(Minecraft mc) {
            Lessons.firstUse(mc, "layers", () -> Interaction.startLayers(mc));
        }

        @Override
        public String why(Minecraft mc) {
            return needBuild();
        }
    },
    BUILD("Build", "hammer", "Help with building: next block, auto-place", "build") {
        @Override
        void run(Minecraft mc) {
            Lessons.firstUse(mc, "build", () -> mc.gui.setScreen(buildScreen(mc)));
        }

        @Override
        public String why(Minecraft mc) {
            // already helping: the panel stays reachable, to turn it off
            return AutoBuilder.on() || Interaction.guide ? "" : needBuild();
        }
    },
    MATERIALS("Materials", "list", "What is still needed", "materials") {
        @Override
        void run(Minecraft mc) {
            Lessons.firstUse(mc, "materials", () -> mc.gui.setScreen(new MaterialsScreen()));
        }

        @Override
        public String why(Minecraft mc) {
            return needBuild();
        }
    },
    SAVE("Save", "save", "Save a build of yours as a blueprint", "pick") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(saveScreen());
        }
    };

    /** What the wheel shows, and the id of the lesson (How it works) for the tool. */
    public final String label, icon, hint, lesson;

    Tool(String label, String icon, String hint, String lesson) {
        this.label = label;
        this.icon = icon;
        this.hint = hint;
        this.lesson = lesson;
    }

    abstract void run(Minecraft mc);

    /**
     * Why the tool cannot be used right now, in words for the player, or empty when it can. A tool that needs the build you are
     * working on (Layers, Materials, Build) waits for one: a blueprint that is placed, put down and checked against the world.
     * Library and Save need nothing; Edit needs any placed blueprint to move.
     */
    public String why(Minecraft mc) {
        return "";
    }

    /** Tools that cannot be used right now are drawn dim, the reason shows under the wheel, and choosing one answers with an error sound and the reason. */
    boolean enabled(Minecraft mc) {
        return why(mc).isEmpty();
    }

    /** What the tools that work on the active build need, in order: something placed, a build picked, put down, loaded, checked. */
    static String needBuild() {
        Placement p = Placements.active();
        if (Placements.all().isEmpty()) return "Place a blueprint first: open the Library.";
        if (p == null) return "Pick the build you work on: aim at it and tap " + Ui.keyName(io.github.profetgit.cyanotype.interaction.Keys.MAIN) + ".";
        if (!p.locked) return "Click to put the blueprint down first.";
        if (!p.ready()) return "The blueprint is still loading.";
        if (GhostRenderer.verifierOf(p) == null) return "Still checking the world.";
        return "";
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
            new ChoiceScreen.Choice("cube", "Build it myself", "Nothing is placed for you.", true, !AutoBuilder.on(), () -> AutoBuilder.request(mc, AutoBuilder.Mode.OFF), "build"),
            new ChoiceScreen.Choice("hammer", "Place what I look at", "Hold use on a ghost block and it goes down at once. Nothing else can be placed.", ready, AutoBuilder.mode() == AutoBuilder.Mode.ASSIST,
                () -> Lessons.firstUse(mc, "auto", () -> AutoBuilder.request(mc, AutoBuilder.Mode.ASSIST)), "auto"),
            new ChoiceScreen.Choice("sweep", "Place everything in reach", "Builds what is near you, lowest layer first, while you walk.", ready, AutoBuilder.mode() == AutoBuilder.Mode.SWEEP,
                () -> Lessons.firstUse(mc, "auto", () -> AutoBuilder.request(mc, AutoBuilder.Mode.SWEEP)), "auto"),
            pasteChoice(mc, ready));
        ChoiceScreen.Toggle next = new ChoiceScreen.Toggle("Mark the next block to build", "A marker and an arrow show where to build next.", () -> Interaction.guide, v -> {
            Interaction.reveal();
            Interaction.guide = v;
            Interaction.say(mc, v ? "Showing the next block to build." : "Next-block marker off.");
        });
        return new ChoiceScreen("Build", need + "It stops when you are hurt or open a menu (chat only pauses it).", choices, ready ? next : null);
    }

    /** The Build panel's creative row: paste the whole build into the world; dim, with the reason, when it cannot be done. */
    private static ChoiceScreen.Choice pasteChoice(Minecraft mc, boolean ready) {
        String why = Paste.unavailable(mc);
        boolean ok = ready && why.isEmpty() && Paste.ready(Placements.active()) && !Paste.busy();
        String desc = !why.isEmpty() ? why : !ready ? "Place a blueprint first." : Paste.busy() ? "A paste is running." : "Creative: puts the whole build in the world at once. Ctrl+Z undoes it.";
        return new ChoiceScreen.Choice("paste", "Paste it into the world", desc, ok, false, () -> Lessons.firstUse(mc, "paste", () -> askPaste(mc)), "paste");
    }

    /** Asks before pasting the active build into the world: how many blocks go in, how many that are in the way are replaced. Esc or Not now keeps everything. */
    public static void askPaste(Minecraft mc) {
        Placement p = locked();
        String why = Paste.unavailable(mc);
        if (!why.isEmpty()) {
            Interaction.say(mc, why);
            Sfx.play(Sfx.ERROR);
            return;
        }
        if (p == null || !Paste.ready(p)) {
            Interaction.say(mc, p == null ? "Place a blueprint first." : "Still checking the world. Try again in a moment.");
            Sfx.play(Sfx.ERROR);
            return;
        }
        long[] n = Paste.preview(p);
        String sizes = String.format(java.util.Locale.ROOT, "%,d", n[0]) + " blocks go in" + (n[1] > 0 ? ", replacing " + String.format(java.util.Locale.ROOT, "%,d", n[1]) + " that are in the way" : "")
            + (n[2] > 0 ? " (" + String.format(java.util.Locale.ROOT, "%,d", n[2]) + " are already right)" : "") + ". Ctrl+Z undoes it.";
        java.util.List<ChoiceScreen.Choice> choices = java.util.List.of(
            new ChoiceScreen.Choice("cross", "Not now", "Nothing changes.", true, false, () -> { }),
            new ChoiceScreen.Choice("paste", "Paste " + p.name, sizes, n[0] + n[1] > 0, false, () -> Paste.start(mc, p)));
        mc.gui.setScreen(new ChoiceScreen("Paste it into the world?", "It goes where the ghost is, in this world only.", choices, null));
    }

    /** What Save offers: pick a whole build with one click, or select a box. */
    static ChoiceScreen saveScreen() {
        Minecraft mc = Minecraft.getInstance();
        java.util.List<ChoiceScreen.Choice> choices = java.util.List.of(
            new ChoiceScreen.Choice("wand", "Pick a build", "Click a build and a box fits itself round it. Drag the sides to change it.", true, false, () -> Lessons.firstUse(mc, "pick", () -> Picking.start(mc)), "pick"),
            new ChoiceScreen.Choice("select", "Select a box", "Click two corners, then drag the sides until it holds your build.", true, false, () -> Lessons.firstUse(mc, "box", () -> Selecting.start(mc)), "box"));
        return new ChoiceScreen("Save a build", "Either way you name it next and it goes into your Library.", choices, null);
    }
}
