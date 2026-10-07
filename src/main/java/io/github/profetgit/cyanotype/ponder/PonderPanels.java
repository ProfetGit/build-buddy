package io.github.profetgit.cyanotype.ponder;

import io.github.profetgit.cyanotype.ui.Motion;
import io.github.profetgit.cyanotype.ui.Skin;
import io.github.profetgit.cyanotype.ui.Ui;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The mock screens a lesson lays over its picture (the Library, the Save screen, the Materials list, the server warning, the
 * progress bar): drawn with the same sprites as the real ones, by hand, so they are close in spirit and never out of sync in
 * mechanics, since nothing in them is clickable. Positions are fractions of the picture's rectangle.
 */
final class PonderPanels {
    private PonderPanels() {
    }

    static void draw(GuiGraphicsExtractor g, List<Snapshot.ItemView> panels, int vx, int vy, int vw, int vh) {
        for (Snapshot.ItemView p : panels) {
            double[] r = p.vec("rect", defaultRect(p.type()));
            int x = vx + (int) Math.round(r[0] * vw), y = vy + (int) Math.round(r[1] * vh), w = (int) Math.round(r[2] * vw), h = (int) Math.round(r[3] * vh);
            float a = (float) p.alpha;
            switch (p.type()) {
                case "library" -> library(g, p, x, y, w, h, a);
                case "save" -> save(g, p, x, y, w, h, a);
                case "list" -> list(g, p, x, y, w, h, a);
                case "warning" -> warning(g, p, x, y, w, h, a);
                case "bar" -> bar(g, p, x, y, w, h, a);
                case "stamp" -> stamp(g, p, x, y, w, h, a);
                case "badge" -> {
                    // a HUD badge keeps the size of the real one (it must hold its words), at the corner the lesson names
                    int bw = Math.max(w, 176), bh = Math.max(h, 29);
                    badge(g, p, Math.min(x, vx + vw - bw - 4), y, bw, bh, a);
                }
                default -> {
                }
            }
        }
    }

    /**
     * Where a cursor points when it points at a panel: the middle of a row, card or button of the panel of that type (in the
     * snapshot when it is showing, else the first one of that type in the lesson). Absolute GUI units, or null when the lesson has no
     * such panel.
     */
    static int @org.jspecify.annotations.Nullable [] anchor(Scene scene, Snapshot snap, String type, int row, int vx, int vy, int vw, int vh) {
        Scene.Item item = null;
        for (Snapshot.ItemView p : snap.panels) if (p.type().equals(type)) item = p.item;
        if (item == null) for (Scene.Item i : scene.panels) if (i.type().equals(type)) {
            item = i;
            break;
        }
        if (item == null) return null;
        Snapshot.ItemView p = new Snapshot.ItemView(item, snap.t, 1);
        double[] r = p.vec("rect", defaultRect(type));
        int x = vx + (int) Math.round(r[0] * vw), y = vy + (int) Math.round(r[1] * vh), w = (int) Math.round(r[2] * vw), h = (int) Math.round(r[3] * vh);
        return switch (type) {
            case "library" -> new int[]{x + w / 2, y + 20 + row * 22 + 10};
            case "save" -> row == 0 ? new int[]{x + w - 8 - 27, y + h - 8 - 8} : new int[]{x + 8 + Math.min(w / 3, h - 52) + 8 + (x + w - 8 - (x + 8 + Math.min(w / 3, h - 52) + 8)) / 2, y + 20 + 17};
            case "list" -> new int[]{x + w / 2, y + 20 + (p.props().strings("header").isEmpty() ? 0 : 11) + row * 16 + 7};
            case "warning" -> {
                int n = Math.max(1, p.props().strings("buttons").size());
                int bh = 15, gap = 4, by = y + h - 8 - n * (bh + gap) + gap;
                yield new int[]{x + w / 2, by + row * (bh + gap) + bh / 2};
            }
            default -> new int[]{x + w / 2, y + h / 2};
        };
    }

    private static double[] defaultRect(String type) {
        return switch (type) {
            case "library" -> new double[]{0.04, 0.08, 0.36, 0.64};
            case "save" -> new double[]{0.42, 0.10, 0.54, 0.66};
            case "list" -> new double[]{0.56, 0.06, 0.40, 0.72};
            case "warning" -> new double[]{0.14, 0.10, 0.72, 0.70};
            case "bar" -> new double[]{0.30, 0.03, 0.40, 0.12};
            default -> new double[]{0.33, 0.38, 0.34, 0.24};
        };
    }

    private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, float a, String title) {
        Ui.blit(g, "panel", x, y, w, h, a);
        Ui.text(g, title, x + 8, y + 6, Ui.withAlpha(Ui.LINE, a));
    }

    private static int fade(int color, float a) {
        return Ui.withAlpha(color, a);
    }

    private static void library(GuiGraphicsExtractor g, Snapshot.ItemView p, int x, int y, int w, int h, float a) {
        frame(g, x, y, w, h, a, p.str("title", "Library"));
        List<String> cards = p.props().strings("cards");
        int hot = (int) Math.round(p.num("hot", -1));
        int rowH = 20, ry = y + 20;
        for (int i = 0; i < cards.size() && ry + rowH <= y + h - 4; i++, ry += rowH + 2) {
            boolean on = i == hot;
            Ui.blit(g, "button_0", x + 6, ry, w - 12, rowH, a);
            if (on) Ui.blit(g, "button_1", x + 6, ry, w - 12, rowH, a);
            Ui.icon(g, "house", x + 10, ry + 2, false, 16, a);
            Ui.text(g, Ui.fit(cards.get(i), w - 42), x + 30, ry + 6, fade(on ? Ui.WHITE : Ui.LINE, a));
            if (on) Ui.marching(g, x + 4, ry - 2, w - 8, rowH + 4, fade(Ui.CYAN, a), p.t);
        }
    }

    private static void save(GuiGraphicsExtractor g, Snapshot.ItemView p, int x, int y, int w, int h, float a) {
        frame(g, x, y, w, h, a, "Save a build");
        String name = p.str("name", "My cottage");
        int typed = (int) Math.round(p.num("typed", name.length()));
        // the picture of the build, as a framed square on the left
        int pw = Math.min(w / 3, h - 52), px = x + 8, py = y + 20;
        Ui.inset(g, px, py, pw, pw);
        Ui.icon(g, "house", px + (pw - 32) / 2, py + (pw - 32) / 2, false, 32, a);
        int fx = px + pw + 8, fw = x + w - 8 - fx;
        Ui.text(g, "Name", fx, py, fade(Ui.DIM, a));
        Ui.inset(g, fx, py + 10, fw, 14);
        String shown = name.substring(0, Math.max(0, Math.min(name.length(), typed)));
        Ui.text(g, Ui.fit(shown, fw - 8), fx + 4, py + 13, fade(Ui.WHITE, a));
        if (typed < name.length() || (int) (p.t * 2) % 2 == 0) {
            int cx = fx + 4 + Ui.font().width(Ui.fit(shown, fw - 8));
            g.fill(cx, py + 12, cx + 1, py + 21, fade(Ui.CYAN, a));
        }
        Ui.text(g, "Tags", fx, py + 30, fade(Ui.DIM, a));
        Ui.inset(g, fx, py + 40, fw, 14);
        Ui.text(g, Ui.fit(p.str("tags", "house, wood"), fw - 8), fx + 4, py + 43, fade(Ui.LINE, a));
        int bw = 54, bh = 16, bx = x + w - 8 - bw, by = y + h - 8 - bh;
        boolean lit = p.num("hot", 0) >= 0.5;
        Ui.blit(g, "button_0", bx, by, bw, bh, a);
        if (lit) {
            Ui.blit(g, "button_1", bx, by, bw, bh, a);
            Ui.blit(g, "button_2", bx, by, bw, bh, a);
        }
        Ui.centered(g, p.str("button", "Save"), bx + bw / 2, by + 4, fade(lit ? Ui.DEEP : Ui.LINE, a));
    }

    private static void list(GuiGraphicsExtractor g, Snapshot.ItemView p, int x, int y, int w, int h, float a) {
        frame(g, x, y, w, h, a, p.str("title", "Materials"));
        List<List<String>> rows = p.props().rows("rows");
        List<String> header = p.props().strings("header");
        int hot = (int) Math.round(p.num("hot", -1));
        int cols = rows.isEmpty() ? 2 : rows.get(0).size();
        int needR = w - 120, haveR = w - 84, toL = w - 76;
        int ry = y + 20;
        if (!header.isEmpty()) {
            Ui.text(g, header.get(0), x + 26, ry, fade(Ui.DIM, a));
            if (cols >= 4) {
                Ui.right(g, header.get(1), x + needR, ry, fade(Ui.DIM, a));
                Ui.right(g, header.get(2), x + haveR, ry, fade(Ui.DIM, a));
                Ui.text(g, header.get(3), x + toL, ry, fade(Ui.DIM, a));
            } else if (header.size() > 1) {
                Ui.right(g, header.get(1), x + w - 10, ry, fade(Ui.DIM, a));
            }
            ry += 11;
        }
        int rowH = 15;
        for (int i = 0; i < rows.size() && ry + rowH <= y + h - 16; i++, ry += rowH + 1) {
            boolean on = i == hot;
            List<String> r = rows.get(i);
            if (on) {
                g.fill(x + 6, ry, x + w - 6, ry + rowH, fade(0xFF3A6E9A, a * 0.55f));
                Ui.marching(g, x + 6, ry, w - 12, rowH, fade(Ui.CYAN, a), p.t);
            } else {
                g.fill(x + 6, ry, x + w - 6, ry + rowH, fade(0xFF123456, a * 0.5f));
            }
            Ui.icon(g, "cube", x + 8, ry - 1, false, 16, a);
            Ui.text(g, Ui.fit(r.get(0), (cols >= 4 ? needR - 22 : w - 80) - 26), x + 26, ry + 4, fade(on ? Ui.WHITE : Ui.LINE, a));
            if (cols >= 4) {
                Ui.right(g, r.get(1), x + needR, ry + 4, fade(Ui.LINE, a));
                Ui.right(g, r.get(2), x + haveR, ry + 4, fade(r.get(3).equals("enough") ? Ui.GOOD : Ui.LINE, a));
                if (r.get(3).equals("enough")) {
                    Ui.icon(g, "check", x + toL - 1, ry - 1, false, 16, a);
                    Ui.text(g, "enough", x + toL + 15, ry + 4, fade(Ui.GOOD, a));
                } else {
                    Ui.text(g, Ui.fit(r.get(3), w - toL - 8), x + toL, ry + 4, fade(Ui.WARN, a));
                }
            } else if (r.size() > 1) {
                Ui.right(g, r.get(1), x + w - 10, ry + 4, fade(on ? Ui.WARN : Ui.DIM, a));
            }
        }
        List<String> buttons = p.props().strings("buttons");
        if (!buttons.isEmpty()) {
            int lit = (int) Math.round(p.num("lit", -1)), bx = x + 8;
            for (int i = 0; i < buttons.size(); i++) {
                int bw = Ui.font().width(buttons.get(i)) + 14;
                Ui.blit(g, "button_0", bx, y + h - 20, bw, 14, a);
                if (i == lit) {
                    Ui.blit(g, "button_1", bx, y + h - 20, bw, 14, a);
                    Ui.blit(g, "button_2", bx, y + h - 20, bw, 14, a);
                }
                Ui.centered(g, buttons.get(i), bx + bw / 2, y + h - 17, fade(i == lit ? Ui.DEEP : Ui.LINE, a));
                bx += bw + 4;
            }
        }
        String note = p.str("note", "");
        if (!note.isEmpty() && buttons.isEmpty()) Ui.text(g, Ui.fit(note, w - 16), x + 8, y + h - 13, fade(Ui.DIM, a));
    }

    private static void warning(GuiGraphicsExtractor g, Snapshot.ItemView p, int x, int y, int w, int h, float a) {
        Ui.blit(g, "panel", x, y, w, h, a);
        Ui.text(g, p.str("title", "Auto-place on a server"), x + 8, y + 7, fade(Ui.WARN, a));
        int ty = y + 22;
        for (String line : Ui.wrap(p.str("text", ""), w - 16, 5)) {
            Ui.text(g, line, x + 8, ty, fade(Ui.LINE, a));
            ty += 10;
        }
        List<String> labels = p.props().strings("buttons");
        int focus = (int) Math.round(p.num("focus", 0)), lit = (int) Math.round(p.num("lit", -1));
        double wait = p.num("wait", 0);
        int n = labels.size();
        int bh = 15, gap = 4;
        int by = y + h - 8 - n * (bh + gap) + gap;
        for (int i = 0; i < n; i++) {
            int yy = by + i * (bh + gap);
            // a button that is not yet allowed is dim, with the seconds left
            boolean dead = i == 1 && wait > 0 && p.age() < wait;
            boolean on = i == lit;
            Ui.blit(g, "button_0", x + 8, yy, w - 16, bh, a * (dead ? 0.5f : 1f));
            if (on) {
                Ui.blit(g, "button_1", x + 8, yy, w - 16, bh, a);
                Ui.blit(g, "button_2", x + 8, yy, w - 16, bh, a);
            }
            String label = dead ? labels.get(i) + " (" + (int) Math.ceil(wait - p.age()) + ")" : labels.get(i);
            Ui.centered(g, label, x + w / 2, yy + 4, fade(dead ? 0xFF5E7C99 : on ? Ui.DEEP : Ui.LINE, a));
            if (i == focus && !dead) Ui.marching(g, x + 6, yy - 2, w - 12, bh + 4, fade(Ui.CYAN, a), p.t);
        }
    }

    private static void bar(GuiGraphicsExtractor g, Snapshot.ItemView p, int x, int y, int w, int h, float a) {
        Ui.blit(g, "panel", x, y, w, h, a);
        double v = Math.max(0, Math.min(1, p.num("value", 0)));
        Ui.text(g, Ui.fit(p.str("label", "Progress"), w / 2), x + 6, y + 4, fade(Ui.LINE, a));
        String pct = Math.round(v * 100) + "%";
        Ui.right(g, pct, x + w - 6, y + 4, fade(v >= 0.999 ? Ui.GOOD : Ui.WHITE, a));
        int bx = x + 6, by = y + h - 9, bw = w - 12, bh = 6;
        Ui.blit(g, "bar_track", bx, by, bw, bh, a);
        int fw = (int) Math.round((bw - 4) * v);
        if (fw > 0) Skin.hatch(g, bx + 2, by + 2, fw, bh - 4, Motion.reduced() ? 0 : -p.t * 1.5);
        if (p.bool("done", false) && v >= 0.999) Ui.icon(g, "check", x + w - 4 - 16, y - 7, false, 16, a);
    }

    /** The "done" stamp of the progress panel, as the HUD draws it: lands a little large, turned 12 degrees, a ring of sparks. */
    private static void stamp(GuiGraphicsExtractor g, Snapshot.ItemView p, int x, int y, int w, int h, float a) {
        String text = p.str("text", "DONE");
        int color = io.github.profetgit.cyanotype.ponder.StageRaster.color(p.str("color", "green"));
        double since = p.age();
        double e = Motion.reduced() ? 1 : Motion.easeOut(Math.min(1, since / 0.18));
        int cx = x + w / 2, cy = y + h / 2;
        int half = Math.max(15, (Ui.font().width(text) + 8) / 2);
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(1f + (float) ((1 - e) * 0.8), 1f + (float) ((1 - e) * 0.8));
        g.pose().rotate((float) Math.toRadians(-12));
        g.pose().scale(1.5f, 1.5f);
        g.fill(-half, -7, half, 7, Ui.withAlpha(Ui.DEEP, (float) e * 0.85f * a));
        g.outline(-half, -7, half * 2, 14, Ui.withAlpha(color, (float) e * a));
        Ui.centered(g, text, 0, -4, Ui.withAlpha(color, (float) e * a));
        g.pose().popMatrix();
        if (!Motion.reduced() && since < 0.7) {
            for (int i = 0; i < 8; i++) {
                double ang = i * Math.PI / 4 + 0.3, r = 6 + since * 40;
                float al = (float) Math.max(0, 1 - since / 0.7) * a;
                int px = (int) (cx + Math.cos(ang) * r), py = (int) (cy + Math.sin(ang) * r * 0.6);
                int col = Ui.withAlpha(Ui.WARN, al);
                g.fill(px - 1, py, px + 2, py + 1, col);
                g.fill(px, py - 1, px + 1, py + 2, col);
            }
        }
    }

    /** A HUD badge ("AUTO: ON" and the like): a small panel, a pulsing dot or a check, a title, a note on the right and a line of status. */
    private static void badge(GuiGraphicsExtractor g, Snapshot.ItemView p, int x, int y, int w, int h, float a) {
        Ui.blit(g, "panel", x, y, w, h, a);
        int accent = io.github.profetgit.cyanotype.ponder.StageRaster.color(p.str("color", "cyan"));
        boolean check = p.str("icon", "dot").equals("check");
        int tx = x + 18;
        if (check) {
            Ui.icon(g, "check", x + 2, y + 1, false, 16, a);
            tx = x + 20;
        } else {
            float pulse = Motion.reduced() ? 1f : (float) (0.55 + 0.45 * Math.sin(p.t * 4));
            g.fill(x + 8, y + 8, x + 13, y + 13, fade(accent, a * pulse));
        }
        Ui.text(g, p.str("title", ""), tx, y + 6, fade(accent, a));
        String right = p.str("right", "");
        if (!right.isEmpty()) Ui.right(g, right, x + w - 8, y + 6, fade(Ui.LINE, a));
        String status = p.str("status", "");
        if (!status.isEmpty()) Ui.text(g, Ui.fit(status, w - 16), x + 8, y + 18, fade(Ui.DIM, a));
    }
}
