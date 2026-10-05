package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A small panel that asks one thing: a few big choices with an icon, a name and a sentence each, and an optional switch
 * under them. It is where the wheel sends the tools that need a choice (Build: how it should help; Save: pick a build or
 * select a box), so the wheel itself stays six icons. Enter or a click takes the focused choice and closes the panel; Esc
 * closes it; the arrow keys and Tab move the focus.
 */
public final class ChoiceScreen extends Screen {
    /** One choice. {@code selected} marks the one that is on now (a radio); a choice that is not enabled is dim and does nothing. */
    public record Choice(String icon, String label, String desc, boolean enabled, boolean selected, Runnable run) {
    }

    /** A switch under the choices, changed in place without closing. */
    public record Toggle(String label, String desc, BooleanSupplier get, Consumer<Boolean> set) {
    }

    private static final int ROW_H = 40;

    private final String title, subtitle;
    private final List<Choice> choices;
    private final Toggle toggle;
    private final long openedNs = System.nanoTime();
    private int focus;
    private String down = "";

    public ChoiceScreen(String title, String subtitle, List<Choice> choices, Toggle toggle) {
        super(Component.literal(title));
        this.title = title;
        this.subtitle = subtitle;
        this.choices = choices;
        this.toggle = toggle;
        // the focus starts on what is selected, else on the first one that works
        int first = -1;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).selected) focus = i;
            if (first < 0 && choices.get(i).enabled) first = i;
        }
        if (!choices.get(focus).enabled && first >= 0) focus = first;
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

    private int pw() {
        return Math.min(width - 24, 300);
    }

    private int ph() {
        return 52 + choices.size() * (ROW_H + 4) + (toggle != null ? 30 : 6);
    }

    private int px() {
        return (width - pw()) / 2;
    }

    private int py() {
        return (height - ph()) / 2;
    }

    private int rowY(int i) {
        return py() + 40 + i * (ROW_H + 4);
    }

    private int toggleY() {
        return py() + 40 + choices.size() * (ROW_H + 4) + 2;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        double open = Motion.reduced() ? 1 : Math.min(1, (System.nanoTime() - openedNs) / 1e9 / 0.2);
        int px = px(), py = py(), pw = pw(), ph = ph();
        float inner = Ui.panelOpening(g, px, py, pw, ph, open);
        if (inner < 0.05f) return;
        double t = System.nanoTime() / 1e9;
        Ui.text(g, title, px + 10, py + 8, Ui.withAlpha(Ui.LINE, inner));
        int sy = py + 22;
        for (String line : Ui.wrap(subtitle, pw - 20, 1)) {
            Ui.text(g, line, px + 10, sy, Ui.withAlpha(Ui.DIM, inner));
            sy += 10;
        }
        for (int i = 0; i < choices.size(); i++) {
            Choice c = choices.get(i);
            int y = rowY(i), x = px + 8, w = pw - 16;
            boolean over = c.enabled && Ui.inside(mx, my, x, y, w, ROW_H);
            String key = "ch#" + i;
            float hv = Motion.hover(key, over), pr = Motion.press(key, over && down.equals("c" + i));
            Ui.blit(g, "button_0", x, y, w, ROW_H, inner);
            Ui.blit(g, "button_1", x, y, w, ROW_H, hv * inner);
            Ui.blit(g, "button_2", x, y, w, ROW_H, pr * inner);
            int sink = Math.round(pr);
            boolean dark = pr > 0.5f;
            Ui.icon(g, c.icon, x + 8, y + (ROW_H - 14) / 2 + sink, dark, 14, c.enabled ? inner : inner * 0.4f);
            int tc = !c.enabled ? 0xFF5E7C99 : dark ? Ui.DEEP : Ui.WHITE, dc = !c.enabled ? 0xFF4A6580 : dark ? Ui.NAVY : Ui.DIM;
            Ui.text(g, c.label, x + 30, y + 8 + sink, Ui.withAlpha(tc, inner));
            int dy = y + 20 + sink;
            for (String line : Ui.wrap(c.desc, w - 52, 2)) {
                Ui.text(g, line, x + 30, dy, Ui.withAlpha(dc, inner));
                dy += 9;
            }
            if (c.selected) {
                // the one that is on: a lit dot at the right
                Ui.icon(g, "check", x + w - 20, y + (ROW_H - 14) / 2, dark, 14, inner);
            }
            if (i == focus && c.enabled) Ui.marching(g, x - 2, y - 2, w + 4, ROW_H + 4, Ui.withAlpha(Ui.CYAN, inner), t);
        }
        if (toggle != null) {
            int y = toggleY(), x = px + 10;
            boolean on = toggle.get.getAsBoolean();
            boolean over = Ui.inside(mx, my, x, y, pw - 20, 24);
            Ui.checkbox(g, "ch#toggle", x, y + 1, on, over);
            Ui.text(g, toggle.label, x + 16, y + 1, Ui.withAlpha(over ? Ui.WHITE : Ui.LINE, inner));
            Ui.text(g, Ui.fit(toggle.desc, pw - 40), x + 16, y + 12, Ui.withAlpha(Ui.DIM, inner));
            if (focus == choices.size()) Ui.marching(g, x - 3, y - 3, pw - 14, 28, Ui.withAlpha(Ui.CYAN, inner), t);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        for (int i = 0; i < choices.size(); i++) {
            if (Ui.inside(mx, my, px() + 8, rowY(i), pw() - 16, ROW_H)) {
                if (choices.get(i).enabled) {
                    down = "c" + i;
                    focus = i;
                    Sfx.play(Sfx.PRESS);
                } else {
                    Sfx.play(Sfx.ERROR, 0.8f);
                }
                return true;
            }
        }
        if (toggle != null && Ui.inside(mx, my, px() + 10, toggleY(), pw() - 20, 24)) {
            flip();
            focus = choices.size();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mx = (int) event.x(), my = (int) event.y();
        String was = down;
        down = "";
        for (int i = 0; i < choices.size(); i++) {
            if (was.equals("c" + i) && Ui.inside(mx, my, px() + 8, rowY(i), pw() - 16, ROW_H)) {
                take(i);
                return true;
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int n = choices.size() + (toggle != null ? 1 : 0);
        if (event.isEscape()) {
            Sfx.play(Sfx.CLOSE);
            onClose();
            return true;
        }
        if (event.isLeft() || event.input() == InputConstants.KEY_UP) {
            focus = (focus + n - 1) % n;
            Sfx.play(Sfx.PRESS, 1.1f);
            return true;
        }
        if (event.isRight() || event.input() == InputConstants.KEY_DOWN || event.input() == InputConstants.KEY_TAB) {
            focus = (focus + 1) % n;
            Sfx.play(Sfx.PRESS, 1.1f);
            return true;
        }
        if (event.isConfirmation() || event.input() == InputConstants.KEY_SPACE) {
            if (focus < choices.size()) take(focus);
            else flip();
            return true;
        }
        return super.keyPressed(event);
    }

    private void flip() {
        toggle.set.accept(!toggle.get.getAsBoolean());
        Sfx.play(Sfx.PRESS, toggle.get.getAsBoolean() ? 1.2f : 0.9f);
    }

    private void take(int i) {
        Choice c = choices.get(i);
        if (!c.enabled) return;
        Sfx.play(Sfx.RELEASE);
        onClose();
        c.run.run();
    }

    // ---- for the demo

    /** Dev demo: the middle of a choice row, or of the switch under them (index = number of choices). */
    public int[] anchor(int i) {
        if (i >= choices.size()) return new int[]{px() + 14, toggleY() + 5};
        return new int[]{px() + pw() / 2, rowY(i) + ROW_H / 2};
    }

    public int count() {
        return choices.size();
    }

    public String heading() {
        return title;
    }
}
