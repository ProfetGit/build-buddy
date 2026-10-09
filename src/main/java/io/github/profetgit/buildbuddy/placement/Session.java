package io.github.profetgit.buildbuddy.placement;

import io.github.profetgit.buildbuddy.interaction.Interaction;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Follows which world the player is in: placements belong to a singleplayer save or a server, so joining one loads its
 * file and leaving it writes the file and forgets the placements. Changing dimension inside a world keeps them (each
 * placement knows its own dimension).
 */
public final class Session {
    private static ClientLevel level;
    private static String key;

    private Session() {
    }

    public static void tick(Minecraft mc) {
        ClientLevel now = mc.level;
        if (now != level) {
            level = now;
            String newKey = now == null ? null : PlacementStore.worldKey(mc);
            if (!Objects.equals(newKey, key)) {
                if (key != null) PlacementStore.flush(true);
                PlacementStore.detach();
                Placements.clear();
                Interaction.reset();
                key = newKey;
                if (key != null) PlacementStore.load(key);
            }
        }
    }

    /** The key of the world whose placements are loaded, or null (dev checks). */
    public static String key() {
        return key;
    }
}
