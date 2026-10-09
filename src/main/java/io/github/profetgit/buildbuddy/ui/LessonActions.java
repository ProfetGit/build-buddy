package io.github.profetgit.buildbuddy.ui;

import io.github.profetgit.buildbuddy.interaction.Picking;
import io.github.profetgit.buildbuddy.interaction.Selecting;
import io.github.profetgit.buildbuddy.paste.Paste;
import net.minecraft.client.Minecraft;

/**
 * What a lesson's Try it does: it starts the tool the lesson is about, or says why it cannot yet. A lesson file names an action
 * ("edit", "pick"...) and only the names here do anything, so a lesson can never run anything else.
 */
public final class LessonActions {
    private LessonActions() {
    }

    /** Whether the game knows this action. */
    public static boolean known(String action) {
        return switch (action) {
            case "none", "library", "place", "edit", "layers", "build", "auto", "materials", "pick", "box", "paste" -> true;
            default -> false;
        };
    }

    /** Why Try it cannot start the tool now, in words for the player, or empty when it can. */
    public static String why(Minecraft mc, String action) {
        return switch (action) {
            case "edit" -> Tool.EDIT.why(mc);
            case "layers" -> Tool.LAYERS.why(mc);
            case "build", "auto" -> Tool.BUILD.why(mc);
            case "materials" -> Tool.MATERIALS.why(mc);
            case "paste" -> {
                String u = Paste.unavailable(mc);
                yield u.isEmpty() ? Tool.needBuild() : u;
            }
            default -> "";
        };
    }

    /** Starts the tool. */
    public static Runnable of(Minecraft mc, String action) {
        return switch (action) {
            case "library", "place" -> () -> Tool.LIBRARY.run(mc);
            case "edit" -> () -> Tool.EDIT.run(mc);
            case "layers" -> () -> Tool.LAYERS.run(mc);
            case "build", "auto" -> () -> Tool.BUILD.run(mc);
            case "materials" -> () -> Tool.MATERIALS.run(mc);
            case "pick" -> () -> Picking.start(mc);
            case "box" -> () -> Selecting.start(mc);
            case "paste" -> () -> Tool.askPaste(mc);
            default -> () -> {
            };
        };
    }

    /** The pixel icon that stands for the lesson in the Help list. */
    public static String icon(String action) {
        return switch (action) {
            case "library", "place" -> "folder";
            case "edit" -> "move";
            case "layers" -> "layers";
            case "build" -> "hammer";
            case "auto" -> "sweep";
            case "materials" -> "list";
            case "pick" -> "wand";
            case "box" -> "select";
            case "paste" -> "paste";
            default -> "help";
        };
    }
}
