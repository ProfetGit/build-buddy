package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The picture of what is about to be saved, in the Save screen: a blueprint drawn from any side by {@link PreviewRaster} on a
 * worker thread and shown as a texture with one texel for every pixel of the screen. Drag to turn it, right-drag (or Shift+drag)
 * to move it, scroll to zoom toward the cursor. While the hand is moving it is drawn once per pixel (and a big build with only
 * some of its faces) so it keeps up; when the hand stops it is drawn again at four times the samples and smoothed, so edges
 * are clean. The pointer can be asked what block it is over (an id buffer comes with every picture).
 */
public final class BuildPreview {
    private static final int FAST_FACES = 250_000;
    private static final long SETTLE_NS = 220_000_000L;
    /** The most samples one picture may have: beyond it the smoothing is left out. */
    private static final long MAX_SAMPLES = 5_000_000L;

    /** A finished picture: what it shows, how big it was drawn compared with the screen ({@code ss} samples a pixel across), and the screen's GUI scale then. */
    private record Shown(PreviewRaster.Frame frame, PreviewRaster.Scene scene, int ss, int res, boolean low) {
    }

    private final Minecraft mc;
    private volatile PreviewRaster.@Nullable Scene scene;
    private volatile boolean tooBig, building;
    private PreviewRaster.View view = PreviewRaster.View.HOME;
    private final AtomicBoolean busy = new AtomicBoolean(), dirty = new AtomicBoolean();
    private volatile @Nullable Shown shown;
    private volatile int frameSeq;
    private int uploadedSeq = -1;
    private volatile int hoverCell = -1;
    private volatile boolean removeMode;
    private volatile int wantW = 1, wantH = 1, wantRes = 1;
    private volatile long lastMoveNs;
    private DynamicTexture texture;
    private Identifier textureId;
    private int texW, texH;

    public BuildPreview(Minecraft mc) {
        this.mc = mc;
    }

    // ---- what is shown

    /** Sets what to draw: null while the box is still being read. A new blueprint keeps the view the player has chosen. */
    public void setBlueprint(@Nullable Blueprint bp) {
        if (bp == null) {
            // nothing to show yet: the old picture goes
            scene = null;
            shown = null;
            tooBig = false;
            hoverCell = -1;
            return;
        }
        // a new blueprint (an edit, say) replaces the picture when it is ready; until then the old one stays
        building = true;
        Util.backgroundExecutor().execute(() -> {
            PreviewRaster.Scene s = PreviewRaster.scene(bp);
            tooBig = s == null;
            scene = s;
            building = false;
            request();
        });
    }

    /** The scene's face count, or -1 when there is no scene (for the demo). */
    public int faces() {
        PreviewRaster.Scene s = scene;
        return s == null ? -1 : s.count + s.custom.length;
    }

    public PreviewRaster.View view() {
        return view;
    }

    /** Whether pointing at a block lights it up (the Save screen's remove mode). */
    public void setRemoveMode(boolean on) {
        removeMode = on;
        if (!on && hoverCell >= 0) {
            hoverCell = -1;
            request();
        }
    }

    /** The cell under a point of the picture ({@code gx, gy} in GUI units from its top left), as an index into the box, or -1. */
    public int cellAt(double gx, double gy) {
        Shown sh = shown;
        if (sh == null) return -1;
        int k = sh.res() * sh.ss();
        int x = (int) (gx * k), y = (int) (gy * k);
        PreviewRaster.Frame f = sh.frame();
        if (x < 0 || y < 0 || x >= f.w() || y >= f.h()) return -1;
        return sh.scene().cellOfId(f.ids()[y * f.w() + x]);
    }

    /** The box's size in cells of the scene the picture shows: {x, y, z}, or null. */
    public int @Nullable [] boxSize() {
        Shown sh = shown;
        return sh == null ? null : new int[]{sh.scene().ex, sh.scene().ey, sh.scene().ez};
    }

    /** Points at a place of the picture (or null when the pointer is elsewhere): in remove mode the block there lights up. */
    private void point(double gx, double gy, boolean inside) {
        int cell = removeMode && inside ? cellAt(gx, gy) : -1;
        if (cell != hoverCell) {
            hoverCell = cell;
            request();
        }
    }

    /** The cell the pointer is on now (-1 when not in remove mode or not over a block). */
    public int hovered() {
        return hoverCell;
    }

    // ---- the player's hands

    public void reset() {
        view = PreviewRaster.View.HOME;
        touch();
        request();
    }

    /** Dragging the picture: turns it, or with {@code pan} moves it. {@code dx, dy} are in GUI units. */
    public void drag(double dx, double dy, boolean pan) {
        if (pan) {
            view = new PreviewRaster.View(view.yaw(), view.pitch(), view.zoom(), view.panX() + dx, view.panY() + dy);
        } else {
            double pitch = Math.max(-1.5, Math.min(1.5, view.pitch() + dy * 0.012));
            view = new PreviewRaster.View(view.yaw() - dx * 0.012, pitch, view.zoom(), view.panX(), view.panY());
        }
        touch();
        request();
    }

    /** Zooms toward a point of the picture ({@code cx, cy} in GUI units from the picture's middle) by wheel steps. */
    public void zoom(double steps, double cx, double cy) {
        double f = Math.pow(1.18, steps);
        double z = Math.max(0.2, Math.min(60, view.zoom() * f));
        f = z / view.zoom();
        view = new PreviewRaster.View(view.yaw(), view.pitch(), z, cx + (view.panX() - cx) * f, cy + (view.panY() - cy) * f);
        touch();
        request();
    }

    private void touch() {
        lastMoveNs = System.nanoTime();
    }

    // ---- drawing

    /**
     * Draws the preview into a rectangle.
     *
     * @param progress 0..1 while the box is still being read, else negative
     */
    public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, double progress, int mouseX, int mouseY) {
        int res = (int) Math.max(1, Math.round(mc.getWindow().getGuiScale()));
        int tw = Math.max(8, w * res), th = Math.max(8, h * res);
        if (tw != wantW || th != wantH || res != wantRes) {
            wantW = tw;
            wantH = th;
            wantRes = res;
            request();
        }
        Ui.inset(g, x, y, w, h);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, 0x40163B63, 0x400A1B30);
        PreviewRaster.Scene s = scene;
        Shown sh = shown;
        point(mouseX - x, mouseY - y, mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h);
        if (s != null && sh != null && sh.frame().w() == tw * sh.ss() && sh.frame().h() == th * sh.ss()) {
            if (frameSeq != uploadedSeq) upload(sh, tw, th);
            if (textureId != null) g.blit(RenderPipelines.GUI_TEXTURED, textureId, x, y, 0f, 0f, w, h, tw, th, tw, th);
            // a picture drawn quickly while the hand moved is drawn again, fully, once it has stopped
            if (System.nanoTime() - lastMoveNs > SETTLE_NS && sh.low()) request();
            if (s.empty()) Ui.centered(g, "Nothing in the box", x + w / 2, y + h / 2 - 4, Ui.DIM);
            if (s.thinned) Ui.text(g, "A very big build: only part of it is drawn", x + 6, y + 5, Ui.withAlpha(Ui.WARN, 0.9f));
            return;
        }
        String msg;
        if (tooBig) msg = "Too big to preview";
        else if (progress >= 0) msg = "Reading the box... " + Math.round(progress * 100) + "%";
        else msg = "Drawing the preview...";
        Ui.centered(g, msg, x + w / 2, y + h / 2 - 4, Ui.DIM);
    }

    /** Puts the picture in the texture, averaging the samples of a pixel (weighted by how much of it is covered, so edges do not go dark). */
    private void upload(Shown sh, int tw, int th) {
        int ss = sh.ss();
        int[] src = sh.frame().px();
        int sw = tw * ss;
        if (texture == null || texW != tw || texH != th) {
            close();
            NativeImage img = new NativeImage(tw, th, true);
            texture = new Skin.Smooth("Cyanotype build preview", img, false);
            textureId = Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, "preview/" + System.nanoTime());
            mc.getTextureManager().register(textureId, texture);
            texW = tw;
            texH = th;
        }
        NativeImage img = texture.getPixels();
        if (img == null) return;
        int n = ss * ss;
        for (int yy = 0; yy < th; yy++) {
            for (int xx = 0; xx < tw; xx++) {
                if (ss == 1) {
                    img.setPixel(xx, yy, src[yy * sw + xx]);
                    continue;
                }
                long a = 0, r = 0, g = 0, b = 0;
                for (int sy = 0; sy < ss; sy++) {
                    for (int sx = 0; sx < ss; sx++) {
                        int c = src[(yy * ss + sy) * sw + xx * ss + sx];
                        int ca = c >>> 24;
                        a += ca;
                        r += ((c >> 16) & 255) * ca;
                        g += ((c >> 8) & 255) * ca;
                        b += (c & 255) * ca;
                    }
                }
                if (a == 0) {
                    img.setPixel(xx, yy, 0);
                } else {
                    img.setPixel(xx, yy, (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a));
                }
            }
        }
        texture.upload();
        uploadedSeq = frameSeq;
    }

    /** Releases the texture. */
    public void close() {
        if (texture != null) {
            texture.close();
            texture = null;
            textureId = null;
        }
    }

    // ---- the worker

    private void request() {
        PreviewRaster.Scene s = scene;
        if (s == null) return;
        if (!busy.compareAndSet(false, true)) {
            dirty.set(true);
            return;
        }
        PreviewRaster.View v = view;
        int tw = wantW, th = wantH, res = wantRes;
        boolean moving = System.nanoTime() - lastMoveNs < SETTLE_NS;
        int faces = s.count + s.custom.length;
        int stride = moving && faces > FAST_FACES ? (int) Math.ceil(faces / (double) FAST_FACES) : 1;
        int ss = moving || (long) tw * th * 4 > MAX_SAMPLES ? 1 : 2;
        int hover = hoverCell;
        boolean low = stride > 1 || ss == 1;
        Util.backgroundExecutor().execute(() -> {
            try {
                // the pan is in GUI units; the picture is drawn in samples
                double k = (double) res * ss;
                PreviewRaster.View scaled = new PreviewRaster.View(v.yaw(), v.pitch(), v.zoom(), v.panX() * k, v.panY() * k);
                PreviewRaster.Frame made = PreviewRaster.render(s, scaled, tw * ss, th * ss, stride, hover);
                shown = new Shown(made, s, ss, res, low);
                frameSeq++;
            } catch (RuntimeException e) {
                Cyanotype.LOG.error("The preview could not be drawn", e);
            } finally {
                busy.set(false);
                if (dirty.getAndSet(false)) request();
            }
        });
    }
}
