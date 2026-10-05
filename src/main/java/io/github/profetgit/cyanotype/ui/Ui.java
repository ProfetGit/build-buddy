package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing the Cyanotype look: navy blueprint panels, white and cyan lines, from the sprites of dev/ui/out/B_final
 * (shipped by dev/ui/ship.py). Immediate mode: a screen calls these every frame with where the mouse is and what is
 * pressed, and the helpers ease each control between its looks (hover about 100 ms, press about 60 ms, a one-pixel spring
 * on release). Colours are the kit's.
 */
public final class Ui {
    public static final int WHITE = 0xFFFFFFFF, LINE = 0xFFE8F6FF, CYAN = 0xFF7FE3FF, DIM = 0xFF8BB6D8, NAVY = 0xFF0E2A47, DEEP = 0xFF0A1B30,
        GOOD = 0xFF6BE58F, WARN = 0xFFFFC857, BAD = 0xFFFF6B6B;

    private Ui() {
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static void blit(GuiGraphicsExtractor g, String sprite, int x, int y, int w, int h) {
        Skin.blit(g, sprite, x, y, w, h, 1f);
    }

    public static void blit(GuiGraphicsExtractor g, String sprite, int x, int y, int w, int h, float alpha) {
        if (alpha <= 0.003f) return;
        Skin.blit(g, sprite, x, y, w, h, alpha);
    }

    /** A sprite multiplied by a colour (ARGB). */
    public static void blitTint(GuiGraphicsExtractor g, String sprite, int x, int y, int w, int h, int color) {
        Skin.blit(g, sprite, x, y, w, h, color);
    }

    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        blit(g, "panel", x, y, w, h);
        Skin.grid(g, x + 1, y + 1, w - 2, h - 2, 1f);
    }

    public static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        blit(g, "inset", x, y, w, h);
    }

    /**
     * A panel that draws itself on (PRD 6b): the frame grows from the middle outward, quickly at first, and its contents
     * fade in once it is mostly there.
     *
     * @param progress 0 closed, 1 open
     * @return how visible the contents should be, 0 to 1
     */
    public static float panelOpening(GuiGraphicsExtractor g, int x, int y, int w, int h, double progress) {
        if (progress >= 1.0 || Motion.reduced()) {
            panel(g, x, y, w, h);
            return 1f;
        }
        double e = Motion.easeOut(progress);
        int cw = Math.max(8, (int) (w * e)), ch = Math.max(8, (int) (h * Math.min(1, e * 1.25)));
        panel(g, x + (w - cw) / 2, y + (h - ch) / 2, cw, ch);
        return (float) Math.max(0, (progress - 0.55) / 0.45);
    }

    // ---- text

    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(font(), s, x, y, color, false);
    }

    public static void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        g.text(font(), s, cx - font().width(s) / 2, y, color, false);
    }

    public static void right(GuiGraphicsExtractor g, String s, int rx, int y, int color) {
        g.text(font(), s, rx - font().width(s), y, color, false);
    }

    /** A string cut to fit a width, with an ellipsis. */
    public static String fit(String s, int width) {
        Font f = font();
        if (f.width(s) <= width) return s;
        String ell = "...";
        int n = s.length();
        while (n > 1 && f.width(s.substring(0, n) + ell) > width) n--;
        return s.substring(0, Math.max(1, n)) + ell;
    }

    /** A string broken at spaces into at most {@code maxLines} lines of a width; the last line gets an ellipsis if the rest does not fit. */
    public static java.util.List<String> wrap(String s, int width, int maxLines) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String rest = s.trim();
        while (!rest.isEmpty()) {
            if (out.size() + 1 == maxLines) {
                out.add(fit(rest, width));
                break;
            }
            if (font().width(rest) <= width) {
                out.add(rest);
                break;
            }
            int cut = rest.length();
            while (cut > 1 && font().width(rest.substring(0, cut)) > width) cut--;
            int space = rest.lastIndexOf(' ', cut);
            if (space > 0) cut = space;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        return out;
    }

    /** A tooltip of several lines (a newline in one Component draws as a box glyph, so each line is its own). */
    public static void tooltip(GuiGraphicsExtractor g, int mx, int my, String text) {
        java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
        for (String line : text.split("\n")) lines.add(net.minecraft.network.chat.Component.literal(line));
        g.setTooltipForNextFrame(font(), lines, java.util.Optional.empty(), mx, my);
    }

    // ---- controls

    public static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    /**
     * A button. The caller owns the click: it passes whether the mouse is over it and whether it is held down, and acts on
     * the release. Eased between the three looks of the kit; the label sinks a pixel when pressed and springs back.
     *
     * @return whether the mouse is over it
     */
    public static boolean button(GuiGraphicsExtractor g, String key, int x, int y, int w, int h, String label, String icon, int mx, int my, boolean down, boolean enabled) {
        boolean over = enabled && inside(mx, my, x, y, w, h);
        float hv = Motion.hover(key, over), pr = Motion.press(key, over && down);
        blit(g, "button_0", x, y, w, h);
        blit(g, "button_1", x, y, w, h, hv);
        blit(g, "button_2", x, y, w, h, pr);
        int sink = Math.round(pr);
        int tx = x + w / 2, ty = y + (h - 8) / 2 + sink;
        int color = !enabled ? 0xFF5E7C99 : pr > 0.5f ? DEEP : LINE;
        if (icon != null) {
            int total = font().width(label == null ? "" : label) + 16 + (label == null || label.isEmpty() ? 0 : 4);
            int ix = x + (w - total) / 2;
            icon(g, icon, ix, y + (h - 16) / 2 + sink, pr > 0.5f, 16, 1f);
            if (label != null && !label.isEmpty()) text(g, label, ix + 20, ty, color);
        } else if (label != null) {
            centered(g, label, tx, ty, color);
        }
        return over;
    }

    /** Dev demo only: where the mouse counts as being (null = the real mouse). */
    public static volatile int[] testMouse;

    public static int mx(int real) {
        int[] t = testMouse;
        return t != null ? t[0] : real;
    }

    public static int my(int real) {
        int[] t = testMouse;
        return t != null ? t[1] : real;
    }

    /**
     * An icon in a box of {@code size} units: the 16 x 16 pixel icon, drawn square and crisp, centred in the box at 16 units (or
     * 32 for a box over 20), so a pixel is a whole number of screen pixels. {@code dark} is no longer a separate look: the
     * icons are white with a dark outline and read on the dark and on the lit wedge alike.
     */
    public static void icon(GuiGraphicsExtractor g, String name, int x, int y, boolean dark, int size, float alpha) {
        int drawn = size <= 20 ? 16 : 32;
        int off = Math.floorDiv(size - drawn, 2);
        Skin.pixel(g, name, x + off, y + off, drawn, drawn, alpha);
    }

    /** A pixel icon in the usual 14 unit box. */
    public static void icon(GuiGraphicsExtractor g, String name, int x, int y, boolean dark) {
        icon(g, name, x, y, dark, 14, 1f);
    }

    public static void tab(GuiGraphicsExtractor g, String key, int x, int y, int w, String label, boolean selected, int mx, int my) {
        boolean over = inside(mx, my, x, y, w, 13);
        float hv = Motion.hover(key, over && !selected);
        blit(g, selected ? "tab_on" : "tab_off", x, y, w, 13);
        centered(g, label, x + w / 2, y + 3, selected ? DEEP : mixColor(DIM, WHITE, hv));
    }

    public static void checkbox(GuiGraphicsExtractor g, String key, int x, int y, boolean on, boolean over) {
        float hv = Motion.hover(key, over);
        blit(g, on ? "checkbox_on" : "checkbox_off", x, y, 10, 10);
        if (hv > 0.02f) blit(g, "checkbox_hover", x - 1, y - 1, 12, 12, hv);
    }

    /**
     * A progress bar: the kit's track with a fill whose hatch drifts slowly along it (PRD 6b); the value eases to where it
     * is meant to be.
     */
    public static void bar(GuiGraphicsExtractor g, String key, int x, int y, int w, int h, double value, double seconds) {
        blit(g, "bar_track", x, y, w, h);
        float v = Motion.follow(key + "#bar", (float) Math.max(0, Math.min(1, value)), 0.18);
        int fw = (int) Math.round((w - 4) * v);
        if (fw <= 0) return;
        // the hatch drifts along the fill, a little over a unit a second
        Skin.hatch(g, x + 2, y + 2, fw, h - 4, Motion.reduced() ? 0 : -seconds * 1.5);
    }

    /**
     * A slider: the kit's track filled with the hatch up to the knob. The knob sits exactly at the value (no easing while
     * it is dragged); it brightens on hover and while held.
     *
     * @param value 0 to 1
     */
    public static void slider(GuiGraphicsExtractor g, String key, int x, int y, int w, int h, double value, boolean hover, boolean held, double seconds) {
        blit(g, "bar_track", x, y, w, h);
        double v = Math.max(0, Math.min(1, value));
        int knobX = x + 2 + (int) Math.round((w - 4 - 5) * v);
        int fw = knobX - (x + 2) + 2;
        if (fw > 0) Skin.hatch(g, x + 2, y + 2, fw, h - 4, Motion.reduced() ? 0 : -seconds * 1.5);
        float hv = Motion.hover(key, hover || held), pr = Motion.press(key, held);
        int kh = h + 4, ky = y - 2;
        blitTint(g, "knob", knobX, ky, 5, kh, 0xFF000000 | mixColor(mixColor(LINE, WHITE, hv), CYAN, pr) & 0xFFFFFF);
    }

    /** Marching dashes around a rectangle: what is selected. */
    public static void marching(GuiGraphicsExtractor g, int x, int y, int w, int h, int color, double seconds) {
        int off = Motion.reduced() ? 0 : (int) (seconds * 10) % 6;
        for (int i = -off; i < w; i += 6) {
            int a = Math.max(0, i), b = Math.min(w, i + 3);
            if (b > a) {
                g.fill(x + a, y, x + b, y + 1, color);
                g.fill(x + w - b, y + h - 1, x + w - a, y + h, color);
            }
        }
        for (int i = -off; i < h; i += 6) {
            int a = Math.max(0, i), b = Math.min(h, i + 3);
            if (b > a) {
                g.fill(x, y + h - b, x + 1, y + h - a, color);
                g.fill(x + w - 1, y + a, x + w, y + b, color);
            }
        }
    }

    public static int mixColor(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int gr = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        int al = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        return al << 24 | r << 16 | gr << 8 | bl;
    }

    public static int withAlpha(int rgb, float alpha) {
        return ((int) (Math.max(0, Math.min(1, alpha)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    // ---- keys

    /** The name of the key a mapping is bound to, as the player sees it. */
    public static String keyName(net.minecraft.client.KeyMapping k) {
        return k.getTranslatedKeyMessage().getString();
    }

    public static boolean keyDown(int glfwKey) {
        return InputConstants.isKeyDown(glfwKey);
    }
}
