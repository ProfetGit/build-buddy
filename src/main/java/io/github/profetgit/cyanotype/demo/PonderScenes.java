package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;

import io.github.profetgit.cyanotype.ponder.Lessons;
import io.github.profetgit.cyanotype.ponder.PonderScreen;
import io.github.profetgit.cyanotype.ponder.Scene;
import io.github.profetgit.cyanotype.ui.Settings;
import io.github.profetgit.cyanotype.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** The lessons in the real client: every one opened as a screen, drawn through the real textures and shaders of the game, and shot at set times. */
final class PonderScenes {
    private PonderScenes() {
    }

    static Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    static PonderScreen ponder() {
        return screen() instanceof PonderScreen p ? p : null;
    }

    private static void click(int[] at) {
        Screen s = screen();
        var info = new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0);
        s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info), false);
        s.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info));
    }

    private static void key(int code) {
        screen().keyPressed(new net.minecraft.client.input.KeyEvent(code, 0, 0));
    }

    /** Opens each lesson (the ids in the property, or all of them), shoots stills every few seconds, and checks the picture is made. */
    static void stills() {
        String which = System.getProperty("cyanotype.demo.ponder", "all");
        double step = Double.parseDouble(System.getProperty("cyanotype.demo.ponder.step", "2.0"));
        act(() -> Settings.get().reduceMotion = false);
        List<String> ids = which.equals("all") ? Lessons.ids() : List.of(which.split(","));
        act(() -> check("ponder/lessons listed", !ids.isEmpty(), ids.toString()));
        for (String id : ids) lesson(id, step);
    }

    private static void lesson(String id, double step) {
        Scene sc = Lessons.scene(id);
        act(() -> {
            check("ponder/" + id + ": the lesson loads", sc != null, String.valueOf(sc));
            if (sc != null) Lessons.open(Minecraft.getInstance(), id);
        });
        if (sc == null) return;
        waitTicks(4);
        act(() -> {
            PonderScreen p = ponder();
            check("ponder/" + id + ": its screen is open", p != null, String.valueOf(screen()));
            if (p != null) p.player().pause();
        });
        for (double t = 0; t < sc.duration - 0.05; t += step) still(id, t);
        still(id, sc.duration - 0.05);
        act(() -> {
            PonderScreen p = ponder();
            if (p != null) {
                check("ponder/" + id + ": pictures were made", p.view().picturesMade() > 0, p.view().picturesMade() + " pictures, " + String.format(java.util.Locale.ROOT, "%.1f", p.view().averageMs()) + " ms each");
            }
            Minecraft.getInstance().gui.setScreen(null);
        });
        waitTicks(3);
    }

    private static void still(String id, double t) {
        act(() -> {
            PonderScreen p = ponder();
            if (p != null) p.player().seek(t);
        });
        until("ponder/" + id + ": picture at " + String.format(java.util.Locale.ROOT, "%.1f", t), 400, () -> {
            PonderScreen p = ponder();
            return p != null && p.view().current() && p.view().shownSnapshot() != null && Math.abs(p.view().shownSnapshot().t - t) < 0.02;
        });
        waitTicks(2);
        shot("ponder_" + id + "_" + String.format(java.util.Locale.ROOT, "%04.1f", t).replace('.', '_'));
    }
}
