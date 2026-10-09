package io.github.profetgit.buildbuddy.ponder;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.buildbuddy.ui.LessonActions;
import io.github.profetgit.buildbuddy.ui.Motion;
import io.github.profetgit.buildbuddy.ui.Settings;
import io.github.profetgit.buildbuddy.ui.Sfx;
import io.github.profetgit.buildbuddy.ui.Ui;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * "How it works": every lesson of the mod in one list, with a search box. Click one (or move with the arrow keys and press Enter)
 * to watch it; closing it brings you back here. It is the fifth layer of the in-game help (PRD 7.12): the place to look when you
 * do not know which tool you want.
 */
public final class HelpScreen extends Screen {
    private static final int ROW_H = 30;

    private final @Nullable Screen parent;
    private final long openedNs = System.nanoTime();
    private final Map<String, int[]> rects = new HashMap<>();
    private EditBox search;
    private List<Scene> shown = List.of();
    private String lastQuery = null;
    private float scroll, scrollTarget;
    private int focus;
    private String down = "";

    public HelpScreen(@Nullable Screen parent) {
        super(Component.literal("How it works"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        search = new EditBox(font, 0, 0, 118, 12, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(40);
        search.setTextColor(Ui.WHITE);
        addRenderableWidget(search);
        setInitialFocus(search);
        Sfx.play(Sfx.OPEN);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        g.fillGradient(0, 0, width, height, 0x40000000, 0x80000000);
    }

    private int pw() {
        return Math.min(width - 24, 340);
    }

    private int ph() {
        return Math.min(height - 20, 252);
    }

    private int px() {
        return (width - pw()) / 2;
    }

    private int py() {
        return (height - ph()) / 2;
    }

    private int listTop() {
        return py() + 28;
    }

    private int listBottom() {
        return py() + ph() - 30;
    }

    /** Dev demo: what the search box holds. */
    public String query() {
        return search.getValue();
    }

    /** Dev demo: types into the search box. */
    public void type(String text) {
        search.setValue(text);
    }

    /** Dev demo: the ids of the lessons in the list now. */
    public List<String> listed() {
        return shown.stream().map(s -> s.id).toList();
    }

    /** Dev demo: the middle of a control or a row ("row:<id>", "close", "again", "never"). */
    public int @Nullable [] anchor(String name) {
        int[] r = rects.get(name);
        return r == null ? null : new int[]{r[0] + r[2] / 2, r[1] + r[3] / 2};
    }

    private void refresh() {
        String q = search == null ? "" : search.getValue();
        if (q.equals(lastQuery)) return;
        lastQuery = q;
        shown = LessonSearch.filter(Lessons.all(), q);
        focus = 0;
        scrollTarget = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        refresh();
        rects.clear();
        double open = Motion.reduced() ? 1 : Math.min(1, (System.nanoTime() - openedNs) / 1e9 / 0.2);
        int px = px(), py = py(), pw = pw(), ph = ph();
        float inner = Ui.panelOpening(g, px, py, pw, ph, open);
        if (inner < 0.05f) return;
        double t = System.nanoTime() / 1e9;
        Ui.text(g, "How it works", px + 10, py + 8, Ui.withAlpha(Ui.LINE, inner));
        int sbx = px + pw - 10 - 124;
        Ui.inset(g, sbx, py + 5, 124, 14);
        search.setX(sbx + 4);
        search.setY(py + 7);
        search.extractRenderState(g, mx, my, partial);
        if (search.getValue().isEmpty()) Ui.text(g, "Search lessons", sbx + 12, py + 8, Ui.withAlpha(Ui.DIM, inner * 0.8f));

        int top = listTop(), bottom = listBottom();
        float max = Math.max(0, shown.size() * (ROW_H + 2) - 2 - (bottom - top));
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        scroll = Motion.follow("help#scroll", scrollTarget, 0.06);
        g.enableScissor(px + 6, top - 2, px + pw - 6, bottom + 2);
        Scene tip = null;
        for (int i = 0; i < shown.size(); i++) {
            Scene s = shown.get(i);
            int y = top + i * (ROW_H + 2) - Math.round(scroll);
            if (y + ROW_H < top - 2 || y > bottom + 2) continue;
            int x = px + 8, w = pw - 16;
            boolean over = Ui.inside(mx, my, x, y, w, ROW_H) && Ui.inside(mx, my, px, top, pw, bottom - top);
            String key = "help#row" + i;
            float hv = Motion.hover(key, over), pr = Motion.press(key, over && down.equals("row:" + s.id));
            Ui.blit(g, "button_0", x, y, w, ROW_H, inner);
            Ui.blit(g, "button_1", x, y, w, ROW_H, hv * inner);
            Ui.blit(g, "button_2", x, y, w, ROW_H, pr * inner);
            boolean dark = pr > 0.5f;
            int sink = Math.round(pr);
            Ui.icon(g, LessonActions.icon(s.action), x + 7, y + (ROW_H - 16) / 2 + sink, dark, 16, inner);
            Ui.text(g, Ui.fit(s.title, w - 100), x + 30, y + 5 + sink, Ui.withAlpha(dark ? Ui.DEEP : Ui.WHITE, inner));
            Ui.text(g, Ui.fit(s.summary, w - 38 - 44), x + 30, y + 17 + sink, Ui.withAlpha(dark ? Ui.NAVY : Ui.DIM, inner));
            String len = (int) Math.round(s.duration) + " s";
            Ui.right(g, len, x + w - 8, y + 5 + sink, Ui.withAlpha(dark ? Ui.NAVY : Ui.DIM, inner));
            if (Lessons.seen(s.id)) Ui.icon(g, "check", x + w - 24, y + 11 + sink, dark, 14, inner * 0.9f);
            if (i == focus) Ui.marching(g, x - 2, y - 2, w + 4, ROW_H + 4, Ui.withAlpha(Ui.CYAN, inner), t);
            rects.put("row:" + s.id, new int[]{x, y, w, ROW_H});
            if (over) tip = s;
        }
        g.disableScissor();
        // the whole summary, when the row had to cut it short
        if (tip != null && Ui.font().width(tip.summary) > pw - 16 - 38 - 44) Ui.tooltip(g, mx, my, String.join("\n", Ui.wrap(tip.summary, 200, 5)));
        if (shown.isEmpty()) {
            Ui.centered(g, "No lesson matches \"" + Ui.fit(search.getValue(), 150) + "\"", px + pw / 2, top + 30, Ui.withAlpha(Ui.WARN, inner));
            Ui.centered(g, "Clear the search to see them all.", px + pw / 2, top + 44, Ui.withAlpha(Ui.DIM, inner));
        }
        // the foot: close, the switch, and start over
        int fy = py + ph - 24;
        rects.put("close", new int[]{px + pw - 10 - 52, fy, 52, 16});
        Ui.button(g, "help#close", px + pw - 10 - 52, fy, 52, 16, "Close", null, mx, my, down.equals("close"), true);
        rects.put("again", new int[]{px + pw - 10 - 52 - 4 - 92, fy, 92, 16});
        Ui.button(g, "help#again", px + pw - 10 - 52 - 4 - 92, fy, 92, 16, "Show all again", null, mx, my, down.equals("again"), true);
        boolean on = Settings.get().lessons;
        int cx = px + 10;
        boolean over = Ui.inside(mx, my, cx, fy, pw - 20 - 52 - 96 - 4, 16);
        rects.put("never", new int[]{cx, fy, pw - 20 - 52 - 96 - 4, 16});
        Ui.checkbox(g, "help#never", cx, fy + 3, on, over);
        String label = pw > 300 ? "Show a lesson the first time" : "First-use lessons";
        Ui.text(g, Ui.fit(label, pw - 20 - 52 - 96 - 4 - 14), cx + 14, fy + 4, Ui.withAlpha(over ? Ui.WHITE : Ui.LINE, inner));
    }

    private @Nullable String hit(int mx, int my) {
        int px = px(), pw = pw();
        for (Map.Entry<String, int[]> e : rects.entrySet()) {
            int[] r = e.getValue();
            boolean row = e.getKey().startsWith("row:");
            if (row && (my < listTop() || my > listBottom())) continue;
            if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) return e.getKey();
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        String h = hit((int) event.x(), (int) event.y());
        if (h == null) return super.mouseClicked(event, doubleClick);
        down = h;
        Sfx.play(Sfx.PRESS);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        String was = down;
        down = "";
        if (was.isEmpty()) return super.mouseReleased(event);
        if (!was.equals(hit((int) event.x(), (int) event.y()))) return true;
        if (was.startsWith("row:")) {
            openLesson(was.substring(4));
            return true;
        }
        switch (was) {
            case "close" -> {
                Sfx.play(Sfx.CLOSE);
                onClose();
            }
            case "again" -> {
                Lessons.resetSeen();
                Sfx.play(Sfx.RELEASE);
            }
            case "never" -> {
                Settings.get().lessons = !Settings.get().lessons;
                Settings.changed();
            }
            default -> {
            }
        }
        return true;
    }

    private void openLesson(String id) {
        Sfx.play(Sfx.RELEASE);
        Lessons.open(minecraft, id, this);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scrollTarget -= (float) (scrollY * 24);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            Sfx.play(Sfx.CLOSE);
            onClose();
            return true;
        }
        int k = event.input();
        if (k == InputConstants.KEY_DOWN || k == InputConstants.KEY_TAB) {
            focus = Math.min(Math.max(0, shown.size() - 1), focus + 1);
            Sfx.play(Sfx.PRESS, 1.1f);
            keepFocusVisible();
            return true;
        }
        if (k == InputConstants.KEY_UP) {
            focus = Math.max(0, focus - 1);
            Sfx.play(Sfx.PRESS, 1.1f);
            keepFocusVisible();
            return true;
        }
        if (event.isConfirmation() && !shown.isEmpty()) {
            openLesson(shown.get(Math.min(focus, shown.size() - 1)).id);
            return true;
        }
        return super.keyPressed(event);
    }

    private void keepFocusVisible() {
        int top = focus * (ROW_H + 2), view = listBottom() - listTop();
        if (top < scrollTarget) scrollTarget = top;
        else if (top + ROW_H > scrollTarget + view) scrollTarget = top + ROW_H - view;
    }
}
