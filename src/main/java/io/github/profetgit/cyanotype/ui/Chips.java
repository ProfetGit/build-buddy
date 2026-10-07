package io.github.profetgit.cyanotype.ui;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The cursor chips: small labels beside the crosshair that say what the mouse and keys do right now ("Scroll: turn",
 * "Click: lock"). Whoever owns the current tool sets them every frame. The rules, so they never flicker:
 * <ul>
 * <li><b>When they show:</b> only while a Cyanotype tool is active (placing, edit, layers, save area, smart pick) and no screen is open;
 * never over the tool wheel or a menu, and not while the ghosts are hidden (H).</li>
 * <li><b>A set is its keys:</b> the left halves ("Drag", "Scroll", "Ctrl+Z / Y"). A new set of keys is a new situation and slides
 * in once, a row after a row. The same keys with other words (the direction "Push south" follows the view, the first row follows
 * what is aimed at) just change the words in place: nothing slides, nothing rebuilds.</li>
 * <li><b>They stay put through gaps:</b> a quarter of a second without a request (a tool changing over, a lost frame) keeps
 * them; after that they fade out, and come in fresh next time.</li>
 * </ul>
 * They are the first layer of the in-game help (PRD 7.12) and can be turned off.
 */
public final class Chips {
    /** {@code quiet} rows are reference, not instructions: drawn smaller in weight (fainter). */
    public record Chip(String key, String action, boolean quiet) {
        public Chip(String key, String action) {
            this(key, action, false);
        }
    }

    private static List<Chip> current = List.of();
    private static String id = "";
    private static long changedNs, wantedNs;
    private static boolean gone = true;
    private static final long GRACE_NS = 250_000_000L;

    private Chips() {
    }

    /** Asks for these chips this frame. */
    public static void show(Chip... chips) {
        List<Chip> now = List.of(chips);
        StringBuilder keys = new StringBuilder();
        for (Chip c : now) keys.append(c.key()).append('\u0001');
        String nid = keys.toString();
        if (gone || !nid.equals(id)) {
            id = nid;
            changedNs = System.nanoTime();
            gone = false;
        }
        current = now;
        wantedNs = System.nanoTime();
    }

    /** Dev demo: how many rows are asked for now. */
    public static int count() {
        return current.size();
    }

    /** The legacy text form, for when chips are switched off: one line on the action bar. */
    public static String line(Chip... chips) {
        StringBuilder sb = new StringBuilder();
        for (Chip c : chips) {
            if (sb.length() > 0) sb.append("  |  ");
            sb.append(c.key()).append(": ").append(c.action());
        }
        return sb.toString();
    }

    /**
     * Called once per frame by the HUD. The chips sit well to the right of the crosshair, out of the way of what is being
     * built, and are drawn at about three quarters of the GUI scale (rounded so the text stays on whole pixels).
     */
    static void draw(GuiGraphicsExtractor g) {
        boolean wanted = System.nanoTime() - wantedNs < GRACE_NS && net.minecraft.client.Minecraft.getInstance().gui.screen() == null;
        float visible = Motion.follow("chips#visible", wanted ? 1f : 0f, 0.1);
        if (visible < 0.02f && !wanted) gone = true;
        if (visible < 0.02f || current.isEmpty() || !Settings.get().chips) return;
        int w = g.guiWidth(), h = g.guiHeight();
        double gui = Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        float s = (float) (Math.max(1, Math.round(gui * 0.75)) / gui);
        int rowH = 16, gap = 1;
        int total = current.size() * (rowH + gap) - gap;
        int ax = w / 2 + 58, ay = Math.round(h / 2f - total * s / 2);
        double since = (System.nanoTime() - changedNs) / 1e9;
        g.pose().pushMatrix();
        g.pose().translate(ax, ay);
        g.pose().scale(s, s);
        for (int i = 0; i < current.size(); i++) {
            Chip c = current.get(i);
            // each chip comes in a little after the one above it
            double local = Motion.reduced() ? 1 : Math.max(0, Math.min(1, (since - i * 0.04) / 0.16));
            float a = (float) (Motion.easeOut(local) * visible) * (c.quiet() ? 0.7f : 1f);
            if (a < 0.02f) continue;
            int slide = Motion.reduced() ? 0 : (int) Math.round((1 - Motion.easeOut(local)) * 8);
            int kw = ChipIcons.width(c.key()), aw = Ui.font().width(c.action());
            int cw = kw + aw + 19;
            int cx = slide, cy = i * (rowH + gap);
            Ui.blit(g, "tooltip", cx, cy, cw, rowH, a * 0.92f);
            ChipIcons.draw(g, c.key(), cx + 5, cy, a);
            g.fill(cx + 8 + kw, cy + 4, cx + 9 + kw, cy + rowH - 4, Ui.withAlpha(Ui.DIM, a * 0.55f));
            Ui.text(g, c.action(), cx + 12 + kw, cy + 4, Ui.withAlpha(Ui.WHITE, a));
        }
        g.pose().popMatrix();
    }
}
