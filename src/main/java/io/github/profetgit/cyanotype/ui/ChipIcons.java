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

    static final int MOUSE_W = 10, MOUSE_H = 14, WHEEL_W = 13, CAP_H = 13, DRAG_W = 9, DRAG_H = 7, GAP = 4;

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
            case MOUSE_WHEEL -> WHEEL_W;
            case KEYCAP -> Ui.font().width(p.text) + 10;
            case TEXT -> Ui.font().width(p.text);
            case PLUS, SLASH -> Ui.font().width(p.text);
        };
    }

    private static int markWidth(Part p) {
        if (p.mark.isEmpty()) return 0;
        return p.mark.equals("drag") ? DRAG_W + 1 : Ui.font().width(p.mark) + 2;
    }

    /** Draws the key part with its top left corner at (x, y) in a chip row 16 units tall; {@code a} is the opacity. */
    public static void draw(GuiGraphicsExtractor g, String key, int x, int y, float a) {
        int cx = x;
        for (Part p : parse(key)) {
            switch (p.kind) {
                case MOUSE_LEFT -> {
                    Ui.blit(g, "mouse_left", cx, y + 1, MOUSE_W, MOUSE_H, a);
                    cx += MOUSE_W;
                    cx += mark(g, p, cx, y, a);
                }
                case MOUSE_RIGHT -> {
                    Ui.blit(g, "mouse_right", cx, y + 1, MOUSE_W, MOUSE_H, a);
                    cx += MOUSE_W;
                    cx += mark(g, p, cx, y, a);
                }
                case MOUSE_WHEEL -> {
                    Ui.blit(g, "mouse_wheel", cx, y + 1, WHEEL_W, MOUSE_H, a);
                    cx += WHEEL_W;
                }
                case KEYCAP -> {
                    int w = Ui.font().width(p.text) + 10;
                    Ui.blit(g, "keycap", cx, y + 1, w, CAP_H, a);
                    Ui.text(g, p.text, cx + 5, y + 4, Ui.withAlpha(Ui.WHITE, a));
                    cx += w;
                }
                case TEXT -> {
                    Ui.text(g, p.text, cx, y + 4, Ui.withAlpha(Ui.CYAN, a));
                    cx += Ui.font().width(p.text);
                }
                case PLUS, SLASH -> {
                    Ui.text(g, p.text, cx, y + 4, Ui.withAlpha(Ui.DIM, a));
                    cx += Ui.font().width(p.text);
                }
            }
            cx += sep(p);
        }
    }

    /** The small mark after a mouse: drag (a trail and an arrow), twice (x2) or hold. @return its width */
    private static int mark(GuiGraphicsExtractor g, Part p, int x, int y, float a) {
        if (p.mark.isEmpty()) return 0;
        if (p.mark.equals("drag")) {
            Ui.blit(g, "mark_drag", x + 1, y + 4, DRAG_W, DRAG_H, a);
            return DRAG_W + 1;
        }
        Ui.text(g, p.mark, x + 2, y + 4, Ui.withAlpha(Ui.DIM, a));
        return Ui.font().width(p.mark) + 2;
    }
}
