package io.github.profetgit.cyanotype.ponder;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.cyanotype.ui.ChipIcons;
import io.github.profetgit.cyanotype.ui.Motion;
import io.github.profetgit.cyanotype.ui.Settings;
import io.github.profetgit.cyanotype.ui.Sfx;
import io.github.profetgit.cyanotype.ui.Skin;
import io.github.profetgit.cyanotype.ui.Ui;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * "How it works": one lesson playing in a panel. The picture is {@link PonderView}; under it the caption of the step, the cursor
 * chips of that moment (the same pictures as in the game), a scrub bar with a tick at every step, and the buttons. Space plays and
 * pauses, Left and Right go to the step before and after, R starts over, Enter is Try it, Esc leaves. Opened the first time a tool
 * is used it ends in Try it or Skip, and both start the tool (it was asked for); opened again from a ? it ends in Try it or Close,
 * and Close starts nothing.
 */
public final class PonderScreen extends Screen {
    public enum Mode { FIRST_USE, REPLAY }

    private static final int PAD = 8;

    private final Scene scene;
    private final Player player;
    private final Mode mode;
    private final Runnable tryIt;
    private final String why;
    private final PonderView view;
    private final @Nullable Screen parent;
    private boolean leaving;
    private final long openedNs = System.nanoTime();
    private final Map<String, int[]> rects = new HashMap<>();
    private Snapshot snap;
    private double snapT = -1;
    private String down = "";
    private boolean scrubbing;

    /** @param why the reason Try it cannot be used now, or empty */
    public PonderScreen(Scene scene, Mode mode, Runnable tryIt, String why) {
        this(scene, mode, tryIt, why, null);
    }

    /** @param parent the screen to go back to when the lesson is closed (the Help list), or null for the game */
    public PonderScreen(Scene scene, Mode mode, Runnable tryIt, String why, @Nullable Screen parent) {
        super(Component.literal(scene.title));
        this.parent = parent;
        this.scene = scene;
        this.mode = mode;
        this.tryIt = tryIt;
        this.why = why == null ? "" : why;
        this.player = new Player(scene, Motion.reduced());
        this.view = new PonderView(net.minecraft.client.Minecraft.getInstance());
    }

    public Player player() {
        return player;
    }

    public PonderView view() {
        return view;
    }

    public Scene scene() {
        return scene;
    }

    /** Dev demo: the middle of a named control ("try", "skip", "play", "next", "prev", "restart", "speed", "view", "scrub"), or null. */
    public int @Nullable [] anchor(String name) {
        int[] r = rects.get(name);
        return r == null ? null : new int[]{r[0] + r[2] / 2, r[1] + r[3] / 2};
    }

    /** Dev demo: a point of the scrub bar a fraction of the way along. */
    public int @Nullable [] scrubAt(double fraction) {
        int[] r = rects.get("scrub");
        return r == null ? null : new int[]{r[0] + 2 + (int) Math.round((r[2] - 4) * fraction), r[1] + r[3] / 2};
    }

    @Override
    protected void init() {
        Sfx.play(Sfx.OPEN);
    }

    @Override
    public void removed() {
        view.close();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Closing goes back to where the lesson was opened from (the Help list), unless a tool is being started: that goes to the game. */
    @Override
    public void onClose() {
        minecraft.gui.setScreen(leaving ? null : parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        g.fillGradient(0, 0, width, height, 0x50000000, 0x90000000);
    }

    // ---- layout

    private record Lay(int px, int py, int pw, int ph, int vx, int vy, int vw, int vh, int titleY, int capY, int capH, int chipY, int chipRows, int ctlY, int btnY, boolean compact) {
    }

    private Lay layout() {
        boolean compact = height < 250;
        int availH = height - 8;
        // the panel is always as wide as the screen allows, so the words wrap the same whatever the picture's size
        int pw = Math.max(232, Math.min(width - 16, 440));
        int textW = pw - 2 * PAD;
        int chipRows = compact ? 0 : chipRows(textW);
        int chipsH = chipRows * 17;
        // the caption area holds the longest caption of the lesson (a title line, and up to three of words)
        int capH = (compact ? 0 : 11) + captionLines(textW) * 10 + 1;
        int fixed = (compact ? 8 : 20) + 6 + capH + (chipsH > 0 ? 2 + chipsH : 0) + 4 + 14 + 8 + 18 + 8;
        int vh = Math.max(40, Math.min(textW * 9 / 16, availH - fixed));
        int vw = Math.min(textW, vh * 16 / 9);
        int top = (height - (fixed + vh)) / 2;
        int px = (width - pw) / 2;
        int vx0 = (width - vw) / 2;
        int titleY = top + 6;
        int vy = top + (compact ? 8 : 20);
        int capY = vy + vh + 6;
        int chipY = capY + capH + 2;
        int ctlY = chipY + chipsH + 4;
        int btnY = ctlY + 14 + 8;
        int ph = btnY + 18 + 8 - top;
        return new Lay(px, top, pw, ph, vx0, vy, vw, vh, titleY, capY, capH, chipY, chipRows, ctlY, btnY, compact);
    }

    private int capLinesWidth = -1, capLines;

    /** How many lines the longest caption (or the summary, when a lesson has none) takes at a width, at most three. */
    private int captionLines(int textW) {
        if (capLinesWidth != textW) {
            capLinesWidth = textW;
            int most = Ui.wrap(scene.summary, textW, 3).size();
            for (Scene.Caption c : scene.captions) most = Math.max(most, Ui.wrap(c.text(), textW, 3).size());
            capLines = Math.max(2, most);
        }
        return capLines;
    }

    /** One row of chips, or two when the widest set of the lesson does not fit on one. */
    private int chipRows(int avail) {
        int widest = 0;
        for (Scene.Chips c : scene.chips) {
            int total = 0;
            for (List<String> row : c.rows()) total += chipWidth(row.get(0), row.get(1)) + 4;
            widest = Math.max(widest, total);
        }
        if (widest == 0) return 0;
        return widest <= avail ? 1 : 2;
    }

    private static int chipWidth(String key, String action) {
        return ChipIcons.width(key) + Ui.font().width(action) + 19;
    }

    // ---- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        if (!scrubbing) player.update(Motion.dt());
        double t = player.time();
        if (snap == null || t != snapT) {
            snap = player.snapshot();
            snapT = t;
        }
        Lay l = layout();
        rects.clear();
        double open = Motion.reduced() ? 1 : Math.min(1, (System.nanoTime() - openedNs) / 1e9 / 0.2);
        float inner = Ui.panelOpening(g, l.px, l.py, l.pw, l.ph, open);
        if (inner < 0.05f) return;
        // title and step counter
        if (!l.compact) {
            Ui.text(g, Ui.fit(scene.title, l.pw - 90), l.px + PAD, l.titleY, Ui.withAlpha(Ui.WHITE, inner));
            String steps = "Step " + (snap.step + 1) + " of " + snap.steps;
            Ui.right(g, steps, l.px + l.pw - PAD, l.titleY, Ui.withAlpha(Ui.DIM, inner));
        }
        // the picture
        Ui.inset(g, l.vx - 1, l.vy - 1, l.vw + 2, l.vh + 2);
        rects.put("view", new int[]{l.vx, l.vy, l.vw, l.vh});
        view.draw(g, scene, snap, l.vx, l.vy, l.vw, l.vh);
        g.enableScissor(l.vx, l.vy, l.vx + l.vw, l.vy + l.vh);
        Snapshot shown = view.shownSnapshot();
        PonderPanels.draw(g, snap.panels, l.vx, l.vy, l.vw, l.vh);
        drawLabels(g, l);
        if (shown != null && shown.cursor != null) drawCursor(g, l, shown.cursor);
        if (l.compact && !snap.chips.isEmpty()) drawChips(g, l.vx + 4, l.vy + l.vh - 20, l.vw - 8, 1, inner, true);
        g.disableScissor();
        // a paused lesson says so
        if (!player.playing() && !scrubbing) {
            Ui.text(g, "Paused", l.vx + 5, l.vy + 4, Ui.withAlpha(Ui.DIM, inner * 0.9f));
        }
        drawCaption(g, l, inner);
        if (!l.compact) drawChips(g, l.px + PAD, l.chipY, l.pw - 2 * PAD, l.chipRows, inner, false);
        drawControls(g, l, mx, my, inner);
        drawButtons(g, l, mx, my, inner);
    }

    private void drawCaption(GuiGraphicsExtractor g, Lay l, float inner) {
        Scene.Caption c = snap.caption;
        String title = c == null ? scene.title : c.title();
        String text = c == null ? scene.summary : c.text();
        double since = c == null ? 1 : snap.t - c.t0(), until = c == null ? 1 : c.t1() - snap.t;
        float a = (float) Math.max(0, Math.min(1, Math.min(since / 0.25, until / 0.2)));
        if (Motion.reduced() || !player.playing()) a = 1f;
        a = Math.max(a, 0.001f) * inner;
        int y = l.capY;
        if (!l.compact && c != null && !title.isEmpty()) {
            Ui.text(g, Ui.fit(title, l.pw - 2 * PAD), l.px + PAD, y, Ui.withAlpha(Ui.CYAN, a));
        }
        int ty = l.compact || c == null || title.isEmpty() ? y : y + 11;
        for (String line : Ui.wrap(text, l.pw - 2 * PAD, 3)) {
            Ui.text(g, line, l.px + PAD, ty, Ui.withAlpha(Ui.WHITE, a));
            ty += 10;
        }
    }

    private void drawChips(GuiGraphicsExtractor g, int x, int y, int width, int rows, float alpha, boolean over) {
        if (snap.chips.isEmpty()) return;
        int cx = x, cy = y;
        for (Snapshot.ChipRow row : snap.chips) {
            int cw = chipWidth(row.key(), row.action());
            if (cx + cw > x + width && cx > x) {
                cx = x;
                cy += 17;
                if (cy >= y + rows * 17) break;
            }
            Ui.blit(g, "tooltip", cx, cy, cw, 16, alpha * (row.lit() ? 1f : 0.82f));
            if (row.lit()) Ui.marching(g, cx - 1, cy - 1, cw + 2, 18, Ui.withAlpha(Ui.CYAN, alpha), snap.t);
            int kw = ChipIcons.width(row.key());
            ChipIcons.draw(g, row.key(), cx + 5, cy, alpha);
            g.fill(cx + 8 + kw, cy + 4, cx + 9 + kw, cy + 12, Ui.withAlpha(Ui.DIM, alpha * 0.55f));
            Ui.text(g, row.action(), cx + 12 + kw, cy + 4, Ui.withAlpha(row.lit() ? Ui.WHITE : Ui.LINE, alpha));
            cx += cw + 4;
        }
    }

    private void drawLabels(GuiGraphicsExtractor g, Lay l) {
        for (StageRaster.TextMark m : view.labels()) {
            int w = Ui.font().width(m.text());
            int x = (int) Math.round(l.vx + m.x()) - (m.anchor() == 0 ? w / 2 : m.anchor() < 0 ? w : 0);
            int y = (int) Math.round(l.vy + m.y()) - 4;
            float a = (float) m.alpha();
            if (m.boxed()) {
                g.fill(x - 3, y - 2, x + w + 3, y + 10, Ui.withAlpha(Ui.DEEP, 0.82f * a));
                g.fill(x - 3, y - 2, x + w + 3, y - 1, Ui.withAlpha(m.color(), 0.8f * a));
            }
            Ui.text(g, m.text(), x, y, Ui.withAlpha(0xFFFFFFFF, a));
        }
    }

    /** A spot the cursor points at, as GUI units from the picture's top left; null when it cannot be placed (no picture yet, no such panel). */
    private double @Nullable [] resolve(Lay l, Scene.Pt p) {
        return switch (p.kind()) {
            case Scene.Pt.WORLD -> view.project(p.at()[0], p.at()[1], p.at()[2]);
            case Scene.Pt.SCREEN -> new double[]{p.at()[0] * l.vw, p.at()[1] * l.vh};
            default -> {
                int[] a = PonderPanels.anchor(scene, snap, p.panel(), p.row(), l.vx, l.vy, l.vw, l.vh);
                yield a == null ? null : new double[]{a[0] - l.vx, a[1] - l.vy};
            }
        };
    }

    /** The mock pointer: the sprite with its tip on the spot, pressed a pixel down, with the corner-bracket ripple of a click round the spot. */
    private void drawCursor(GuiGraphicsExtractor g, Lay l, Snapshot.CursorView c) {
        double[] a = resolve(l, c.from()), b = c.to() == c.from() ? a : resolve(l, c.to());
        if (a == null) return;
        if (b == null) b = a;
        double gx = a[0] + (b[0] - a[0]) * c.k(), gy = a[1] + (b[1] - a[1]) * c.k();
        int x = (int) Math.round(l.vx + gx), y = (int) Math.round(l.vy + gy);
        float al = (float) c.alpha();
        if (c.clickAge() < 0.45 && !Motion.reduced()) {
            double k = c.clickAge() / 0.45;
            int r = 3 + (int) Math.round(9 * Motion.easeOut(k));
            bracket(g, x, y, r, Ui.withAlpha(Ui.CYAN, (float) (0.9 * (1 - k)) * al));
        }
        int sink = c.down() ? 1 : 0;
        Skin.pixel(g, "cursor", x - 3 + sink, y - 1 + sink, 16, 16, c.down() ? al * 0.9f : al);
    }

    private static void bracket(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        int len = Math.max(2, r / 2);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                int x = cx + sx * r, y = cy + sy * r;
                g.fill(Math.min(x, x - sx * len), y, Math.max(x, x - sx * len) + 1, y + 1, color);
                g.fill(x, Math.min(y, y - sy * len), x + 1, Math.max(y, y - sy * len) + 1, color);
            }
        }
    }

    private void drawControls(GuiGraphicsExtractor g, Lay l, int mx, int my, float inner) {
        int y = l.ctlY, x = l.px + PAD;
        x = ctl(g, "restart", "restart", x, y, 18, mx, my, inner);
        x = ctl(g, "prev", "step_prev", x, y, 18, mx, my, inner);
        x = ctl(g, "play", player.playing() ? "pause" : "play", x, y, 18, mx, my, inner);
        x = ctl(g, "next", "step_next", x, y, 18, mx, my, inner);
        int speedW = 30;
        int sx = x + 4, sw = l.px + l.pw - PAD - speedW - 4 - sx;
        boolean overScrub = Ui.inside(mx, my, sx, y, sw, 14);
        rects.put("scrub", new int[]{sx, y, sw, 14});
        Ui.slider(g, "pnd#scrub", sx, y + 3, sw, 8, player.time() / scene.duration, overScrub, scrubbing, snap.t);
        // a tick where each step begins
        for (int i = 1; i < scene.steps(); i++) {
            int tx = sx + 2 + (int) Math.round((sw - 4 - 5) * scene.stepStart(i) / scene.duration) + 2;
            g.fill(tx, y + 11, tx + 1, y + 14, Ui.withAlpha(Ui.DIM, inner));
        }
        int bx = l.px + l.pw - PAD - speedW;
        rects.put("speed", new int[]{bx, y, speedW, 14});
        Ui.button(g, "pnd#speed", bx, y, speedW, 14, player.speed() > 0.75 ? "1x" : "0.5x", null, mx, my, down.equals("speed"), true);
    }

    private int ctl(GuiGraphicsExtractor g, String name, String icon, int x, int y, int w, int mx, int my, float inner) {
        rects.put(name, new int[]{x, y, w, 14});
        Ui.button(g, "pnd#" + name, x, y, w, 14, "", icon, mx, my, down.equals(name), true);
        return x + w + 2;
    }

    private void drawButtons(GuiGraphicsExtractor g, Lay l, int mx, int my, float inner) {
        int y = l.btnY;
        boolean first = mode == Mode.FIRST_USE;
        int skipW = 56, tryW = 64;
        int sx = l.px + PAD, tx = l.px + l.pw - PAD - tryW;
        rects.put("skip", new int[]{sx, y, skipW, 18});
        Ui.button(g, "pnd#skip", sx, y, skipW, 18, first ? "Skip" : "Close", null, mx, my, down.equals("skip"), true);
        boolean can = why.isEmpty();
        rects.put("try", new int[]{tx, y, tryW, 18});
        Ui.button(g, "pnd#try", tx, y, tryW, 18, "Try it", null, mx, my, down.equals("try"), can);
        if (can) Ui.marching(g, tx - 2, y - 2, tryW + 4, 22, Ui.withAlpha(Ui.CYAN, inner), snap.t);
        // between them: why Try it waits, or the switch that stops the lessons
        int mid0 = sx + skipW + 8, mid1 = tx - 8;
        if (!can) {
            int ly = l.btnY + 1;
            for (String line : Ui.wrap(why, mid1 - mid0, 2)) {
                Ui.text(g, line, mid0, ly, Ui.withAlpha(Ui.WARN, inner));
                ly += 9;
            }
        } else if (first) {
            boolean on = !Settings.get().lessons;
            boolean over = Ui.inside(mx, my, mid0, y, mid1 - mid0, 18);
            rects.put("never", new int[]{mid0, y, mid1 - mid0, 18});
            Ui.checkbox(g, "pnd#never", mid0, y + 4, on, over);
            Ui.text(g, Ui.fit("Don't show lessons again", mid1 - mid0 - 14), mid0 + 14, y + 5, Ui.withAlpha(over ? Ui.WHITE : Ui.DIM, inner));
        }
    }

    // ---- input

    private String hit(int mx, int my) {
        for (Map.Entry<String, int[]> e : rects.entrySet()) {
            int[] r = e.getValue();
            if (!e.getKey().equals("view") && Ui.inside(mx, my, r[0], r[1], r[2], r[3])) return e.getKey();
        }
        int[] v = rects.get("view");
        return v != null && Ui.inside(mx, my, v[0], v[1], v[2], v[3]) ? "view" : "";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        int mx = (int) event.x(), my = (int) event.y();
        String h = hit(mx, my);
        if (h.isEmpty()) return super.mouseClicked(event, doubleClick);
        down = h;
        Sfx.play(Sfx.PRESS);
        if (h.equals("scrub")) {
            scrubbing = true;
            scrubTo(mx);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (scrubbing) {
            scrubTo((int) event.x());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    private void scrubTo(int mx) {
        int[] r = rects.get("scrub");
        if (r == null) return;
        double f = (mx - r[0] - 2) / (double) Math.max(1, r[2] - 4);
        player.seek(Math.max(0, Math.min(1, f)) * scene.duration);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        String was = down;
        down = "";
        boolean wasScrubbing = scrubbing;
        scrubbing = false;
        if (wasScrubbing) return true;
        if (was.isEmpty()) return super.mouseReleased(event);
        int mx = (int) event.x(), my = (int) event.y();
        if (!hit(mx, my).equals(was)) return true;
        act(was);
        return true;
    }

    private void act(String name) {
        switch (name) {
            case "restart" -> player.restart();
            case "prev" -> player.previous();
            case "next" -> player.next();
            case "play", "view" -> player.toggle();
            case "speed" -> player.cycleSpeed();
            case "skip" -> skip();
            case "try" -> tryNow();
            case "never" -> {
                Settings.get().lessons = !Settings.get().lessons;
                Settings.changed();
            }
            default -> {
            }
        }
    }

    private void skip() {
        Sfx.play(Sfx.CLOSE);
        onClose();
        // the tool was asked for: skipping the lesson still starts it
        if (mode == Mode.FIRST_USE) tryIt.run();
    }

    private void tryNow() {
        if (!why.isEmpty()) {
            Sfx.play(Sfx.ERROR, 0.8f);
            return;
        }
        Sfx.play(Sfx.RELEASE);
        leaving = true;
        onClose();
        tryIt.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            skip();
            return true;
        }
        if (event.isConfirmation()) {
            tryNow();
            return true;
        }
        int k = event.input();
        if (k == InputConstants.KEY_SPACE) {
            player.toggle();
            return true;
        }
        if (event.isLeft()) {
            player.previous();
            return true;
        }
        if (event.isRight()) {
            player.next();
            return true;
        }
        if (k == InputConstants.KEY_R) {
            player.restart();
            return true;
        }
        return super.keyPressed(event);
    }
}
