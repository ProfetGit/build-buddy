package io.github.profetgit.cyanotype.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * One screen, six groups (PRD 7.10): the group list on the left, the settings of the chosen group on the right, each with a
 * one-line description. Changes apply as they are made and are saved a moment later; every setting has a default that
 * works, and Advanced can put them all back.
 */
public final class SettingsScreen extends Screen {
    private enum Group {
        APPEARANCE("Appearance"), GHOST("Ghost"), MATERIALS("Materials"), COMMUNITY("Community"), AUTO("Auto-place"), SERVERS("Servers"), ADVANCED("Advanced");

        final String label;

        Group(String label) {
            this.label = label;
        }
    }

    private sealed interface Row permits Toggle, Slider, Action, Note {
        String id();
    }

    private record Toggle(String id, String label, String desc, BooleanSupplier get, Consumer<Boolean> set) implements Row {
    }

    private record Slider(String id, String label, String desc, double min, double max, double step, DoubleSupplier get, DoubleConsumer set, DoubleFunction<String> format) implements Row {
    }

    /** A button with a line beside it; {@code run} is what a click does (null for the ones the screen handles itself: reset, local site). */
    private record Action(String id, String label, String desc, Runnable run) implements Row {
        Action(String id, String label, String desc) {
            this(id, label, desc, null);
        }
    }

    private record Note(String id, String title, String body) implements Row {
    }

    /** A row with where it is on screen. */
    private record Laid(Row row, int x, int y, int w, int h) {
    }

    private static final int PANEL_W = 400, TAB_W = 86, SIDE = 106, TRACK_H = 9;

    private Group group = Group.APPEARANCE;
    private final long openedNs = System.nanoTime();
    private String down = "";
    private String dragging = "";
    private long confirmNs;
    private float scrollTarget, scroll;

    public SettingsScreen() {
        super(Component.literal("Settings"));
    }

    @Override
    protected void init() {
        Sfx.play(Sfx.OPEN);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        g.fillGradient(0, 0, width, height, 0x30000000, 0x70000000);
    }

    @Override
    public void removed() {
        Settings.flush();
    }

    private int panelW() {
        return Math.min(width - 24, PANEL_W);
    }

    private int panelH() {
        return Math.min(height - 24, 268);
    }

    private int px() {
        return (width - panelW()) / 2;
    }

    private int py() {
        return (height - panelH()) / 2;
    }

    // ---- what each group holds

    private List<Row> rows(Group g) {
        Settings.Data d = Settings.get();
        List<Row> r = new ArrayList<>();
        switch (g) {
            case APPEARANCE -> {
                r.add(new Toggle("chips", "Cursor hints", "Key hints beside the crosshair.", () -> d.chips, v -> d.chips = v));
                r.add(new Toggle("reduceMotion", "Reduce motion", "No springs or draw-on. Sound stays.", () -> d.reduceMotion, v -> d.reduceMotion = v));
                r.add(new Slider("sounds", "Sound volume", "Clicks and chimes of the interface. 0 is off.", 0, 100, 5, () -> d.sounds * 100, v -> d.sounds = (float) (v / 100),
                    v -> v <= 0 ? "Off" : (int) v + " %"));
                r.add(new Slider("wheelHold", "Wheel hold time", "Hold the tool key this long to open the wheel.", 100, 600, 25, () -> d.wheelHoldMs, v -> d.wheelHoldMs = (int) v,
                    v -> (int) v + " ms"));
            }
            case GHOST -> {
                r.add(new Slider("opacity", "Opacity of new ghosts", "How see-through a ghost is when you place it.", 5, 100, 5, () -> d.opacity * 100, v -> d.opacity = (float) (v / 100),
                    v -> (int) v + " %"));
                r.add(new Slider("range", "Draw distance", "How far from you ghost blocks are drawn.", 64, 512, 16, () -> d.range, v -> d.range = (int) v,
                    v -> (int) v + " blocks"));
                r.add(new Toggle("fade", "Fade with distance", "Far parts fade out instead of ending sharply.", () -> d.fade, v -> d.fade = v));
                r.add(new Toggle("verify", "Check what is built", "Hide right blocks, mark wrong ones, count progress.", () -> d.verify, v -> d.verify = v));
            }
            case MATERIALS -> r.add(new Toggle("groupVariants", "Group variants", "Count every colour or wood of a block as one line.", () -> d.groupVariants, v -> d.groupVariants = v));
            case COMMUNITY -> {
                String base = io.github.profetgit.cyanotype.community.Community.base();
                r.add(new Toggle("community", "Community tab", "Browse and download builds that people shared on the community website. Off sends nothing.",
                    () -> io.github.profetgit.cyanotype.community.CommunityConfig.ON.equals(io.github.profetgit.cyanotype.community.Community.consent()),
                    v -> io.github.profetgit.cyanotype.community.Community.setConsent(v ? io.github.profetgit.cyanotype.community.CommunityConfig.ON : io.github.profetgit.cyanotype.community.CommunityConfig.OFF)));
                r.add(new Note("communityUrl", "Website address", base != null ? base + "  To change it, edit communityUrl in config/cyanotype.json."
                    : "None yet: the community website has no public address. To try a local copy, edit communityUrl in config/cyanotype.json or use the button below."));
                r.add(new Action("localSite", "Local site", "Test site on this computer."));
            }
            case AUTO -> {
                r.add(new Slider("autoRate", "Sweep speed", "Blocks a second while it builds by itself. Assist places one the moment you press, and while held one every tick. The mod never goes above " + io.github.profetgit.cyanotype.auto.Rate.MAX + ".",
                    io.github.profetgit.cyanotype.auto.Rate.MIN, io.github.profetgit.cyanotype.auto.Rate.MAX, 1, () -> d.autoRate, v -> d.autoRate = (int) v, v -> (int) v + " a second"));
                r.add(new Toggle("autoTurn", "Turn to face", "Let it turn your view toward blocks that need a facing (stairs, logs, observers). Off: it places only what works the way you look.",
                    () -> d.autoTurn, v -> d.autoTurn = v));
                r.add(new Note("auto", "How to use it", "Choose Build on the wheel: Assist places the ghost under your crosshair the moment you press use and keeps going while you hold it (and puts nothing else down), Sweep builds everything in reach, lowest layer first. "
                    + "It stops when you are hurt, open any screen (Esc too) or run out of the blocks. On a multiplayer server it stays off until you say yes for that server."));
            }
            case SERVERS -> serverRows(r);
            case ADVANCED -> {
                r.add(new Toggle("saveToSchematics", "Save into the schematics folder", "New builds go in the game's schematics folder, where Litematica finds them too. Off: config/cyanotype/blueprints. Both folders always show in the Library.", () -> d.saveToSchematics, v -> d.saveToSchematics = v));
                r.add(new Toggle("showNames", "Names over ghosts", "Show each placement's name above its ghost.", () -> d.showNames, v -> d.showNames = v));
                r.add(new Action("reset", "Reset all", "Back to the defaults."));
            }
        }
        return r;
    }

    /** The servers the player has decided about, and the one they are on. */
    private void serverRows(List<Row> r) {
        io.github.profetgit.cyanotype.auto.ServerRules rules = io.github.profetgit.cyanotype.auto.ServerRules.get();
        String here = io.github.profetgit.cyanotype.auto.AutoBuilder.serverKey(Minecraft.getInstance());
        if (here == null) {
            r.add(new Note("serversHere", "This world", "You are in a world of your own (singleplayer or a LAN world you host): auto-placing needs no warning here."));
        } else if (rules.decision(here) == io.github.profetgit.cyanotype.auto.ServerRules.Decision.BLOCKED) {
            r.add(new Action("blockHere", "Unblock", "Take " + here + " off the list.", () -> rules.unblock(here)));
        } else {
            r.add(new Action("blockHere", "Block", "Never allow auto-placing on " + here, () -> {
                rules.block(here);
                io.github.profetgit.cyanotype.auto.AutoBuilder.stop(Minecraft.getInstance(), "Auto-placing is blocked on this server");
            }));
        }
        List<String> blocked = rules.blocked();
        for (String b : blocked) r.add(new Action("unblock:" + b, "Unblock", "Blocked: " + b, () -> rules.unblock(b)));
        for (var e : rules.remembered().entrySet()) {
            String k = e.getKey();
            r.add(new Action("forget:" + k, "Ask again", ("allowed".equals(e.getValue()) ? "Allowed: " : "Kept off: ") + k, () -> rules.forget(k)));
        }
        if (blocked.isEmpty() && rules.remembered().isEmpty()) {
            r.add(new Note("servers", "No servers listed", "Servers you allow, keep off or block show up here. Blocked ones can never have auto-placing switched on."));
        }
    }

    private int heightOf(Row row, int w) {
        return switch (row) {
            case Toggle t -> 14 + Ui.wrap(t.desc, w - 16, Integer.MAX_VALUE).size() * 9 + 6;
            case Slider s -> 12 + Ui.wrap(s.desc, w, Integer.MAX_VALUE).size() * 9 + TRACK_H + 10;
            case Action a -> Math.max(22, 6 + Ui.wrap(a.desc, w - 98, Integer.MAX_VALUE).size() * 9);
            case Note n -> 14 + Ui.wrap(n.body, w, Integer.MAX_VALUE).size() * 9;
        };
    }

    /** The part of the panel the rows of a group are drawn in (and can be clicked in). */
    private int areaTop() {
        return py() + 44;
    }

    private int areaBottom() {
        return py() + panelH() - 28;
    }

    private boolean inArea(int mx, int my) {
        return Ui.inside(mx, my, px() + SIDE, areaTop(), panelW() - SIDE - 8, areaBottom() - areaTop());
    }

    private List<Laid> laid() {
        int cx = px() + SIDE + 4, cw = panelW() - SIDE - 4 - 12;
        int y = areaTop() - Math.round(scroll);
        List<Laid> out = new ArrayList<>();
        for (Row r : rows(group)) {
            int h = heightOf(r, cw);
            out.add(new Laid(r, cx, y, cw, h));
            y += h + 4;
        }
        return out;
    }

    private int contentHeight() {
        int cw = panelW() - SIDE - 4 - 12, total = 0;
        for (Row r : rows(group)) total += heightOf(r, cw) + 4;
        return Math.max(0, total - 4);
    }

    private float maxScroll() {
        return Math.max(0, contentHeight() - (areaBottom() - areaTop()));
    }

    /** x, y, width, height of a slider's track. */
    private static int[] trackOf(Laid l) {
        return new int[]{l.x, l.y + l.h - TRACK_H - 2, l.w, TRACK_H};
    }

    private static double fractionOf(Slider s) {
        return (s.get.getAsDouble() - s.min) / (s.max - s.min);
    }

    private static double valueAt(Slider s, double mx, int[] track) {
        double f = (mx - (track[0] + 2 + 2.5)) / (track[2] - 4 - 5);
        f = Math.max(0, Math.min(1, f));
        double v = s.min + f * (s.max - s.min);
        return Math.max(s.min, Math.min(s.max, Math.round(v / s.step) * s.step));
    }

    // ---- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        double open = Motion.reduced() ? 1 : Math.min(1, (System.nanoTime() - openedNs) / 1e9 / 0.25);
        int pw = panelW(), ph = panelH(), px = px(), py = py();
        float inner = Ui.panelOpening(g, px, py, pw, ph, open);
        if (inner < 0.05f) return;
        double t = System.nanoTime() / 1e9;

        Ui.text(g, "Settings", px + 10, py + 8, Ui.withAlpha(Ui.LINE, inner));
        Ui.right(g, "Changes apply at once", px + pw - 10, py + 8, Ui.withAlpha(Ui.DIM, inner));
        g.fill(px + 8, py + 21, px + pw - 8, py + 22, Ui.withAlpha(Ui.DIM, inner * 0.35f));

        int ty = py + 30;
        for (Group gr : Group.values()) {
            Ui.tab(g, "set#tab" + gr, px + 10, ty, TAB_W, gr.label, gr == group, mx, my);
            ty += 15;
        }
        g.fill(px + SIDE - 4, py + 28, px + SIDE - 3, py + ph - 30, Ui.withAlpha(Ui.DIM, inner * 0.35f));

        Ui.text(g, group.label, px + SIDE + 4, py + 30, Ui.withAlpha(Ui.CYAN, inner));
        scroll = Motion.follow("set#scroll", Math.max(0, Math.min(maxScroll(), scrollTarget)), 0.06);
        g.enableScissor(px + SIDE, areaTop(), px + pw - 8, areaBottom());
        for (Laid l : laid()) {
            switch (l.row) {
                case Toggle tg -> toggle(g, tg, l, mx, my, inner);
                case Slider sl -> slider(g, sl, l, mx, my, inner, t);
                case Action ac -> action(g, ac, l, mx, my, inner);
                case Note n -> note(g, n, l, inner);
            }
        }
        g.disableScissor();
        float max = maxScroll();
        if (max > 0) {
            int trackH = areaBottom() - areaTop(), barH = Math.max(12, (int) (trackH * trackH / (trackH + max)));
            int barY = areaTop() + (int) ((trackH - barH) * (scroll / max));
            g.fill(px + pw - 7, areaTop(), px + pw - 5, areaBottom(), Ui.withAlpha(Ui.DEEP, 0.7f));
            g.fill(px + pw - 7, barY, px + pw - 5, barY + barH, Ui.withAlpha(Ui.CYAN, 0.9f));
        }

        int fy = py + ph - 24;
        Ui.text(g, Ui.fit("Saved in config/" + Settings.file().getFileName(), pw - 90), px + 10, fy + 4, Ui.withAlpha(Ui.DIM, inner * 0.8f));
        Ui.button(g, "set#done", px + pw - 10 - 52, fy, 52, 16, "Done", null, mx, my, down.equals("done"), true);
    }

    private void toggle(GuiGraphicsExtractor g, Toggle tg, Laid l, int mx, int my, float a) {
        boolean over = Ui.inside(mx, my, l.x, l.y, l.w, l.h - 2);
        boolean on = tg.get.getAsBoolean();
        Ui.checkbox(g, "set#" + tg.id, l.x, l.y + 1, on, over);
        Ui.text(g, tg.label, l.x + 16, l.y + 1, Ui.withAlpha(over ? Ui.WHITE : Ui.LINE, a));
        Ui.right(g, on ? "On" : "Off", l.x + l.w, l.y + 1, Ui.withAlpha(on ? Ui.CYAN : Ui.DIM, a));
        int dy = l.y + 13;
        for (String line : Ui.wrap(tg.desc, l.w - 16, Integer.MAX_VALUE)) {
            Ui.text(g, line, l.x + 16, dy, Ui.withAlpha(Ui.DIM, a));
            dy += 9;
        }
    }

    private void slider(GuiGraphicsExtractor g, Slider sl, Laid l, int mx, int my, float a, double t) {
        int[] track = trackOf(l);
        boolean over = Ui.inside(mx, my, l.x, track[1] - 3, l.w, TRACK_H + 6);
        Ui.text(g, sl.label, l.x, l.y + 1, Ui.withAlpha(over || dragging.equals(sl.id) ? Ui.WHITE : Ui.LINE, a));
        Ui.right(g, sl.format.apply(sl.get.getAsDouble()), l.x + l.w, l.y + 1, Ui.withAlpha(Ui.CYAN, a));
        int dy = l.y + 12;
        for (String line : Ui.wrap(sl.desc, l.w, Integer.MAX_VALUE)) {
            Ui.text(g, line, l.x, dy, Ui.withAlpha(Ui.DIM, a));
            dy += 9;
        }
        Ui.slider(g, "set#" + sl.id, track[0], track[1], track[2], track[3], fractionOf(sl), over, dragging.equals(sl.id), t);
    }

    private void action(GuiGraphicsExtractor g, Action ac, Laid l, int mx, int my, float a) {
        boolean confirm = System.nanoTime() - confirmNs < 3_000_000_000L;
        String label = confirm ? "Click again" : ac.label;
        Ui.button(g, "set#" + ac.id, l.x, l.y + 1, 90, 16, label, null, mx, my, down.equals(ac.id), true);
        int dy = l.y + 5;
        for (String line : Ui.wrap(confirm ? "This puts every setting back." : ac.desc, l.w - 98, Integer.MAX_VALUE)) {
            Ui.text(g, line, l.x + 98, dy, Ui.withAlpha(confirm ? Ui.WARN : Ui.DIM, a));
            dy += 9;
        }
    }

    private void note(GuiGraphicsExtractor g, Note n, Laid l, float a) {
        Ui.text(g, n.title, l.x, l.y + 2, Ui.withAlpha(Ui.LINE, a));
        int dy = l.y + 14;
        for (String line : Ui.wrap(n.body, l.w, Integer.MAX_VALUE)) {
            Ui.text(g, line, l.x, dy, Ui.withAlpha(Ui.DIM, a));
            dy += 9;
        }
    }

    // ---- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        int px = px(), py = py(), ph = panelH(), pw = panelW();
        int ty = py + 30;
        for (Group gr : Group.values()) {
            if (Ui.inside(mx, my, px + 10, ty, TAB_W, 13)) {
                if (gr != group) {
                    group = gr;
                    scrollTarget = 0;
                    dragging = "";
                    Sfx.play(Sfx.PRESS, 1.1f);
                }
                return true;
            }
            ty += 15;
        }
        if (Ui.inside(mx, my, px + pw - 10 - 52, py + ph - 24, 52, 16)) {
            down = "done";
            Sfx.play(Sfx.PRESS);
            return true;
        }
        for (Laid l : laid()) {
            if (!inArea(mx, my)) break;
            switch (l.row) {
                case Toggle tg -> {
                    if (Ui.inside(mx, my, l.x, l.y, l.w, l.h - 2)) {
                        boolean now = !tg.get.getAsBoolean();
                        tg.set.accept(now);
                        Settings.changed();
                        Sfx.play(Sfx.PRESS, now ? 1.2f : 0.9f);
                        return true;
                    }
                }
                case Slider sl -> {
                    int[] track = trackOf(l);
                    if (Ui.inside(mx, my, l.x, track[1] - 3, l.w, TRACK_H + 6)) {
                        dragging = sl.id;
                        slide(sl, valueAt(sl, mx, track));
                        return true;
                    }
                }
                case Action ac -> {
                    if (Ui.inside(mx, my, l.x, l.y + 1, 90, 16)) {
                        down = ac.id;
                        Sfx.play(Sfx.PRESS);
                        return true;
                    }
                }
                case Note n -> {
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void slide(Slider sl, double value) {
        if (value == sl.get.getAsDouble()) return;
        sl.set.accept(value);
        Settings.changed();
        // a tick per step, higher as the value rises, quiet enough to be a ruler and not a buzz
        if (!sl.id.equals("sounds")) Sfx.play(Sfx.SNAP, (float) (0.8 + 0.5 * (value - sl.min) / (sl.max - sl.min)), 0.5f);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging.isEmpty()) {
            for (Laid l : laid()) {
                if (l.row instanceof Slider sl && sl.id.equals(dragging)) slide(sl, valueAt(sl, event.x(), trackOf(l)));
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mx = (int) event.x(), my = (int) event.y();
        String was = down, drag = dragging;
        down = "";
        dragging = "";
        if (!drag.isEmpty()) {
            // after moving the volume, play a click at the new level so it can be judged
            if (drag.equals("sounds")) Sfx.play(Sfx.PRESS);
            return true;
        }
        if (was.equals("done") && Ui.inside(mx, my, px() + panelW() - 10 - 52, py() + panelH() - 24, 52, 16)) {
            Sfx.play(Sfx.CLOSE);
            onClose();
            return true;
        }
        for (Laid l : laid()) {
            if (l.row instanceof Action ac && ac.run() != null && ac.id().equals(was) && Ui.inside(mx, my, l.x, l.y + 1, 90, 16)) {
                ac.run().run();
                Sfx.play(Sfx.PRESS, 1.1f);
                return true;
            }
        }
        if (was.equals("localSite")) {
            for (Laid l : laid()) {
                if (l.row instanceof Action ac && ac.id.equals("localSite") && Ui.inside(mx, my, l.x, l.y + 1, 90, 16)) {
                    Settings.get().communityUrl = io.github.profetgit.cyanotype.community.CommunityConfig.LOCAL_TEST_URL;
                    Settings.changed();
                    Sfx.play(Sfx.COMPLETE);
                    return true;
                }
            }
        }
        if (was.equals("reset")) {
            for (Laid l : laid()) {
                if (l.row instanceof Action ac && ac.id.equals("reset") && Ui.inside(mx, my, l.x, l.y + 1, 90, 16)) {
                    if (System.nanoTime() - confirmNs < 3_000_000_000L) {
                        confirmNs = 0;
                        Settings.reset();
                        Sfx.play(Sfx.COMPLETE);
                    } else {
                        confirmNs = System.nanoTime();
                    }
                    return true;
                }
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) scrollY * 20));
        return true;
    }

    // ---- the demo drives the real mouse paths through these

    /** Dev demo: the middle of a control on screen ("tab:GHOST", a toggle's id, "reset", "done"), or null. */
    public int[] anchor(String id) {
        if (id.startsWith("tab:")) {
            int i = Group.valueOf(id.substring(4)).ordinal();
            return new int[]{px() + 10 + TAB_W / 2, py() + 30 + i * 15 + 6};
        }
        if (id.equals("done")) return new int[]{px() + panelW() - 10 - 26, py() + panelH() - 16};
        for (Laid l : laid()) {
            if (!l.row.id().equals(id)) continue;
            return switch (l.row) {
                case Toggle tg -> new int[]{l.x + 5, l.y + 6};
                case Action ac -> new int[]{l.x + 45, l.y + 9};
                default -> null;
            };
        }
        return null;
    }

    /** Dev demo: where on screen a slider's track is for a share of its range (0 to 1), or null. */
    public int[] sliderPoint(String id, double fraction) {
        for (Laid l : laid()) {
            if (l.row instanceof Slider sl && sl.id.equals(id)) {
                int[] track = trackOf(l);
                return new int[]{(int) Math.round(track[0] + 2 + 2.5 + fraction * (track[2] - 4 - 5)), track[1] + TRACK_H / 2};
            }
        }
        return null;
    }

    /** Dev demo: whether a control ("slider:<id>" or a toggle's id) is inside the panel's content area right now, not scrolled out. */
    public boolean reachable(String id) {
        boolean slider = id.startsWith("slider:");
        int[] p = slider ? sliderPoint(id.substring(7), 0.5) : anchor(id);
        if (p == null) return false;
        // a slider counts only when its whole track is inside, not when it is cut in half
        return inArea(p[0], p[1]) && (!slider || inArea(p[0], p[1] - TRACK_H / 2 - 1) && inArea(p[0], p[1] + TRACK_H / 2 + 1));
    }

    /** Dev demo: how far the rows are scrolled, for the log. */
    public String scrollState() {
        return "target " + scrollTarget + ", shown " + scroll + ", max " + maxScroll() + ", content " + contentHeight() + ", area " + (areaBottom() - areaTop()) + ", size " + width + "x" + height;
    }

    /** Dev demo: the group shown. */
    public String groupName() {
        return group.name();
    }
}
