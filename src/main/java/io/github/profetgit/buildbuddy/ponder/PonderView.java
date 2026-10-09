package io.github.profetgit.buildbuddy.ponder;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.buildbuddy.BuildBuddy;
import io.github.profetgit.buildbuddy.ui.PreviewRaster;
import io.github.profetgit.buildbuddy.ui.Skin;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The picture of a lesson in a rectangle of the screen. {@link StageRaster} draws it on a worker thread at one sample per screen
 * pixel, twice over in each direction and averaged (smooth edges); the game's frame only copies the finished picture into a
 * texture. The picture comes a frame or two after the snapshot it shows, so the cursor and the labels are placed from the
 * snapshot and camera the picture was made from, never from a newer one, and stay on the blocks they point at.
 */
public final class PonderView {
    /** More samples than this and the smoothing is left out. */
    private static final long MAX_SAMPLES = 6_000_000L;

    /** A finished picture: pixels in the texture's byte order, the snapshot and camera it shows, its text marks in samples, and its size. */
    private record Shown(int[] abgr, int tw, int th, int ss, int res, Snapshot snap, StageRaster.Camera cam, List<StageRaster.TextMark> marks, Scene scene) {
    }

    private final Minecraft mc;
    private final StageRaster raster = new StageRaster();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile @Nullable Shown shown;
    private volatile int seq;
    private int uploadedSeq = -1;
    private volatile Request wanted, last;
    private DynamicTexture texture;
    private Identifier textureId;
    private int texW, texH;
    private long rendered;
    private double renderNs;
    private long drawnFrames;

    private record Request(Scene scene, Snapshot snap, int tw, int th, int ss, int res) {
    }

    public PonderView(Minecraft mc) {
        this.mc = mc;
    }

    /**
     * Draws the picture of a snapshot into a rectangle (GUI units). Asks the worker for a new picture when the snapshot or the size
     * changed; draws the newest one that is ready meanwhile.
     */
    public void draw(GuiGraphicsExtractor g, Scene scene, Snapshot snap, int x, int y, int w, int h) {
        int res = (int) Math.max(1, Math.round(mc.getWindow().getGuiScale()));
        int tw = Math.max(8, w * res), th = Math.max(8, h * res);
        int ss = (long) tw * th * 4 > MAX_SAMPLES ? 1 : 2;
        Request req = new Request(scene, snap, tw, th, ss, res);
        Request prev = last;
        if (prev == null || prev.scene != scene || prev.snap.t != snap.t || prev.tw != tw || prev.th != th || prev.res != res) request(req);
        Shown sh = shown;
        drawnFrames++;
        if (sh == null) return;
        if (seq != uploadedSeq) upload(sh);
        if (textureId != null) g.blit(RenderPipelines.GUI_TEXTURED, textureId, x, y, 0f, 0f, w, h, texW, texH, texW, texH);
    }

    /** The snapshot the picture on screen was made from (null before the first one is ready). */
    public @Nullable Snapshot shownSnapshot() {
        Shown sh = shown;
        return sh == null ? null : sh.snap;
    }

    /** Whether the picture on screen shows the newest snapshot asked for: for the demo. */
    public boolean current() {
        Shown sh = shown;
        Request l = last;
        return sh != null && l != null && sh.snap == l.snap && !busy.get();
    }

    /** A point of the world as a spot of the picture in GUI units from its top left (x, y) and its depth, or null when there is no picture yet. */
    public double @Nullable [] project(double wx, double wy, double wz) {
        Shown sh = shown;
        if (sh == null) return null;
        double[] p = sh.cam.project(wx, wy, wz);
        double k = sh.res * sh.ss;
        return new double[]{p[0] / k, p[1] / k, p[2]};
    }

    /** The text marks of the picture on screen, in GUI units from its top left. */
    public List<StageRaster.TextMark> labels() {
        Shown sh = shown;
        if (sh == null) return List.of();
        double k = sh.res * sh.ss;
        List<StageRaster.TextMark> out = new ArrayList<>(sh.marks.size());
        for (StageRaster.TextMark m : sh.marks) out.add(new StageRaster.TextMark(m.x() / k, m.y() / k, m.text(), m.color(), m.anchor(), m.alpha(), m.boxed()));
        return out;
    }

    /** Average milliseconds a picture took to make, for the demo's numbers. */
    public double averageMs() {
        return rendered == 0 ? 0 : renderNs / rendered / 1e6;
    }

    public long picturesMade() {
        return rendered;
    }

    // ---- the worker

    private void request(Request req) {
        wanted = req;
        last = req;
        if (!busy.compareAndSet(false, true)) return;
        work();
    }

    private void work() {
        Util.backgroundExecutor().execute(() -> {
            Request r = wanted;
            try {
                long t0 = System.nanoTime();
                int w = r.tw * r.ss, h = r.th * r.ss;
                int[] px = raster.render(r.scene, r.snap, PonderLooks.INSTANCE, w, h, (double) r.res * r.ss);
                int[] out = resolve(new PreviewRaster.Frame(w, h, px, new int[0]), r.ss, r.tw, r.th);
                List<StageRaster.TextMark> marks = new ArrayList<>(raster.labels());
                shown = new Shown(out, r.tw, r.th, r.ss, r.res, r.snap, raster.lastCamera(), marks, r.scene);
                seq++;
                rendered++;
                renderNs += System.nanoTime() - t0;
            } catch (RuntimeException e) {
                BuildBuddy.LOG.error("A lesson picture could not be drawn", e);
            } finally {
                busy.set(false);
                // a newer request came in while this one was drawn
                if (wanted != r && busy.compareAndSet(false, true)) work();
            }
        });
    }

    /** Averages the samples of each pixel and puts the colours in the texture's order (ABGR), on several threads. */
    private static int[] resolve(PreviewRaster.Frame f, int ss, int tw, int th) {
        int[] out = PreviewRaster.resolveArgb(f, ss, tw, th);
        java.util.stream.IntStream.range(0, th).parallel().forEach(yy -> {
            for (int xx = 0; xx < tw; xx++) {
                int c = out[yy * tw + xx];
                out[yy * tw + xx] = c & 0xFF00FF00 | (c & 255) << 16 | (c >> 16) & 255;
            }
        });
        return out;
    }

    private void upload(Shown sh) {
        if (texture == null || texW != sh.tw || texH != sh.th) {
            close();
            NativeImage img = new NativeImage(sh.tw, sh.th, true);
            texture = new Skin.Smooth("Build Buddy lesson", img, false);
            textureId = Identifier.fromNamespaceAndPath(BuildBuddy.MOD_ID, "ponder/" + System.nanoTime());
            mc.getTextureManager().register(textureId, texture);
            texW = sh.tw;
            texH = sh.th;
        }
        NativeImage img = texture.getPixels();
        if (img == null) return;
        img.getPixelBytes().order(ByteOrder.nativeOrder()).asIntBuffer().put(sh.abgr, 0, sh.tw * sh.th);
        texture.upload();
        uploadedSeq = seq;
    }

    /** Releases the texture; the view can be used again after (it makes a new one). */
    public void close() {
        // releasing closes the texture the manager holds
        if (textureId != null) mc.getTextureManager().release(textureId);
        else if (texture != null) texture.close();
        texture = null;
        textureId = null;
    }
}
