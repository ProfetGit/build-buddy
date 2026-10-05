package io.github.profetgit.cyanotype.interaction;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.cyanotype.Cyanotype;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The few keys the mod binds, all rebindable in Controls. V is the Cyanotype key: it will hold the tool wheel (M4); until
 * then it starts and ends editing. B was the plan, but it is Traveler's Lantern's key in the author's profile.
 * Added to the options by OptionsMixin (no loader API needed).
 */
public final class Keys {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, Cyanotype.MOD_ID));

    public static final KeyMapping MAIN = new KeyMapping("key.cyanotype.main", InputConstants.KEY_V, CATEGORY);
    public static final KeyMapping TOGGLE = new KeyMapping("key.cyanotype.toggle", InputConstants.KEY_H, CATEGORY);
    public static final KeyMapping UNDO = new KeyMapping("key.cyanotype.undo", InputConstants.KEY_Z, CATEGORY);
    public static final KeyMapping REMOVE = new KeyMapping("key.cyanotype.remove", InputConstants.KEY_DELETE, CATEGORY);

    private Keys() {
    }
}
