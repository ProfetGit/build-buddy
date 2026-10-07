package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.cyanotype.interaction.Interaction;
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
            if (hovered >= 0) {
                Sfx.play(Sfx.ERROR);
                Interaction.say(minecraft, tools[hovered].why(minecraft));
            }
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

    /** The radius of the hub (the middle of the wheel, which is not a tool). */
    private double hubRadius() {
        return WheelArt.hub(height >= 270 ? 160 : 88);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (chosen >= 0) return true;
        double dx = event.x() - width / 2.0, dy = event.y() - height / 2.0;
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) {
            // a right click on a tool shows how it works
            if (hovered >= 0) {
                Sfx.play(Sfx.RELEASE);
                if (!io.github.profetgit.cyanotype.ponder.Lessons.open(minecraft, tools[hovered].lesson)) Sfx.play(Sfx.ERROR);
            }
            return true;
        }
        if (hovered < 0 && Math.hypot(dx, dy) < hubRadius()) {
            // the middle of the wheel is the ?: the list of every lesson
            Sfx.play(Sfx.RELEASE);
            minecraft.gui.setScreen(new io.github.profetgit.cyanotype.ponder.HelpScreen(null));
            return true;
        }
        // clicking a segment chooses it too, for those who would rather not hold a key
        if (hovered >= 0) release();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        int cx = width / 2, cy = height / 2;
        int scale = height >= 270 ? 2 : 1;
        // the ring is wider than the icon in it by a margin on each side: 16 units of icon in a 24 wide ring, 32 in a 44 wide one
        int size = scale == 2 ? 160 : 88;
        int n = tools.length;
        WheelArt.ensure(n, size);
        double inner = WheelArt.hub(size), outer = size / 2.0 + 90, rim = WheelArt.outer(size);
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
        // a tool that cannot be used now gets only a faint wedge: it is pointed at, not offered
        float wedgeA = Motion.follow("wheel#wedge", hovered >= 0 ? (tools[hovered].enabled(minecraft) ? 1f : 0.35f) : 0f, 0.06) * alpha;
        if (wedgeA > 0.02f) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().rotate(wedgeAngle);
            WheelArt.drawWedge(g, -size / 2, -size / 2, size, wedgeA);
            g.pose().popMatrix();
        }
        // icons sit in the middle of their segments, at a whole 16 or 32 so the pixels stay square; the big one only when
        // the ring is wide enough to leave a margin and a neighbour is not closer than the icon
        double ringR = (inner + rim) / 2, arc = 2 * Math.PI * ringR / n;
        int box = (rim - inner) >= 40 && arc >= 38 ? 32 : 16;
        for (int i = 0; i < n; i++) {
            Tool t = tools[i];
            boolean on = t.enabled(minecraft);
            boolean hot = i == hovered;
            boolean pulse = i == chosen;
            double pulseK = pulse && !Motion.reduced() ? 1 + 0.35 * Math.sin(Math.min(1, (System.nanoTime() - chosenNs) / 150e6) * Math.PI) : 1;
            double[] at = WheelGeometry.pointAt(i, n, ringR);
            int ix = (int) Math.round(cx + at[0]), iy = (int) Math.round(cy + at[1]);
            float lift = Motion.follow("wheel#icon" + i, hot ? 1f : 0f, 0.07);
            // no scaling (it would smear the pixels): the hovered icon lifts a pixel, the chosen one hops
            int hop = (int) Math.round(lift * (box / 16) + (pulseK - 1) * 10);
            boolean dark = hot && on;
            Ui.icon(g, t.icon, ix - box / 2, iy - box / 2 - hop, dark, box, on ? alpha : alpha * 0.4f);
        }
        // the middle names the segment; what it does goes under the wheel
        if (hovered >= 0) {
            Tool t = tools[hovered];
            Ui.centered(g, Ui.fit(t.label, (int) (inner * 1.8)), cx, cy - 4, Ui.withAlpha(t.enabled(minecraft) ? Ui.WHITE : Ui.DIM, alpha));
            String why = t.why(minecraft);
            String hint = why.isEmpty() ? t.hint : why;
            int hw = Ui.font().width(hint);
            int hy = cy + size / 2 + 26 * scale;
            g.fill(cx - hw / 2 - 4, hy - 2, cx + hw / 2 + 4, hy + 11, Ui.withAlpha(Ui.DEEP, alpha * 0.72f));
            Ui.centered(g, hint, cx, hy, Ui.withAlpha(why.isEmpty() ? Ui.CYAN : Ui.WARN, alpha));
            String how = "Right click: how it works";
            Ui.centered(g, how, cx, hy + 13, Ui.withAlpha(Ui.DIM, alpha * 0.9f));
        } else {
            int hy = cy + size / 2 + 26 * scale;
            boolean overHub = Math.hypot(mx - cx, my - cy) < inner;
            String hint = overHub ? "Click for the lessons: how every tool works" : "Move to a tool, let go to choose";
            int hw = Ui.font().width(hint);
            g.fill(cx - hw / 2 - 4, hy - 2, cx + hw / 2 + 4, hy + 11, Ui.withAlpha(Ui.DEEP, alpha * 0.6f));
            Ui.centered(g, hint, cx, hy, Ui.withAlpha(overHub ? Ui.CYAN : Ui.DIM, alpha));
            // the ? in the middle
            Ui.icon(g, "help", cx - 8, cy - 8, false, 16, alpha * (overHub ? 1f : 0.7f));
        }
        g.pose().popMatrix();
    }
}
