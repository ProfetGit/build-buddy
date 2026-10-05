package io.github.profetgit.cyanotype.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws the key part of a cursor chip as pictures: a small mouse with the pressed button lit, key caps for Shift, Ctrl and
 * the keys of the mod. The chip's key text ("Shift+Click", "Right click", "Ctrl+Z / Y") says what to draw; anything that is
 * not a known mouse action or a short key name stays text. Pure parsing (tested) and plain rectangles, so it needs no art.
 */
public final class ChipIcons {
    public enum Kind {
        MOUSE_LEFT, MOUSE_RIGHT, MOUSE_WHEEL, KEYCAP, TEXT, PLUS, SLASH
    }

    /** One piece of the key: what to draw, the words if it has any, and a small mark after a mouse (drag, twice, hold). */
    public record Part(Kind kind, String text, String mark) {
    }

    static final int MOUSE_W = 9, MOUSE_H = 12, CAP_H = 11, GAP = 3;

    private ChipIcons() {
    }

    /** Splits a chip's key text into the pieces drawn one after another. */
    public static List<Part> parse(String key) {
        List<Part> out = new ArrayList<>();
        String[] plus = key.split("\\+", -1);
        for (int i = 0; i < plus.length; i++) {
            String t = plus[i].trim();
            if (i > 0) out.add(new Part(Kind.PLUS, "+", ""));
            if (t.isEmpty()) continue;
            switch (t) {
                case "Click" -> out.add(new Part(Kind.MOUSE_LEFT, "", ""));
                case "Click flip" -> out.add(new Part(Kind.MOUSE_LEFT, "", ""));
                case "Click twice" -> out.add(new Part(Kind.MOUSE_LEFT, "", "x2"));
                case "Right click" -> out.add(new Part(Kind.MOUSE_RIGHT, "", ""));
                case "Hold use" -> out.add(new Part(Kind.MOUSE_RIGHT, "", "hold"));
                case "Scroll" -> out.add(new Part(Kind.MOUSE_WHEEL, "", ""));
                case "Drag", "Drag arrow", "Drag ring" -> out.add(new Part(Kind.MOUSE_LEFT, "", "drag"));
                default -> {
                    // "Z / Y": two keys with a slash between
                    if (t.contains(" / ") && t.length() <= 20) {
                        String[] keys = t.split(" / ");
                        for (int k = 0; k < keys.length; k++) {
                            if (k > 0) out.add(new Part(Kind.SLASH, "/", ""));
                            out.add(cap(keys[k].trim()));
                        }
                    } else {
                        out.add(cap(t));
                    }
                }
            }
        }
        return out;
    }

    /** A short word that is a key name becomes a key cap; a sentence stays text. */
    private static Part cap(String t) {
        boolean keyLike = t.length() <= 8 && !t.contains(" ") && !t.equals("Release") && !t.equals("Water");
        return new Part(keyLike ? Kind.KEYCAP : Kind.TEXT, t, "");
    }

    /** How wide the key part is when drawn, in GUI units. */
    public static int width(String key) {
        List<Part> parts = parse(key);
        int w = 0;
        for (int i = 0; i < parts.size(); i++) {
            w += widthOf(parts.get(i));
            if (i < parts.size() - 1) w += sep(parts.get(i));
        }
        return w;
    }

    /** The space after a piece: a little less around the + and the slash. */
    private static int sep(Part p) {
        return p.kind == Kind.PLUS || p.kind == Kind.SLASH ? 2 : GAP;
    }

    private static int widthOf(Part p) {
        return switch (p.kind) {
            case MOUSE_LEFT, MOUSE_RIGHT -> MOUSE_W + markWidth(p);
            case MOUSE_WHEEL -> MOUSE_W + 5;
            case KEYCAP -> Ui.font().width(p.text) + 6;
            case TEXT -> Ui.font().width(p.text);
            case PLUS, SLASH -> Ui.font().width(p.text);
        };
    }

    private static int markWidth(Part p) {
        if (p.mark.isEmpty()) return 0;
        return p.mark.equals("drag") ? 7 : Ui.font().width(p.mark) + 2;
    }

    /** Draws the key part with its top left corner at (x, y) in a chip row 13 units tall; {@code a} is the opacity. */
    public static void draw(GuiGraphicsExtractor g, String key, int x, int y, float a) {
        int cx = x;
        for (Part p : parse(key)) {
            switch (p.kind) {
                case MOUSE_LEFT -> {
                    mouse(g, cx, y + 1, true, false, false, a);
                    cx += MOUSE_W;
                    cx += mark(g, p, cx, y, a);
                }
                case MOUSE_RIGHT -> {
                    mouse(g, cx, y + 1, false, true, false, a);
                    cx += MOUSE_W;
                    cx += mark(g, p, cx, y, a);
                }
                case MOUSE_WHEEL -> {
                    mouse(g, cx, y + 1, false, false, true, a);
                    cx += MOUSE_W;
                    // the wheel turns both ways
                    int ax = cx + 2;
                    int col = Ui.withAlpha(Ui.CYAN, a);
                    g.fill(ax + 1, y + 2, ax + 2, y + 3, col);
                    g.fill(ax, y + 3, ax + 3, y + 4, col);
                    g.fill(ax, y + 9, ax + 3, y + 10, col);
                    g.fill(ax + 1, y + 10, ax + 2, y + 11, col);
                    cx += 5;
                }
                case KEYCAP -> {
                    int w = Ui.font().width(p.text) + 6;
                    keycap(g, cx, y + 1, w, p.text, a);
                    cx += w;
                }
                case TEXT -> {
                    Ui.text(g, p.text, cx, y + 3, Ui.withAlpha(Ui.CYAN, a));
                    cx += Ui.font().width(p.text);
                }
                case PLUS, SLASH -> {
                    Ui.text(g, p.text, cx, y + 3, Ui.withAlpha(Ui.DIM, a));
                    cx += Ui.font().width(p.text);
                }
            }
            cx += sep(p);
        }
    }

    /** A mouse: a body with the buttons across the top; the one that is used is lit. */
    private static void mouse(GuiGraphicsExtractor g, int x, int y, boolean left, boolean right, boolean wheel, float a) {
        int line = Ui.withAlpha(Ui.DIM, a), body = Ui.withAlpha(Ui.DEEP, a), lit = Ui.withAlpha(Ui.CYAN, a), hot = Ui.withAlpha(Ui.WHITE, a);
        // the body, with its corners cut
        g.fill(x + 1, y, x + MOUSE_W - 1, y + 1, line);
        g.fill(x + 1, y + MOUSE_H - 1, x + MOUSE_W - 1, y + MOUSE_H, line);
        g.fill(x, y + 1, x + 1, y + MOUSE_H - 1, line);
        g.fill(x + MOUSE_W - 1, y + 1, x + MOUSE_W, y + MOUSE_H - 1, line);
        g.fill(x + 1, y + 1, x + MOUSE_W - 1, y + MOUSE_H - 1, body);
        // the buttons: left 3 wide, the wheel 1, right 3, 5 tall
        if (left) g.fill(x + 1, y + 1, x + 4, y + 6, lit);
        if (right) g.fill(x + 5, y + 1, x + 8, y + 6, lit);
        g.fill(x + 4, y + 1, x + 5, y + 6, wheel ? hot : line);
        g.fill(x + 1, y + 6, x + MOUSE_W - 1, y + 7, line);
    }

    private static void keycap(GuiGraphicsExtractor g, int x, int y, int w, String text, float a) {
        int line = Ui.withAlpha(Ui.DIM, a);
        g.fill(x + 1, y, x + w - 1, y + 1, line);
        g.fill(x + 1, y + CAP_H - 1, x + w - 1, y + CAP_H, line);
        g.fill(x, y + 1, x + 1, y + CAP_H - 1, line);
        g.fill(x + w - 1, y + 1, x + w, y + CAP_H - 1, line);
        g.fill(x + 1, y + 1, x + w - 1, y + CAP_H - 1, Ui.withAlpha(Ui.DEEP, a));
        // a lighter top edge, like the top of a key
        g.fill(x + 2, y + 1, x + w - 2, y + 2, Ui.withAlpha(Ui.NAVY, a));
        Ui.text(g, text, x + 3, y + 2, Ui.withAlpha(Ui.CYAN, a));
    }

    /** The small mark after a mouse: drag (an arrow), twice (x2) or hold. @return its width */
    private static int mark(GuiGraphicsExtractor g, Part p, int x, int y, float a) {
        if (p.mark.isEmpty()) return 0;
        int col = Ui.withAlpha(Ui.CYAN, a);
        if (p.mark.equals("drag")) {
            // a short arrow pointing right
            g.fill(x + 2, y + 6, x + 6, y + 7, col);
            g.fill(x + 5, y + 5, x + 6, y + 8, col);
            g.fill(x + 6, y + 6, x + 7, y + 7, col);
            return 7;
        }
        Ui.text(g, p.mark, x + 2, y + 3, Ui.withAlpha(Ui.DIM, a));
        return Ui.font().width(p.mark) + 2;
    }
}
