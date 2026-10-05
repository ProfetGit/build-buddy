package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.cyanotype.interaction.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The tool wheel: hold the tool key, move toward a segment, let go. A wedge slides to the segment under the mouse with a
 * spring and the chosen one pulses once; the world stays visible behind it and the game does not pause. Letting go in the
 * middle (or Esc) closes it without choosing.
 */
public final class WheelScreen extends Screen {
    /** Dev demo only: whether the tool key counts as held, and where the mouse is (null = the real ones). */
    public static volatile Boolean testHeld;

    /** Dev demo only: a different set of tools, to see the wheel with other counts. */
    public static volatile Tool[] testTools;

    private final Tool[] tools = testTools != null ? testTools : Tool.values();
    private int hovered = -1;
    private int chosen = -1;
    private long openedNs = System.nanoTime(), chosenNs;
    private float wedgeAngle;
    private boolean wedgeVisible;

    public WheelScreen() {
        super(Component.literal("Cyanotype tools"));
    }

    /** Dev demo: how many tools the wheel is cut for. */
    public int toolCount() {
        return tools.length;
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
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        // the world stays as it is; only a soft shade toward the edges so the wheel reads
        g.fillGradient(0, 0, width, height, 0x22000000, 0x55000000);
    }

    private boolean held() {
        Boolean t = testHeld;
        if (t != null) return t;
        InputConstants.Key key = InputConstants.getKey(Keys.MAIN.saveString());
        // a tool key bound to a mouse button cannot be polled here: the wheel then stays until a segment is clicked
        return key.getType() == InputConstants.Type.MOUSE || InputConstants.isKeyDown(key.getValue());
    }

    @Override
    public void tick() {
        if (chosen >= 0) {
            long wait = Motion.reduced() ? 0 : 150_000_000L;
            if (System.nanoTime() - chosenNs >= wait) finish();
            return;
        }
        // a hold shorter than a moment is not a choice
        if (!held() && System.nanoTime() - openedNs > 60_000_000L) release();
    }

    private void release() {
        if (hovered >= 0 && tools[hovered].enabled(minecraft)) {
            chosen = hovered;
            chosenNs = System.nanoTime();
            Sfx.play(Sfx.PRESS);
        } else {
            if (hovered >= 0) Sfx.play(Sfx.ERROR);
            Sfx.play(Sfx.CLOSE);
            onClose();
        }
    }

    private void finish() {
        Tool t = tools[chosen];
        onClose();
        t.run(minecraft);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // clicking a segment chooses it too, for those who would rather not hold a key
        if (chosen < 0 && hovered >= 0) release();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        int cx = width / 2, cy = height / 2;
        int scale = height >= 270 ? 2 : 1;
        int size = 64 * scale;
        int n = tools.length;
        WheelArt.ensure(n, size);
        double inner = WheelArt.hub(size), outer = 34 * scale + 90, rim = WheelArt.outer(size);
        if (chosen < 0) {
            int now = WheelGeometry.segmentAt(mx - cx, my - cy, inner, outer, n);
            if (now != hovered) {
                hovered = now;
                if (now >= 0) Sfx.play(Sfx.WHEEL_TICK, 0.9f + 0.05f * now);
            }
        }
        double open = Motion.reduced() ? 1 : Math.min(1, (System.nanoTime() - openedNs) / 1e9 / 0.18);
        float alpha = (float) Motion.easeOut(open);
        float s = (float) (0.85 + 0.15 * Motion.easeOut(open));

        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(s, s);
        g.pose().translate(-cx, -cy);

        WheelArt.drawBase(g, cx - size / 2, cy - size / 2, size, alpha);
        // the wedge follows the hovered segment with a spring-like ease, going the short way round
        if (hovered >= 0) {
            float target = (float) WheelGeometry.center(hovered, n);
            if (!wedgeVisible) {
                wedgeAngle = target;
                wedgeVisible = true;
            }
            float d = (float) Math.atan2(Math.sin(target - wedgeAngle), Math.cos(target - wedgeAngle));
            wedgeAngle += Motion.reduced() ? d : d * (float) (1 - Math.exp(-Motion.dt() / 0.045));
        } else {
            wedgeVisible = false;
        }
        float wedgeA = Motion.follow("wheel#wedge", hovered >= 0 ? 1f : 0f, 0.06) * alpha;
        if (wedgeA > 0.02f) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().rotate(wedgeAngle);
            WheelArt.drawWedge(g, -size / 2, -size / 2, size, wedgeA);
            g.pose().popMatrix();
        }
        // icons sit in the middle of their segments; they shrink when many tools share the ring
        double ringR = (inner + rim) / 2, arc = 2 * Math.PI * ringR / n;
        double iconHalf = Math.min(7 * scale, Math.min(arc * 0.34, (rim - inner) * 0.4));
        for (int i = 0; i < n; i++) {
            Tool t = tools[i];
            boolean on = t.enabled(minecraft);
            boolean hot = i == hovered;
            boolean pulse = i == chosen;
            double pulseK = pulse && !Motion.reduced() ? 1 + 0.35 * Math.sin(Math.min(1, (System.nanoTime() - chosenNs) / 150e6) * Math.PI) : 1;
            double[] at = WheelGeometry.pointAt(i, n, ringR);
            int ix = (int) Math.round(cx + at[0]), iy = (int) Math.round(cy + at[1]);
            float lift = Motion.follow("wheel#icon" + i, hot ? 1f : 0f, 0.07);
            int isz = (int) Math.round(iconHalf * (1 + 0.12 * lift) * pulseK);
            // dark icons on the lit wedge, light ones on the base
            boolean dark = hot && on;
            Ui.icon(g, t.icon, ix - isz, iy - isz, dark, isz * 2, on ? alpha : alpha * 0.4f);
            // the name outside the wheel
            double ang = WheelGeometry.center(i, n) - Math.PI / 2;
            int lx = (int) Math.round(cx + Math.cos(ang) * (size / 2.0 + 12 * scale)), ly = (int) Math.round(cy + Math.sin(ang) * (size / 2.0 + 10 * scale));
            int col = !on ? 0xFF5E7C99 : hot ? Ui.WHITE : Ui.DIM;
            String label = t.label;
            int tw = Ui.font().width(label);
            // a navy chip behind the name so it reads over sky and grass alike
            int chipX = Math.cos(ang) > 0.3 ? lx - 6 : Math.cos(ang) < -0.3 ? lx + 6 - tw - 4 : lx - tw / 2 - 2;
            int chipY = ly - 6 + (Math.abs(Math.cos(ang)) <= 0.3 ? (Math.sin(ang) > 0 ? 3 : -3) : 0);
            g.fill(chipX, chipY, chipX + tw + 4, chipY + 12, Ui.withAlpha(Ui.DEEP, alpha * 0.72f));
            Ui.text(g, label, chipX + 2, chipY + 2, Ui.withAlpha(col, alpha));
        }
        // the middle names the segment; what it does goes under the wheel
        if (hovered >= 0) {
            Tool t = tools[hovered];
            Ui.centered(g, Ui.fit(t.label, (int) (inner * 1.8)), cx, cy - 4, Ui.withAlpha(t.enabled(minecraft) ? Ui.WHITE : Ui.DIM, alpha));
            String hint = t.hint;
            int hw = Ui.font().width(hint);
            int hy = cy + size / 2 + 26 * scale;
            g.fill(cx - hw / 2 - 4, hy - 2, cx + hw / 2 + 4, hy + 11, Ui.withAlpha(Ui.DEEP, alpha * 0.72f));
            Ui.centered(g, hint, cx, hy, Ui.withAlpha(t.enabled(minecraft) ? Ui.CYAN : Ui.DIM, alpha));
        } else {
            int hy = cy + size / 2 + 26 * scale;
            String hint = "Move to a tool, let go to choose";
            int hw = Ui.font().width(hint);
            g.fill(cx - hw / 2 - 4, hy - 2, cx + hw / 2 + 4, hy + 11, Ui.withAlpha(Ui.DEEP, alpha * 0.6f));
            Ui.centered(g, hint, cx, hy, Ui.withAlpha(Ui.DIM, alpha));
        }
        g.pose().popMatrix();
    }
}
