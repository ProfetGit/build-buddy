package io.github.profetgit.buildbuddy.demo;

import static io.github.profetgit.buildbuddy.demo.Director.G;
import static io.github.profetgit.buildbuddy.demo.Director.act;
import static io.github.profetgit.buildbuddy.demo.Director.camera;
import static io.github.profetgit.buildbuddy.demo.Director.check;
import static io.github.profetgit.buildbuddy.demo.Director.cmd;
import static io.github.profetgit.buildbuddy.demo.Director.until;
import static io.github.profetgit.buildbuddy.demo.Director.waitTicks;
import static io.github.profetgit.buildbuddy.demo.PlaceScenes.tap;
import static io.github.profetgit.buildbuddy.demo.SaveScenes.mc;

import io.github.profetgit.buildbuddy.auto.AutoBuilder;
import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.interaction.Keys;
import io.github.profetgit.buildbuddy.placement.Orientation;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;

/**
 * The keys that need no tool: the auto-placing toggle (on in Assist, off, back on in the mode used last) and Page Up and
 * Page Down moving the layer window of the active build, driven through the key mappings. With a screen open or the ghosts
 * hidden they do nothing.
 */
final class KeyScenes {
    private KeyScenes() {
    }

    private static Placement house;

    private static String layers() {
        return house.layerLo + ".." + house.layerHi;
    }

    private static boolean window(int lo, int hi) {
        return house.layerLo == lo && house.layerHi == hi;
    }

    private static void press(net.minecraft.client.KeyMapping k) {
        act(() -> tap(k));
        waitTicks(3);
    }

    static void keys() {
        Director.clean();
        cmd("fill -1 " + (G + 1) + " 5 4 " + (G + 8) + " 10 air", "gamemode creative Builder");
        waitTicks(10);
        // nothing placed yet: the layer keys say so and change nothing, the toggle still works
        press(Keys.LAYER_UP);
        press(Keys.LAYER_DOWN);
        act(() -> check("keys/with nothing placed the layer keys do nothing", Placements.active() == null, "no placement"));

        act(() -> {
            house = Director.locked(new Placement("tower", Samples.uniform(3, 4, 3), "buildbuddy:keys.litematic", Director.DIM, new BlockPos(0, G + 1, 6), Orientation.NONE));
            Placements.add(house);
        });
        camera(1.5, G + 7, 0, 0, 30);
        cmd("fill 0 " + (G + 1) + " 6 2 " + (G + 1) + " 8 stone_bricks");
        until("keys/compared with the world", 400, () -> GhostRenderer.verifierOf(house) != null && GhostRenderer.verifierOf(house).settled());

        // ---- the auto-placing toggle
        act(() -> check("keys/auto-placing starts off", AutoBuilder.mode() == AutoBuilder.Mode.OFF, AutoBuilder.mode().toString()));
        press(Keys.AUTO);
        act(() -> check("keys/the toggle turns Assist on first", AutoBuilder.mode() == AutoBuilder.Mode.ASSIST, AutoBuilder.mode().toString()));
        press(Keys.AUTO);
        act(() -> check("keys/the toggle turns it off", AutoBuilder.mode() == AutoBuilder.Mode.OFF, AutoBuilder.mode().toString()));
        press(Keys.AUTO);
        act(() -> check("keys/the toggle turns Assist on again", AutoBuilder.mode() == AutoBuilder.Mode.ASSIST, AutoBuilder.mode().toString()));
        act(() -> AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP));
        waitTicks(3);
        act(() -> check("keys/Sweep is on", AutoBuilder.mode() == AutoBuilder.Mode.SWEEP, AutoBuilder.mode().toString()));
        press(Keys.AUTO);
        act(() -> check("keys/the toggle turns Sweep off", AutoBuilder.mode() == AutoBuilder.Mode.OFF, AutoBuilder.mode().toString()));
        press(Keys.AUTO);
        act(() -> check("keys/the toggle brings back the mode used last (Sweep)", AutoBuilder.mode() == AutoBuilder.Mode.SWEEP, AutoBuilder.mode().toString()));
        press(Keys.AUTO);
        act(() -> {
            check("keys/off again", AutoBuilder.mode() == AutoBuilder.Mode.OFF, AutoBuilder.mode().toString());
            mc().gui.setScreen(new PauseScreen(false));
        });
        waitTicks(3);
        press(Keys.AUTO);
        act(() -> {
            check("keys/the toggle does nothing with a screen open", AutoBuilder.mode() == AutoBuilder.Mode.OFF, AutoBuilder.mode().toString());
            mc().gui.setScreen(null);
        });
        waitTicks(3);
        press(Keys.TOGGLE);
        act(() -> check("keys/the ghosts are hidden", GhostRenderer.hidden, "hidden=" + GhostRenderer.hidden));
        press(Keys.AUTO);
        act(() -> check("keys/the toggle does nothing with the ghosts hidden", AutoBuilder.mode() == AutoBuilder.Mode.OFF, AutoBuilder.mode().toString()));
        press(Keys.TOGGLE);
        act(() -> check("keys/the ghosts are back", !GhostRenderer.hidden, "hidden=" + GhostRenderer.hidden));

        // ---- Page Up and Page Down: layer 1 (of 4) is finished, so the first press lands on layer 2
        act(() -> check("keys/all layers to begin with", window(-1, -1), layers()));
        press(Keys.LAYER_DOWN);
        act(() -> check("keys/Page Down from all layers starts at the lowest unfinished layer", window(1, 1), layers()));
        press(Keys.LAYER_UP);
        act(() -> check("keys/Page Up moves one layer up", window(2, 2), layers()));
        press(Keys.LAYER_UP);
        act(() -> check("keys/Page Up again reaches the top layer", window(3, 3), layers()));
        press(Keys.LAYER_UP);
        act(() -> check("keys/Page Up at the top shows all layers", window(-1, -1), layers()));
        press(Keys.LAYER_UP);
        act(() -> check("keys/Page Up from all layers also starts at the lowest unfinished layer", window(1, 1), layers()));
        press(Keys.LAYER_DOWN);
        act(() -> check("keys/Page Down moves one layer down", window(0, 0), layers()));
        press(Keys.LAYER_DOWN);
        act(() -> check("keys/Page Down stops at the lowest layer", window(0, 0), layers()));
        press(Keys.LAYER_UP);
        act(() -> check("keys/Page Up goes up again", window(1, 1), layers()));
        until("keys/the ghost follows the window", 300, () -> GhostRenderer.settled());

        // inert
        act(() -> mc().gui.setScreen(new PauseScreen(false)));
        waitTicks(3);
        press(Keys.LAYER_UP);
        press(Keys.LAYER_DOWN);
        act(() -> {
            check("keys/the layer keys do nothing with a screen open", window(1, 1), layers());
            mc().gui.setScreen(null);
        });
        waitTicks(3);
        press(Keys.TOGGLE);
        press(Keys.LAYER_UP);
        act(() -> check("keys/the layer keys do nothing with the ghosts hidden", window(1, 1), layers()));
        press(Keys.TOGGLE);
        press(Keys.LAYER_UP);
        act(() -> check("keys/and work again when they are back", window(2, 2), layers()));

        act(() -> {
            house.layerLo = house.layerHi = -1;
            Placements.remove(house);
        });
        cmd("fill -1 " + (G + 1) + " 5 4 " + (G + 8) + " 10 air", "gamemode spectator Builder");
        waitTicks(4);
    }
}
