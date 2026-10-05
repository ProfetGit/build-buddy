package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.verify.Counts;
import io.github.profetgit.cyanotype.verify.Verifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * What the mod puts on the game's HUD: the progress panel of the placement being built (name, layers, a bar, what is left)
 * and the cursor chips. Drawn at the end of vanilla's HUD by HudMixin.
 */
public final class CyanotypeHud {
    private static boolean wasDone;
    private static long doneAtNs;

    private CyanotypeHud() {
    }

    public static void draw(Minecraft mc, GuiGraphicsExtractor g) {
        if (mc.gui.hud.isHidden() || mc.level == null || mc.player == null) return;
        Motion.frame();
        progress(mc, g);
        Chips.draw(g);
    }

    private static void progress(Minecraft mc, GuiGraphicsExtractor g) {
        Placement p = Placements.active();
        Verifier v = p == null || !p.locked ? null : GhostRenderer.verifierOf(p);
        boolean show = v != null && !GhostRenderer.hidden && (Placements.mode() != Placements.Mode.IDLE || near(mc, p, 96));
        float a = Motion.follow("hud#progress", show ? 1f : 0f, 0.15);
        if (v == null || a < 0.02f) return;
        Counts c = v.counts();
        int w = 196, h = 40, x = (g.guiWidth() - w) / 2, y = 6 - (int) Math.round((1 - a) * 12);
        double t = System.nanoTime() / 1e9;
        float inner = Ui.panelOpening(g, x, y, w, h, Motion.reduced() ? 1 : Math.min(1.0, a));
        if (inner < 0.05f) return;
        String name = Ui.fit(p.name, w - 90);
        Ui.text(g, name, x + 8, y + 6, Ui.withAlpha(Ui.LINE, inner));
        if (p.layered()) {
            int lo = Math.max(0, p.layerLo) + 1, hi = (p.layerHi < 0 ? p.sizeY() - 1 : Math.min(p.sizeY() - 1, p.layerHi)) + 1;
            Ui.right(g, (lo == hi ? "Layer " + lo : "Layers " + lo + "-" + hi) + " of " + p.sizeY(), x + w - 8, y + 6, Ui.withAlpha(Ui.CYAN, inner));
        } else {
            Ui.right(g, p.sizeX() + " x " + p.sizeY() + " x " + p.sizeZ(), x + w - 8, y + 6, Ui.withAlpha(Ui.DIM, inner));
        }
        Ui.bar(g, "hud#bar", x + 8, y + 18, w - 16, 8, c.progress(), t);
        String status = c.done() ? "Done" : c.judged() == 0 ? (c.unloaded() > 0 ? c.unloaded() + " blocks not loaded" : "Nothing to build")
            : Math.round(c.progress() * 100) + "%   " + c.todo() + " to go" + (c.unloaded() > 0 ? "   (" + c.unloaded() + " not loaded)" : "");
        Ui.centered(g, status, x + w / 2, y + 29, Ui.withAlpha(c.done() ? Ui.GOOD : Ui.CYAN, inner));
        stamp(g, c.done(), x, y, w, h, t);
    }

    /** The "done" moment: a sound, a ring of small sparkles, and a stamped mark over the corner of the panel. */
    private static void stamp(GuiGraphicsExtractor g, boolean done, int x, int y, int w, int h, double t) {
        if (done && !wasDone) {
            doneAtNs = System.nanoTime();
            Sfx.play(Sfx.COMPLETE);
        }
        wasDone = done;
        if (!done) return;
        double since = (System.nanoTime() - doneAtNs) / 1e9;
        if (since > 600) return;
        double e = Motion.reduced() ? 1 : Motion.easeOut(Math.min(1, since / 0.18));
        int sx = x + w - 30, sy = y + h - 6;
        // the stamp drops in a little large, then sits
        int size = (int) (1 + (Motion.reduced() ? 0 : (1 - e) * 1.0));
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.pose().scale(1f + (float) ((1 - e) * 0.8), 1f + (float) ((1 - e) * 0.8));
        g.pose().rotate((float) Math.toRadians(-12));
        g.pose().scale(1.5f, 1.5f);
        g.fill(-15, -7, 15, 7, Ui.withAlpha(Ui.DEEP, (float) e * 0.85f));
        g.outline(-15, -7, 30, 14, Ui.withAlpha(Ui.GOOD, (float) e));
        Ui.centered(g, "DONE", 0, -4, Ui.withAlpha(Ui.GOOD, (float) e));
        g.pose().popMatrix();
        if (!Motion.reduced() && since < 0.7) {
            for (int i = 0; i < 8; i++) {
                double ang = i * Math.PI / 4 + 0.3, r = 6 + since * 40;
                float a = (float) Math.max(0, 1 - since / 0.7);
                int px = (int) (sx + Math.cos(ang) * r), py = (int) (sy + Math.sin(ang) * r * 0.6);
                int col = Ui.withAlpha(Ui.WARN, a);
                g.fill(px - 1, py, px + 2, py + 1, col);
                g.fill(px, py - 1, px + 1, py + 2, col);
            }
        }
    }

    private static boolean near(Minecraft mc, Placement p, double range) {
        double dx = mc.player.getX() - p.centerX(), dz = mc.player.getZ() - p.centerZ();
        return dx * dx + dz * dz < range * range;
    }
}
