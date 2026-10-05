package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;

import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.Motion;
import io.github.profetgit.cyanotype.ui.Settings;
import io.github.profetgit.cyanotype.ui.SettingsScreen;
import io.github.profetgit.cyanotype.ui.Ui;
import java.io.IOException;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** The Settings screen in the real client, driven through the screen's own mouse handlers. */
final class SettingsScenes {
    private SettingsScenes() {
    }

    private static SettingsScreen screen() {
        return (SettingsScreen) Minecraft.getInstance().gui.screen();
    }

    private static MouseButtonEvent at(int[] p) {
        return new MouseButtonEvent(p[0], p[1], new MouseButtonInfo(0, 0));
    }

    private static void click(int[] p) {
        Ui.testMouse = p;
        screen().mouseClicked(at(p), false);
        screen().mouseReleased(at(p));
    }

    private static void clickId(String id) {
        int[] p = screen().anchor(id);
        if (p == null) {
            check("settings/control " + id + " is on screen", false, "no anchor in group " + screen().groupName());
            return;
        }
        click(p);
    }

    /** Presses on a slider at one share of its range, drags to another, lets go. */
    private static void drag(String id, double from, double to) {
        int[] a = screen().sliderPoint(id, from), b = screen().sliderPoint(id, to);
        Ui.testMouse = a;
        screen().mouseClicked(at(a), false);
        int steps = 6;
        for (int i = 1; i <= steps; i++) {
            int[] p = {a[0] + (b[0] - a[0]) * i / steps, a[1]};
            Ui.testMouse = p;
            screen().mouseDragged(at(p), (b[0] - a[0]) / (double) steps, 0);
        }
        screen().mouseReleased(at(b));
    }

    static void settings() {
        Director.clean();
        act(() -> {
            Settings.reset();
            Director.hideHud(Minecraft.getInstance(), false);
            Minecraft.getInstance().gui.setScreen(new SettingsScreen());
        });
        Director.camera(5.5, Director.G + 5, -14, 0, 14);
        waitTicks(14);
        act(() -> check("settings/opens on Appearance", Minecraft.getInstance().gui.screen() instanceof SettingsScreen s && s.groupName().equals("APPEARANCE"), String.valueOf(Minecraft.getInstance().gui.screen())));
        shot("settings_0_appearance");

        // toggles
        act(() -> clickId("chips"));
        waitTicks(6);
        act(() -> check("settings/a click switches cursor hints off", !Settings.get().chips, "chips " + Settings.get().chips));
        shot("settings_1_chips_off");
        act(() -> clickId("chips"));
        act(() -> clickId("reduceMotion"));
        waitTicks(2);
        act(() -> check("settings/reduce motion takes effect at once", Settings.get().reduceMotion && Motion.reduced() && Settings.get().chips, "reduce " + Settings.get().reduceMotion));
        act(() -> clickId("reduceMotion"));

        // sliders: press, drag, let go
        act(() -> drag("sounds", 0.2, 0.5));
        act(() -> check("settings/volume slider drags to the middle", Math.abs(Settings.get().sounds - 0.5f) < 0.011f, "sounds " + Settings.get().sounds));
        act(() -> drag("wheelHold", 0.5, 0.0));
        act(() -> check("settings/wheel hold time drags to its lowest (100 ms)", Settings.get().wheelHoldMs == 100, "ms " + Settings.get().wheelHoldMs));
        act(() -> drag("wheelHold", 0.0, 0.2));
        act(() -> check("settings/wheel hold snaps to steps of 25", Settings.get().wheelHoldMs == 200, "ms " + Settings.get().wheelHoldMs));
        act(() -> {
            Ui.testMouse = screen().sliderPoint("sounds", 0.5);
        });
        waitTicks(8);
        shot("settings_2_sliders");
        act(() -> drag("sounds", 0.5, 1.4));
        act(() -> check("settings/dragging past the end stops at the maximum", Settings.get().sounds == 1.0f, "sounds " + Settings.get().sounds));
        act(() -> drag("sounds", 1.0, -0.5));
        act(() -> check("settings/volume at 0 is Off", Settings.get().sounds == 0f, "sounds " + Settings.get().sounds));
        waitTicks(4);
        shot("settings_3_sound_off");
        act(() -> drag("sounds", 0.0, 1.0));

        // Ghost
        act(() -> clickId("tab:GHOST"));
        waitTicks(4);
        act(() -> check("settings/the Ghost tab opens", screen().groupName().equals("GHOST"), screen().groupName()));
        act(() -> drag("opacity", 0.8, 20 / 95.0));
        act(() -> check("settings/opacity slider sets the default (25 %)", Math.abs(Settings.get().opacity - 0.25f) < 0.011f, "opacity " + Settings.get().opacity));
        act(() -> drag("range", 0.5, 1.0));
        act(() -> check("settings/draw distance reaches the renderer", Settings.get().range == 512 && GhostRenderer.range == 512, "range " + Settings.get().range + ", renderer " + GhostRenderer.range));
        act(() -> {
            clickId("fade");
            clickId("verify");
        });
        waitTicks(6);
        act(() -> check("settings/fade and check switches reach the renderer", !GhostRenderer.fadeEnabled && !GhostRenderer.verifyEnabled, "fade " + GhostRenderer.fadeEnabled + ", verify " + GhostRenderer.verifyEnabled));
        shot("settings_4_ghost");
        act(() -> {
            clickId("fade");
            clickId("verify");
        });
        act(() -> check("settings/switching them back works", GhostRenderer.fadeEnabled && GhostRenderer.verifyEnabled, "fade " + GhostRenderer.fadeEnabled + ", verify " + GhostRenderer.verifyEnabled));

        // the other groups
        act(() -> clickId("tab:MATERIALS"));
        act(() -> clickId("groupVariants"));
        waitTicks(6);
        act(() -> check("settings/group variants switches on", Settings.get().groupVariants, "groupVariants " + Settings.get().groupVariants));
        shot("settings_5_materials");
        act(() -> clickId("tab:AUTO"));
        waitTicks(6);
        shot("settings_6_auto");
        act(() -> clickId("tab:SERVERS"));
        waitTicks(6);
        shot("settings_7_servers");

        // Advanced: names, then reset (two clicks)
        act(() -> clickId("tab:ADVANCED"));
        act(() -> clickId("showNames"));
        waitTicks(6);
        act(() -> check("settings/names over ghosts switch off", !Settings.get().showNames, "showNames " + Settings.get().showNames));
        act(() -> clickId("reset"));
        waitTicks(6);
        act(() -> check("settings/one click on Reset only asks", Math.abs(Settings.get().opacity - 0.25f) < 0.011f && !Settings.get().showNames, "opacity " + Settings.get().opacity));
        shot("settings_8_reset_asks");
        act(() -> clickId("reset"));
        waitTicks(6);
        act(() -> check("settings/the second click puts everything back", Settings.get().opacity == 0.6f && Settings.get().showNames && Settings.get().range == 192 && Settings.get().sounds == 1.0f && !Settings.get().groupVariants,
            "opacity " + Settings.get().opacity + ", range " + Settings.get().range + ", names " + Settings.get().showNames));
        shot("settings_9_after_reset");

        // the default opacity is what a new placement starts with
        act(() -> {
            clickId("tab:GHOST");
            drag("opacity", 0.5, 25 / 95.0);
        });
        act(() -> {
            clickId("done");
        });
        waitTicks(6);
        act(() -> check("settings/Done closes the screen", Minecraft.getInstance().gui.screen() == null, String.valueOf(Minecraft.getInstance().gui.screen())));
        act(() -> {
            Interaction.startPlacing("opacity test", Samples.house(), "cyanotype:none.litematic");
            Placement p = Placements.active();
            check("settings/a new placement starts at the default opacity", p != null && Math.abs(p.opacity - Settings.get().opacity) < 0.001f && Math.abs(p.opacity - 0.3f) < 0.011f, p == null ? "none" : "opacity " + p.opacity);
            if (p != null) Placements.remove(p);
        });
        waitTicks(4);

        // the smallest window Minecraft allows (a 320 x 240 GUI): the last rows start out of reach and scrolling brings them in
        act(() -> {
            SettingsScreen small = new SettingsScreen();
            Minecraft.getInstance().gui.setScreen(small);
            small.resize(320, 240);
        });
        waitTicks(14);
        act(() -> check("settings/a small window leaves the last slider out of reach", !screen().reachable("slider:wheelHold"), "reachable " + screen().reachable("slider:wheelHold")));
        shot("settings_10_small");
        act(() -> screen().mouseScrolled(100, 100, 0, -10));
        waitTicks(30);
        act(() -> check("settings/scrolling brings it into reach", screen().reachable("slider:wheelHold"), screen().scrollState()));
        shot("settings_11_small_scrolled");
        act(() -> Minecraft.getInstance().gui.setScreen(null));
        waitTicks(6);

        // closing the screen writes the file at once; reading it back gives the same value
        act(() -> {
            try {
                float saved = com.google.gson.JsonParser.parseString(Files.readString(Settings.file())).getAsJsonObject().get("opacity").getAsFloat();
                check("settings/closing writes the file", saved == Settings.get().opacity, "file " + saved + ", memory " + Settings.get().opacity);
            } catch (IOException e) {
                check("settings/closing writes the file", false, e.toString());
            }
            float before = Settings.get().opacity;
            Settings.get().opacity = 0.9f;
            Settings.load();
            check("settings/the file is read back", Settings.get().opacity == before, "opacity " + Settings.get().opacity + " vs " + before);
            Settings.reset();
            Settings.flush();
            Director.hideHud(Minecraft.getInstance(), true);
            Ui.testMouse = null;
        });
        waitTicks(4);
    }
}
