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
 * worker thread and shown as a texture. Drag to turn it, right-drag (or Shift+drag) to move it, scroll to zoom toward the
 * cursor, double click to start over. While it is being moved a big build is drawn with only some of its faces so it keeps
 * up; a moment after the hand stops the full picture is drawn.
 */
public final class BuildPreview {
    /** Texels per GUI unit: the picture is sharp on screens up to GUI scale 2 and smooth beyond. */
    private static final int RES = 2;
    private static final int FAST_FACES = 250_000;
    private static final long SETTLE_NS = 220_000_000L;

    private final Minecraft mc;
    private volatile PreviewRaster.@Nullable Scene scene;
    private volatile boolean tooBig, building;
    private PreviewRaster.View view = PreviewRaster.View.HOME;
    private final AtomicBoolean busy = new AtomicBoolean(), dirty = new AtomicBoolean();
    private volatile int[] frame;
    private volatile int frameW, frameH, frameSeq;
    private int shownSeq = -1;
    private volatile int wantW = 1, wantH = 1;
    private volatile long lastMoveNs;
    private volatile boolean lastWasFast;
    private DynamicTexture texture;
    private Identifier textureId;
    private int texW, texH;

    public BuildPreview(Minecraft mc) {
        this.mc = mc;
    }

    // ---- what is shown

    /** Sets what to draw: null while the box is still being read. A new blueprint keeps the view the player has chosen. */
    public void setBlueprint(@Nullable Blueprint bp) {
        scene = null;
        tooBig = false;
        frame = null;
        if (bp == null) return;
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
        return s == null ? -1 : s.count;
    }

    public PreviewRaster.View view() {
        return view;
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
            view = new PreviewRaster.View(view.yaw(), view.pitch(), view.zoom(), view.panX() + dx * RES, view.panY() + dy * RES);
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
        double px = cx * RES, py = cy * RES;
        view = new PreviewRaster.View(view.yaw(), view.pitch(), z, px + (view.panX() - px) * f, py + (view.panY() - py) * f);
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
    public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, double progress) {
        int tw = Math.max(8, w * RES), th = Math.max(8, h * RES);
        if (tw != wantW || th != wantH) {
            wantW = tw;
            wantH = th;
            request();
        }
        Ui.inset(g, x, y, w, h);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, 0x40163B63, 0x400A1B30);
        PreviewRaster.Scene s = scene;
        if (s != null && frame != null && frameW == tw && frameH == th) {
            if (frameSeq != shownSeq) upload(tw, th);
            if (textureId != null) g.blit(RenderPipelines.GUI_TEXTURED, textureId, x, y, 0f, 0f, w, h, tw, th, tw, th);
            if (System.nanoTime() - lastMoveNs > SETTLE_NS && lastWasFast) request();
            if (s.count == 0) Ui.centered(g, "Nothing in the box", x + w / 2, y + h / 2 - 4, Ui.DIM);
            if (s.thinned) Ui.text(g, "A very big build: only part of it is drawn", x + 6, y + 5, Ui.withAlpha(Ui.WARN, 0.9f));
            return;
        }
        String msg;
        if (tooBig) msg = "Too big to preview";
        else if (progress >= 0) msg = "Reading the box... " + Math.round(progress * 100) + "%";
        else if (building) msg = "Drawing the preview...";
        else msg = "Drawing the preview...";
        Ui.centered(g, msg, x + w / 2, y + h / 2 - 4, Ui.DIM);
    }

    private void upload(int tw, int th) {
        int[] px = frame;
        if (px == null || px.length != tw * th) return;
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
        for (int yy = 0; yy < th; yy++) for (int xx = 0; xx < tw; xx++) img.setPixel(xx, yy, px[yy * tw + xx]);
        texture.upload();
        shownSeq = frameSeq;
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
        int w = wantW, h = wantH;
        boolean moving = System.nanoTime() - lastMoveNs < SETTLE_NS;
        int stride = moving && s.count > FAST_FACES ? (int) Math.ceil(s.count / (double) FAST_FACES) : 1;
        Util.backgroundExecutor().execute(() -> {
            try {
                int[] px = PreviewRaster.render(s, v, w, h, stride);
                frameW = w;
                frameH = h;
                frame = px;
                lastWasFast = stride > 1;
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
