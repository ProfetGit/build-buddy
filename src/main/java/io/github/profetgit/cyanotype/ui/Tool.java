package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
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
            Interaction.guide = !Interaction.guide;
            Sfx.play(Interaction.guide ? Sfx.OPEN : Sfx.CLOSE);
            Interaction.say(mc, Interaction.guide ? "Showing the next block to build." : "Next-block guide off.");
        }

        @Override
        boolean enabled(Minecraft mc) {
            return locked() != null;
        }
    },
    MATERIALS("Materials", "list", "What is still needed") {
        @Override
        void run(Minecraft mc) {
            mc.gui.setScreen(new MaterialsScreen());
        }
    },
    SAVE("Save area", "save", "Coming with the next update") {
        @Override
        void run(Minecraft mc) {
            Sfx.play(Sfx.ERROR);
            Interaction.say(mc, "Saving your own builds comes in a later version.");
        }

        @Override
        boolean enabled(Minecraft mc) {
            return false;
        }
    },
    SETTINGS("Settings", "select", "Look, feel and defaults") {
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
