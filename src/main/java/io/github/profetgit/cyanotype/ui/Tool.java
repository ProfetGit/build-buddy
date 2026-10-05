package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.auto.AutoBuilder;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Picking;
import io.github.profetgit.cyanotype.interaction.Selecting;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import net.minecraft.client.Minecraft;

/** The eight tools of the wheel, clockwise from the top (PRD 7.9). */
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
    NEXT("Next block", "arrow", "Mark the block to build next") {
        @Override
        void run(Minecraft mc) {
            Interaction.reveal();
            Interaction.guide = !Interaction.guide;
            Sfx.play(Interaction.guide ? Sfx.OPEN : Sfx.CLOSE);
            Interaction.say(mc, Interaction.guide ? "Showing the next block to build." : "Next-block guide off.");
        }

        @Override
        boolean enabled(Minecraft mc) {
            return locked() != null;
        }
    },
    AUTO("Auto-place", "hammer", "Place blocks for you from your inventory") {
        @Override
        void run(Minecraft mc) {
            AutoBuilder.cycle(mc);
        }

        @Override
        boolean enabled(Minecraft mc) {
            return locked() != null || AutoBuilder.on();
        }
    },
    MATERIALS("Materials", "list", "What is still needed") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(new MaterialsScreen());
        }
    },
    SAVE("Save area", "save", "Pick a box of your build and save it") {
        @Override
        void run(Minecraft mc) {
            Selecting.start(mc);
        }
    },
    PICK("Smart pick", "wand", "Click a build to pick all of it") {
        @Override
        void run(Minecraft mc) {
            Picking.start(mc);
        }
    },
    SETTINGS("Settings", "gear", "Look, feel and defaults") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(new SettingsScreen());
        }
    },
    GHOSTS("Show / hide", "eye", "Hide or show the ghosts") {
        @Override
        void run(Minecraft mc) {
            Interaction.toggleGhosts(mc);
        }
    },
    REMOVE("Remove", "trash", "Take a placed blueprint away") {
        @Override
        void run(Minecraft mc) {
            Placement p = Interaction.aimedPlacement(mc);
            if (p == null) p = Placements.active();
            if (p == null) {
                Sfx.play(Sfx.ERROR);
                return;
            }
            mc.gui.setScreen(new RemoveScreen(p));
        }

        @Override
        boolean enabled(Minecraft mc) {
            return !Placements.all().isEmpty();
        }
    },
    UNDO("Undo", "undo", "Take back the last change (Ctrl+Z)") {
        @Override
        void run(Minecraft mc) {
            Interaction.undo(mc);
        }

        @Override
        boolean enabled(Minecraft mc) {
            return Placements.canUndo();
        }
    },
    REDO("Redo", "redo", "Do the undone change again (Ctrl+Y)") {
        @Override
        void run(Minecraft mc) {
            Interaction.redo(mc);
        }

        @Override
        boolean enabled(Minecraft mc) {
            return Placements.canRedo();
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
}
