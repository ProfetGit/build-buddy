package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.auto.AutoBuilder;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.paste.Paste;
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
    private static Verifier lastVerifier;
    private static long doneAtNs;

    private CyanotypeHud() {
    }

    public static void draw(Minecraft mc, GuiGraphicsExtractor g) {
        if (mc.gui.hud.isHidden() || mc.level == null || mc.player == null) return;
        Motion.frame();
        progress(mc, g);
        autoBadge(mc, g);
        pasteBadge(mc, g);
        Chips.draw(g);
        Welcome.draw(mc, g);
    }

    /** "AUTO: ON" while auto-placing is running: always visible then, amber on a multiplayer server (PRD 7.8), with what it is doing. */
    private static void autoBadge(Minecraft mc, GuiGraphicsExtractor g) {
        boolean on = AutoBuilder.on();
        float a = Motion.follow("hud#auto", on ? 1f : 0f, 0.12);
        if (a < 0.02f) return;
        boolean server = AutoBuilder.serverKey(mc) != null;
        int w = 150, h = 29, x = g.guiWidth() - w - 6, y = 6;
        double t = System.nanoTime() / 1e9;
        float inner = Ui.panelOpening(g, x, y, w, h, Motion.reduced() ? 1 : Math.min(1.0, a));
        if (inner < 0.05f) return;
        int accent = server ? Ui.WARN : Ui.CYAN;
        // a small pulse beside the words says it is live
        float pulse = Motion.reduced() ? 1f : (float) (0.55 + 0.45 * Math.sin(t * 4));
        g.fill(x + 8, y + 8, x + 13, y + 13, Ui.withAlpha(accent, inner * pulse));
        String mode = AutoBuilder.mode() == AutoBuilder.Mode.SWEEP ? "Sweep" : "Assist";
        Ui.text(g, "AUTO: ON", x + 18, y + 6, Ui.withAlpha(accent, inner));
        Ui.right(g, mode + "  " + Settings.get().autoRate + "/s", x + w - 8, y + 6, Ui.withAlpha(Ui.LINE, inner));
        Ui.text(g, Ui.fit(AutoBuilder.status(), w - 16), x + 8, y + 18, Ui.withAlpha(Ui.DIM, inner));
    }

    /** A thin panel under the progress one while a paste (or the undo of one) runs in the world. */
    private static void pasteBadge(Minecraft mc, GuiGraphicsExtractor g) {
        double v = Paste.progress();
        float a = Motion.follow("hud#paste", v >= 0 ? 1f : 0f, 0.12);
        if (a < 0.02f) return;
        int w = 196, h = 26, x = (g.guiWidth() - w) / 2, y = 50;
        double t = System.nanoTime() / 1e9;
        float inner = Ui.panelOpening(g, x, y, w, h, Motion.reduced() ? 1 : Math.min(1.0, a));
        if (inner < 0.05f) return;
        double shown = Math.max(0, v);
        String what = Paste.undoing() ? "Undoing the paste" : "Pasting into the world";
        Ui.text(g, what, x + 8, y + 5, Ui.withAlpha(Ui.LINE, inner));
        Ui.right(g, Math.round(shown * 100) + "%", x + w - 8, y + 5, Ui.withAlpha(Ui.CYAN, inner));
        Ui.bar(g, "hud#pastebar", x + 8, y + 16, w - 16, 5, shown, t);
    }

    private static void progress(Minecraft mc, GuiGraphicsExtractor g) {
        Placement p = Placements.active();
        Verifier v = p == null || !p.locked ? null : GhostRenderer.verifierOf(p);
        boolean show = v != null && !GhostRenderer.hidden && (Placements.mode() != Placements.Mode.IDLE || near(mc, p, 96));
        float a = Motion.follow("hud#progress", show ? 1f : 0f, 0.15);
        // what the verifier says, every frame, whether or not the panel is on show: the done sound belongs to the moment the
        // build is finished, not to the moment the panel appears, and never to a verifier that has only just been made
        Verifier.Phase phase = v == null ? Verifier.Phase.CHECKING : v.phase();
        if (v != lastVerifier) {
            lastVerifier = v;
            wasDone = phase == Verifier.Phase.DONE;
        }
        boolean done = phase == Verifier.Phase.DONE;
        if (done && !wasDone && show) {
            doneAtNs = System.nanoTime();
            Sfx.play(Sfx.COMPLETE);
        } else if (done && !wasDone) {
            doneAtNs = System.nanoTime() - 10_000_000_000L;
        }
        wasDone = done;
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
        double fraction = switch (phase) {
            case CHECKING -> v.scanFraction();
            case EMPTY -> 0;
            case BUILDING -> v.progress();
            case DONE -> 1;
        };
        Ui.bar(g, "hud#bar", x + 8, y + 18, w - 16, 8, fraction, t);
        String status = switch (phase) {
            case CHECKING -> "Checking the world...  " + Math.round(v.scanFraction() * 100) + "%";
            case EMPTY -> c.unloaded() > 0 ? c.unloaded() + " blocks not loaded" : "Nothing to build";
            case DONE -> "Done";
            case BUILDING -> Math.round(v.progress() * 100) + "%   " + c.todo() + " to go" + (c.unloaded() > 0 ? "   (" + c.unloaded() + " not loaded)" : "");
        };
        Ui.centered(g, status, x + w / 2, y + 29, Ui.withAlpha(phase == Verifier.Phase.DONE ? Ui.GOOD : phase == Verifier.Phase.CHECKING ? Ui.DIM : Ui.CYAN, inner));
        stamp(g, done, x, y, w, h, t);
    }

    /** The "done" moment: a sound, a ring of small sparkles, and a stamped mark over the corner of the panel. */
    private static void stamp(GuiGraphicsExtractor g, boolean done, int x, int y, int w, int h, double t) {
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
