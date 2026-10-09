package io.github.profetgit.buildbuddy.ui;

import io.github.profetgit.buildbuddy.interaction.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The first-run hello: once, a few seconds after the player first stands in a world with the mod, a small card says which
 * key opens everything. It leaves by itself, or at once when the player presses the tool key or the lessons key.
 */
public final class Welcome {
    private static final double DELAY_S = 3, SHOWN_S = 16;
    private static double waited, shown;
    private static long lastNs;

    private Welcome() {
    }

    /** Dev demo: the card is on screen. */
    public static boolean showing() {
        return !Settings.get().welcomed && waited >= DELAY_S && shown > 0.5 && shown < SHOWN_S;
    }

    /** Dev demo: starts the first-run clock over. */
    public static void restart() {
        waited = 0;
        shown = 0;
        lastNs = 0;
    }

    public static void draw(Minecraft mc, GuiGraphicsExtractor g) {
        if (Settings.get().welcomed) return;
        long now = System.nanoTime();
        double dt = lastNs == 0 ? 0 : Math.min(0.1, (now - lastNs) / 1e9);
        lastNs = now;
        if (mc.gui.screen() != null) return;
        if (Keys.MAIN.isDown() || Keys.HELP.isDown()) {
            finish();
            return;
        }
        if (waited < DELAY_S) {
            waited += dt;
            return;
        }
        shown += dt;
        if (shown > SHOWN_S + 1) {
            finish();
            return;
        }
        float a = Motion.follow("hud#welcome", shown < SHOWN_S ? 1f : 0f, 0.12);
        if (a < 0.02f) return;
        String hold = Ui.keyName(Keys.MAIN), help = Ui.keyName(Keys.HELP);
        int w = 214, h = 56, x = (g.guiWidth() - w) / 2, y = 22;
        float inner = Ui.panelOpening(g, x, y, w, h, Motion.reduced() ? 1 : Math.min(1.0, a));
        if (inner < 0.05f) return;
        Ui.centered(g, "Build Buddy is ready", g.guiWidth() / 2, y + 7, Ui.withAlpha(Ui.CYAN, inner));
        Ui.centered(g, "Hold " + hold + " for the tool wheel", g.guiWidth() / 2, y + 22, Ui.withAlpha(Ui.LINE, inner));
        Ui.centered(g, "Press " + help + " for lessons on every tool", g.guiWidth() / 2, y + 34, Ui.withAlpha(Ui.DIM, inner));
    }

    private static void finish() {
        Settings.get().welcomed = true;
        Settings.changed();
    }
}
