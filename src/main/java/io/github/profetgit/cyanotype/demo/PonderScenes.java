package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ponder.HelpScreen;
import io.github.profetgit.cyanotype.ponder.Lessons;
import io.github.profetgit.cyanotype.ponder.PonderScreen;
import io.github.profetgit.cyanotype.ponder.Scene;
import io.github.profetgit.cyanotype.ui.Settings;
import io.github.profetgit.cyanotype.ui.Ui;
import io.github.profetgit.cyanotype.ui.WheelScreen;
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
        click(at, InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static void click(int[] at, int button) {
        Screen s = screen();
        var info = new net.minecraft.client.input.MouseButtonInfo(button, 0);
        s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info), false);
        s.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info));
    }

    private static void key(int code) {
        screen().keyPressed(new net.minecraft.client.input.KeyEvent(code, 0, 0));
    }

    private static void cleanup() {
        Minecraft mc = Minecraft.getInstance();
        Ui.testMouse = null;
        WheelScreen.testHeld = null;
        Interaction.testMainDown = null;
        mc.gui.setScreen(null);
    }

    /** Holds the tool key so that the wheel opens, and points at a segment. */
    private static void wheelOn(int segment) {
        act(() -> {
            WheelScreen.testHeld = true;
            Interaction.testMainDown = true;
        });
        waitTicks(8);
        act(() -> check("ponder/the wheel opened", screen() instanceof WheelScreen, String.valueOf(screen())));
        act(() -> Ui.testMouse = UiScenes.segmentPoint(segment));
        waitTicks(6);
    }

    /** What the open lesson screen costs: frame times of the world alone, then with each of a light and a heavy lesson playing. */
    static void perf() {
        act(() -> Settings.get().reduceMotion = false);
        waitTicks(20);
        Director.measure("ponder/baseline, no screen");
        for (String id : new String[]{"place", "build", "edit"}) {
            act(() -> Lessons.open(Minecraft.getInstance(), id));
            waitTicks(30);
            Director.measure("ponder/" + id + " playing");
            act(() -> {
                PonderScreen p = ponder();
                if (p != null) check("ponder/" + id + ": pictures were made while it played", p.view().picturesMade() > 20, p.view().picturesMade() + " pictures, " + String.format(java.util.Locale.ROOT, "%.1f", p.view().averageMs()) + " ms each on the worker");
                Minecraft.getInstance().gui.setScreen(null);
            });
            waitTicks(10);
        }
    }

    /** The flow: first use plays the lesson once, ? shows it again, Try it and Skip start the tool, Close does not, and the Help list searches. */
    static void flow() {
        UiScenes.setup();
        act(() -> {
            Settings.get().reduceMotion = false;
            Settings.get().lessons = true;
            Lessons.resetSeen();
        });
        // first use: Layers is chosen on the wheel (segment 2): its lesson plays before the tool starts, Skip starts it
        wheelOn(2);
        act(() -> {
            WheelScreen.testHeld = false;
            Interaction.testMainDown = false;
        });
        waitTicks(14);
        act(() -> {
            PonderScreen p = ponder();
            check("ponder/first use: the Layers lesson plays before the tool", p != null && p.scene().id.equals("layers") && Placements.mode() != Placements.Mode.LAYERS, screen() + ", mode " + Placements.mode());
            check("ponder/first use: it is marked seen at once", Lessons.seen("layers"), "seen " + Settings.get().lessonsSeen);
            check("ponder/first use: it opens playing", p != null && p.player().playing() && p.player().time() < 3, "time " + (p == null ? -1 : p.player().time()));
        });
        waitTicks(30);
        act(() -> {
            PonderScreen p = ponder();
            check("ponder/the lesson plays on its own (time moves)", p != null && p.player().time() > 0.4, "time " + (p == null ? -1 : p.player().time()));
        });
        shot("ponder_flow_firstuse");
        act(() -> click(ponder().anchor("skip")));
        waitTicks(6);
        act(() -> check("ponder/first use: Skip starts the tool", screen() == null && Placements.mode() == Placements.Mode.LAYERS, screen() + ", mode " + Placements.mode()));
        act(() -> Placements.setMode(Placements.Mode.IDLE));
        // the second time: no lesson, the tool just starts
        wheelOn(2);
        act(() -> {
            WheelScreen.testHeld = false;
            Interaction.testMainDown = false;
        });
        waitTicks(14);
        act(() -> check("ponder/the second time there is no lesson", screen() == null && Placements.mode() == Placements.Mode.LAYERS, screen() + ", mode " + Placements.mode()));
        act(() -> Placements.setMode(Placements.Mode.IDLE));

        // lessons off: nothing opens, even for a tool not seen yet
        act(() -> {
            Settings.get().lessons = false;
            Lessons.resetSeen();
        });
        wheelOn(2);
        act(() -> {
            WheelScreen.testHeld = false;
            Interaction.testMainDown = false;
        });
        waitTicks(14);
        act(() -> check("ponder/with lessons switched off the tool just starts", screen() == null && Placements.mode() == Placements.Mode.LAYERS, screen() + ", mode " + Placements.mode()));
        act(() -> {
            Placements.setMode(Placements.Mode.IDLE);
            Settings.get().lessons = true;
        });

        // ? : a right click on a tool of the wheel shows its lesson again; Try it starts the tool, Close does not
        wheelOn(1);
        shot("ponder_flow_wheel");
        act(() -> click(UiScenes.segmentPoint(1), InputConstants.MOUSE_BUTTON_RIGHT));
        waitTicks(8);
        act(() -> {
            PonderScreen p = ponder();
            check("ponder/? : a right click on Edit shows the Edit lesson", p != null && p.scene().id.equals("edit"), screen() + "");
            WheelScreen.testHeld = null;
            Interaction.testMainDown = null;
        });
        act(() -> click(ponder().anchor("skip")));
        waitTicks(6);
        act(() -> check("ponder/a replayed lesson's Close starts nothing", screen() == null && Placements.mode() == Placements.Mode.IDLE, screen() + ", mode " + Placements.mode()));
        act(() -> Lessons.open(Minecraft.getInstance(), "edit"));
        waitTicks(8);
        act(() -> click(ponder().anchor("try")));
        waitTicks(6);
        act(() -> check("ponder/Try it starts the tool", screen() == null && Placements.mode() == Placements.Mode.EDIT, screen() + ", mode " + Placements.mode()));
        act(() -> Placements.setMode(Placements.Mode.IDLE));

        // the ? of a choice of the Build panel opens that choice's lesson, and closing it comes back to the panel
        act(() -> {
            Lessons.markSeen("build");
            Lessons.markSeen("auto");
        });
        wheelOn(3);
        act(() -> {
            WheelScreen.testHeld = false;
            Interaction.testMainDown = false;
        });
        waitTicks(14);
        act(() -> check("ponder/the Build panel opens (its lesson was seen)", screen() instanceof io.github.profetgit.cyanotype.ui.ChoiceScreen, String.valueOf(screen())));
        shot("ponder_flow_build_panel");
        act(() -> {
            var cs = (io.github.profetgit.cyanotype.ui.ChoiceScreen) screen();
            click(cs.helpAnchor(1));
        });
        waitTicks(8);
        act(() -> check("ponder/the ? of 'Place what I look at' opens the auto lesson", ponder() != null && ponder().scene().id.equals("auto"), "screen " + screen()));
        act(() -> click(ponder().anchor("skip")));
        waitTicks(6);
        act(() -> check("ponder/closing it goes back to the Build panel", screen() instanceof io.github.profetgit.cyanotype.ui.ChoiceScreen, "screen " + screen()));
        act(() -> key(InputConstants.KEY_ESCAPE));
        waitTicks(4);
        act(() -> Placements.setMode(Placements.Mode.IDLE));

        // Try it cannot start a tool that is not ready: it says why and stays
        act(() -> {
            for (var p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            Lessons.open(Minecraft.getInstance(), "materials");
        });
        waitTicks(8);
        act(() -> {
            PonderScreen p = ponder();
            check("ponder/Try it waits for a placed blueprint", p != null, "screen " + screen());
            click(ponder().anchor("try"));
        });
        waitTicks(4);
        act(() -> check("ponder/and a click on it changes nothing", ponder() != null, "screen " + screen()));
        shot("ponder_flow_try_disabled");
        act(() -> cleanup());
        waitTicks(4);
        UiScenes.setup();

        // the keys
        act(() -> Lessons.open(Minecraft.getInstance(), "place"));
        waitTicks(10);
        act(() -> {
            PonderScreen p = ponder();
            p.player().seek(1.0);
            key(InputConstants.KEY_SPACE);
            check("ponder/Space pauses", !p.player().playing(), "playing " + p.player().playing());
        });
        waitTicks(6);
        act(() -> {
            PonderScreen p = ponder();
            double t = p.player().time();
            key(InputConstants.KEY_SPACE);
            check("ponder/Space plays again", p.player().playing(), "playing " + p.player().playing() + " at " + t);
            key(InputConstants.KEY_RIGHT);
            check("ponder/Right goes to the next step", p.player().step() == 1 && Math.abs(p.player().time() - p.scene().stepStart(1)) < 0.1, "step " + p.player().step() + ", time " + p.player().time());
            key(InputConstants.KEY_R);
            check("ponder/R starts over", p.player().step() == 0 && p.player().time() < 0.3, "step " + p.player().step() + ", time " + p.player().time());
        });
        act(() -> {
            PonderScreen p = ponder();
            int before = p.player().step();
            p.mouseScrolled(100, 100, 0, -1);
            check("ponder/the mouse wheel steps on", p.player().step() == (before + 1) % p.scene().steps(), "step " + before + " -> " + p.player().step());
            p.mouseScrolled(100, 100, 0, 1);
            check("ponder/and back", p.player().step() == before, "step " + p.player().step());
        });
        // a click on the scrub bar seeks, on the picture pauses
        act(() -> click(ponder().scrubAt(0.5)));
        act(() -> {
            PonderScreen p = ponder();
            check("ponder/a click on the scrub bar goes there", Math.abs(p.player().time() - p.scene().duration * 0.5) < 0.6, "time " + p.player().time() + " of " + p.scene().duration);
            boolean was = p.player().playing();
            click(p.anchor("view"));
            check("ponder/a click on the picture plays or pauses", p.player().playing() != was, "was " + was + ", now " + p.player().playing());
            click(p.anchor("speed"));
            check("ponder/the speed button slows it to half", p.player().speed() < 0.75, "speed " + p.player().speed());
        });
        act(() -> {
            key(InputConstants.KEY_ESCAPE);
        });
        waitTicks(4);
        act(() -> check("ponder/Esc leaves a replay without starting a tool", screen() == null, "screen " + screen()));

        // reduced motion: paused on the finished picture of the first step, steps jump from still to still
        act(() -> {
            Settings.get().reduceMotion = true;
            Lessons.open(Minecraft.getInstance(), "edit");
        });
        waitTicks(8);
        act(() -> {
            PonderScreen p = ponder();
            double still = p.player().stillTime(0);
            check("ponder/reduced motion: opens paused on the still of step 1", !p.player().playing() && Math.abs(p.player().time() - still) < 1e-6, "playing " + p.player().playing() + ", time " + p.player().time() + ", still " + still);
            key(InputConstants.KEY_RIGHT);
            check("ponder/reduced motion: Next shows the still of step 2", p.player().step() == 1 && Math.abs(p.player().time() - p.player().stillTime(1)) < 1e-6, "step " + p.player().step() + ", time " + p.player().time());
        });
        waitTicks(6);
        shot("ponder_flow_reduced");
        act(() -> {
            Settings.get().reduceMotion = false;
            cleanup();
        });
        waitTicks(4);

        // the Help list
        act(() -> Minecraft.getInstance().gui.setScreen(new HelpScreen(null)));
        waitTicks(8);
        act(() -> {
            HelpScreen h = (HelpScreen) screen();
            check("ponder/the Help list shows every lesson", h.listed().size() == Lessons.ids().size() && h.listed().size() >= 9, h.listed().toString());
        });
        shot("ponder_help_all");
        act(() -> ((HelpScreen) screen()).type("paste"));
        waitTicks(4);
        act(() -> check("ponder/searching 'paste' finds the paste lesson", ((HelpScreen) screen()).listed().equals(java.util.List.of("paste")), ((HelpScreen) screen()).listed().toString()));
        shot("ponder_help_search");
        act(() -> ((HelpScreen) screen()).type("zzzz"));
        waitTicks(4);
        act(() -> check("ponder/a search with no match lists nothing", ((HelpScreen) screen()).listed().isEmpty(), ((HelpScreen) screen()).listed().toString()));
        shot("ponder_help_empty");
        act(() -> ((HelpScreen) screen()).type("layer"));
        waitTicks(4);
        act(() -> {
            HelpScreen h = (HelpScreen) screen();
            check("ponder/'layer' finds the layers lesson", h.listed().contains("layers"), h.listed().toString());
            click(h.anchor("row:layers"));
        });
        waitTicks(8);
        act(() -> check("ponder/a click on a row opens that lesson", ponder() != null && ponder().scene().id.equals("layers"), "screen " + screen()));
        act(() -> click(ponder().anchor("skip")));
        waitTicks(4);
        act(() -> check("ponder/closing a lesson from the list goes back to the list", screen() instanceof HelpScreen, "screen " + screen()));
        act(() -> key(InputConstants.KEY_ESCAPE));
        waitTicks(4);
        act(() -> check("ponder/Esc closes the list", screen() == null, "screen " + screen()));

        // Settings: the switch, start over, and the list
        act(() -> Minecraft.getInstance().gui.setScreen(new io.github.profetgit.cyanotype.ui.SettingsScreen()));
        waitTicks(10);
        act(() -> click(((io.github.profetgit.cyanotype.ui.SettingsScreen) screen()).anchor("tab:LESSONS")));
        waitTicks(6);
        act(() -> {
            var st = (io.github.profetgit.cyanotype.ui.SettingsScreen) screen();
            boolean before = Settings.get().lessons;
            click(st.anchor("lessons"));
            check("ponder/Settings: the lesson switch turns lessons off", Settings.get().lessons != before, "lessons " + Settings.get().lessons);
            click(st.anchor("lessons"));
            check("ponder/and on again", Settings.get().lessons == before, "lessons " + Settings.get().lessons);
            Lessons.markSeen("place");
            check("ponder/a lesson is marked seen", Lessons.seen("place"), "seen " + Settings.get().lessonsSeen);
            click(st.anchor("lessonsAgain"));
            check("ponder/Settings: Show all again clears what was seen", Settings.get().lessonsSeen.isEmpty(), "seen " + Settings.get().lessonsSeen);
        });
        shot("ponder_settings");
        act(() -> click(((io.github.profetgit.cyanotype.ui.SettingsScreen) screen()).anchor("lessonsOpen")));
        waitTicks(8);
        act(() -> check("ponder/Settings: Open the lessons shows the list", screen() instanceof HelpScreen, "screen " + screen()));
        act(() -> key(InputConstants.KEY_ESCAPE));
        waitTicks(4);
        act(() -> check("ponder/and Esc comes back to Settings", screen() instanceof io.github.profetgit.cyanotype.ui.SettingsScreen, "screen " + screen()));
        act(() -> cleanup());
        waitTicks(4);

        // the middle of the wheel opens the list too
        act(() -> {
            WheelScreen.testHeld = true;
            Interaction.testMainDown = true;
        });
        waitTicks(8);
        act(() -> Ui.testMouse = new int[]{screen().width / 2, screen().height / 2});
        waitTicks(6);
        shot("ponder_flow_hub");
        act(() -> {
            click(new int[]{screen().width / 2, screen().height / 2});
            WheelScreen.testHeld = null;
            Interaction.testMainDown = null;
        });
        waitTicks(8);
        act(() -> check("ponder/a click in the middle of the wheel opens the list", screen() instanceof HelpScreen, "screen " + screen()));
        act(() -> {
            cleanup();
            // the other scenes run without lessons popping up over them
            Settings.get().lessons = false;
            Settings.get().reduceMotion = false;
        });
        waitTicks(4);
        UiScenes.teardown();
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
