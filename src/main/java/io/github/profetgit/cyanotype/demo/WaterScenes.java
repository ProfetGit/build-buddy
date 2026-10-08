package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.G;
import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.camera;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;

/** A ghost standing half in a pool of water: the part under the water must show through the surface (from above and from inside). */
final class WaterScenes {
    private static final int PX = 60, PZ = 60, SIDE = 12, DEEP = 4;
    private static final int GX = 63, GZ = 63;
    private static final double TX = 66, TY = G, TZ = 66;
    private static final Map<String, int[]> GRABS = new ConcurrentHashMap<>();

    private static final int MOVED = 18;

    private record View(String name, double ex, double ey, double ez, double share, float yaw, float pitch) {
        View(String name, double ex, double ey, double ez, double share) {
            this(name, ex, ey, ez, share, (float) Math.toDegrees(Math.atan2(-(TX - ex), TZ - ez)), (float) -Math.toDegrees(Math.atan2(TY - ey, Math.hypot(TX - ex, TZ - ez))));
        }
    }

    private static final View[] VIEWS = {
        new View("steep", 66, G + 16, 70, 0.25),
        new View("angle45", 66, G + 10, 76, 0.25),
        new View("shallow", 66, G + 3, 82, 0.04),
        new View("inside", 66, G - 1.4, 76, 0.25),
    };

    private WaterScenes() {
    }

    static void water() {
        Director.cmd("gamemode spectator Builder");
        pool();
        run("classic", "water");
        act(() -> Minecraft.getInstance().options.improvedTransparency().set(true));
        waitTicks(20);
        run("improved transparency", "water_oit");
        act(() -> Minecraft.getInstance().options.improvedTransparency().set(false));
        waitTicks(10);
        drain();
    }

    private static void pool() {
        Director.clean();
        Director.cmd(
            "fill " + (PX - 2) + " " + (G - DEEP - 1) + " " + (PZ - 2) + " " + (PX + SIDE + 1) + " " + (G + 8) + " " + (PZ + SIDE + 1) + " air",
            "fill " + (PX - 2) + " " + (G - DEEP - 2) + " " + (PZ - 2) + " " + (PX + SIDE + 1) + " " + (G - DEEP - 2) + " " + (PZ + SIDE + 1) + " dirt",
            "fill " + (PX - 2) + " " + (G - DEEP - 1) + " " + (PZ - 2) + " " + (PX + SIDE + 1) + " " + (G - DEEP - 1) + " " + (PZ + SIDE + 1) + " sand",
            "fill " + (PX - 2) + " " + (G - DEEP) + " " + (PZ - 2) + " " + (PX + SIDE + 1) + " " + G + " " + (PZ + SIDE + 1) + " grass_block",
            "fill " + PX + " " + (G - DEEP) + " " + PZ + " " + (PX + SIDE - 1) + " " + G + " " + (PZ + SIDE - 1) + " water");
        waitTicks(60);
        act(() -> {
            var mc = Minecraft.getInstance();
            check("water/the pool is water", !mc.level.getFluidState(new BlockPos(PX + 5, G, PZ + 5)).isEmpty() && !mc.level.getFluidState(new BlockPos(PX + 5, G - DEEP, PZ + 5)).isEmpty(), "pool filled");
        });

    }

    private static void run(String label, String prefix) {
        Placement[] pl = new Placement[1];
        // 1. only the underwater part is a ghost: every pixel that changes when it is switched off is the ghost seen through the water
        act(() -> {
            pl[0] = Director.locked(new Placement("under", Samples.uniform(6, DEEP - 1, 6), "cyanotype:under.litematic", Director.DIM, new BlockPos(GX, G - DEEP + 1, GZ), Orientation.NONE));
            Placements.add(pl[0]);
        });
        until("water/" + label + ": under-only ghost baked", 600, GhostRenderer::settled);
        waitTicks(30);
        for (View v : VIEWS) {
            final View vv = v;
            look(v);
            waitTicks(25);
            grab("on_" + v.name);
            act(() -> pl[0].opacity = 0f);
            waitTicks(12);
            grab("off_" + v.name);
            waitTicks(7);
            grab("off2_" + v.name);
            act(() -> {
                pl[0].opacity = 0.6f;
                int[] rect = rectOf(vv, GRABS.get("on_" + vv.name));
                int[] d = diff(GRABS.get("on_" + vv.name), GRABS.get("off_" + vv.name), rect);
                writeDiff(prefix + "_under_" + vv.name, GRABS.get("on_" + vv.name), GRABS.get("off_" + vv.name), rect);
                int[] noise = diff(GRABS.get("off_" + vv.name), GRABS.get("off2_" + vv.name), rect);
                int area = Math.max(1, (rect[2] - rect[0]) * (rect[3] - rect[1]));
                int net = d[0] - noise[0];
                check("water/" + label + ": under-only ghost shows from " + vv.name, net >= area * vv.share,
                    String.format(Locale.ROOT, "%d of the %d pixels where the underwater ghost projects change when it is switched off (%.0f%%, need %.0f%%; %d changed, %d of them the water moving), strongest change %d", net, area, 100.0 * net / area, 100 * vv.share, d[0], noise[0], d[1]));
            });
            waitTicks(12);
        }
        act(() -> Placements.remove(pl[0]));
        waitTicks(10);

        // 2. half in, half out: the stills
        act(() -> {
            pl[0] = Director.locked(new Placement("half", Samples.uniform(6, 6, 6), "cyanotype:half.litematic", Director.DIM, new BlockPos(GX, G - DEEP + 1, GZ), Orientation.NONE));
            Placements.add(pl[0]);
        });
        until("water/" + label + ": half-in ghost baked", 600, GhostRenderer::settled);
        waitTicks(30);
        for (View v : VIEWS) {
            look(v);
            waitTicks(25);
            shot(prefix + "_" + v.name);
        }
        act(() -> {
            check("water/" + label + ": the ghost is still drawn at the end", GhostRenderer.drawn(pl[0]) && GhostRenderer.staleSections() == 0, "drawn " + GhostRenderer.drawn(pl[0]));
            Placements.remove(pl[0]);
        });
    }

    private static void drain() {
        Director.cmd("fill " + (PX - 2) + " " + (G - DEEP - 2) + " " + (PZ - 2) + " " + (PX + SIDE + 1) + " " + (G - DEEP - 2) + " " + (PZ + SIDE + 1) + " grass_block",
            "fill " + PX + " " + (G - DEEP) + " " + PZ + " " + (PX + SIDE - 1) + " " + G + " " + (PZ + SIDE - 1) + " grass_block");
        waitTicks(6);
    }

    private static void look(View v) {
        // a mod may have opened a screen over the picture (a chat with a keyboard, say)
        act(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui.screen() != null) mc.gui.setScreen(null);
        });
        camera(v.ex, v.ey - 1.62, v.ez, v.yaw, v.pitch);
        waitTicks(4);
    }

    /** The screen rectangle (x0, y0, x1, y1) the underwater ghost's box projects to from a view, clipped to the picture. */
    private static int[] rectOf(View v, int[] frame) {
        int w = frame[0], h = frame[1];
        double yaw = Math.toRadians(v.yaw), pitch = Math.toRadians(v.pitch);
        double[] f = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] r = {-f[2], 0, f[0]};
        double rl = Math.hypot(r[0], r[2]);
        r[0] /= rl;
        r[2] /= rl;
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        double tan = Math.tan(Math.toRadians(Integer.getInteger("cyanotype.demo.fov", 70)) / 2);
        double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
        for (int c = 0; c < 8; c++) {
            double[] d = {GX + (c & 1) * 6 - v.ex, G - DEEP + 1 + ((c >> 1) & 1) * (DEEP - 1) - v.ey, GZ + ((c >> 2) & 1) * 6 - v.ez};
            double fz = d[0] * f[0] + d[1] * f[1] + d[2] * f[2];
            if (fz < 0.2) return new int[]{0, 0, w, h};
            double sx = (d[0] * r[0] + d[1] * r[1] + d[2] * r[2]) / fz / (tan * w / h);
            double sy = (d[0] * u[0] + d[1] * u[1] + d[2] * u[2]) / fz / tan;
            x0 = Math.min(x0, (1 + sx) * w / 2);
            x1 = Math.max(x1, (1 + sx) * w / 2);
            y0 = Math.min(y0, (1 - sy) * h / 2);
            y1 = Math.max(y1, (1 - sy) * h / 2);
        }
        return new int[]{(int) Math.max(0, x0), (int) Math.max(0, y0), (int) Math.min(w, x1), (int) Math.min(h, y1)};
    }

    /** Reads the finished frame into memory (width, height, then ARGB-agnostic pixels). */
    private static void grab(String key) {
        int[] state = {0};
        Director.add(mc -> {
            if (state[0] == 0) {
                state[0] = 1;
                Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage img) -> {
                    try (img) {
                        int w = img.getWidth(), h = img.getHeight();
                        int[] px = new int[2 + w * h];
                        px[0] = w;
                        px[1] = h;
                        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[2 + y * w + x] = img.getPixel(x, y);
                        GRABS.put(key, px);
                    }
                });
                return false;
            }
            return GRABS.containsKey(key);
        });
    }

    /** The ghost-on picture with every pixel the ghost changed painted magenta over a dimmed copy: a still to look at. */
    private static void writeDiff(String name, int[] a, int[] b, int[] rect) {
        if (a == null || b == null || a.length != b.length) return;
        int w = a[0], h = a[1];
        try (NativeImage img = new NativeImage(w, h, false)) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int p = a[2 + y * w + x], q = b[2 + y * w + x];
                int d = Math.abs((p & 255) - (q & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
                boolean edge = (x == rect[0] || x == rect[2] - 1) && y >= rect[1] && y < rect[3] || (y == rect[1] || y == rect[3] - 1) && x >= rect[0] && x < rect[2];
                img.setPixelABGR(x, y, edge ? 0xFF00FFFF : d > MOVED ? 0xFFFF00FF : 0xFF000000 | (((p & 255) >> 1) | (((p >> 8) & 255) >> 1) << 8 | (((p >> 16) & 255) >> 1) << 16));
            }
            java.nio.file.Files.createDirectories(Director.OUT);
            img.writeToFile(Director.OUT.resolve(name + "_diff.png"));
        } catch (Exception e) {
            System.out.println("[cydemo] diff still not written: " + e);
        }
    }

    /** {pixels whose colour moved by more than MOVED over the three channels, strongest move} */
    private static int[] diff(int[] a, int[] b, int[] rect) {
        if (a == null || b == null || a.length != b.length) return new int[]{0, 0};
        int n = 0, max = 0, w = a[0];
        for (int y = rect[1]; y < rect[3]; y++) for (int x = rect[0]; x < rect[2]; x++) {
            int i = 2 + y * w + x;
            int p = a[i], q = b[i];
            int d = Math.abs((p & 255) - (q & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
            if (d > MOVED) n++;
            max = Math.max(max, d);
        }
        return new int[]{n, max};
    }
}
