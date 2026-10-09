package io.github.profetgit.buildbuddy.interaction;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.buildbuddy.BuildBuddy;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The few keys the mod binds, all rebindable in Controls. V is the Build Buddy key: it will hold the tool wheel (M4); until
 * then it starts and ends editing. B was the plan, but it is Traveler's Lantern's key in the author's profile.
 * Added to the options by OptionsMixin (no loader API needed).
 */
public final class Keys {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BuildBuddy.MOD_ID, BuildBuddy.MOD_ID));

    public static final KeyMapping MAIN = new KeyMapping("key.buildbuddy.main", InputConstants.KEY_V, CATEGORY);
    public static final KeyMapping TOGGLE = new KeyMapping("key.buildbuddy.toggle", InputConstants.KEY_H, CATEGORY);
    public static final KeyMapping UNDO = new KeyMapping("key.buildbuddy.undo", InputConstants.KEY_Z, CATEGORY);
    public static final KeyMapping REDO = new KeyMapping("key.buildbuddy.redo", InputConstants.KEY_Y, CATEGORY);
    public static final KeyMapping MIRROR = new KeyMapping("key.buildbuddy.mirror", InputConstants.KEY_M, CATEGORY);
    public static final KeyMapping PASTE = new KeyMapping("key.buildbuddy.paste", InputConstants.KEY_P, CATEGORY);
    public static final KeyMapping REMOVE = new KeyMapping("key.buildbuddy.remove", InputConstants.KEY_DELETE, CATEGORY);
    public static final KeyMapping PICK = new KeyMapping("key.buildbuddy.pick", InputConstants.KEY_U, CATEGORY);
    public static final KeyMapping HELP = new KeyMapping("key.buildbuddy.help", InputConstants.KEY_J, CATEGORY);
    public static final KeyMapping AUTO = new KeyMapping("key.buildbuddy.auto", InputConstants.KEY_N, CATEGORY);
    public static final KeyMapping LAYER_UP = new KeyMapping("key.buildbuddy.layer_up", InputConstants.KEY_PAGEUP, CATEGORY);
    public static final KeyMapping LAYER_DOWN = new KeyMapping("key.buildbuddy.layer_down", InputConstants.KEY_PAGEDOWN, CATEGORY);

    private Keys() {
    }
}
