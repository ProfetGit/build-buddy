package io.github.profetgit.buildbuddy.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.buildbuddy.BuildBuddy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The picture of the tool wheel, made at run time for however many tools there are: a ring cut into equal segments by fine
 * lines (halfway between two icons), a hub, and the lit wedge for one segment. Drawn at the art's density with a smooth
 * sampler, so the circle is round and the lines are fine at any GUI scale; the wedge is turned to the hovered segment by
 * the screen. Remade only when the number of tools or the size changes.
 */
final class WheelArt {
    private static final Identifier BASE = Identifier.fromNamespaceAndPath(BuildBuddy.MOD_ID, "skin/wheel_base"), WEDGE = Identifier.fromNamespaceAndPath(BuildBuddy.MOD_ID, "skin/wheel_wedge");
    private static Skin.Smooth baseTex, wedgeTex;
    private static int builtN = -1, builtSize = -1, texels;

    private static final int PANEL = 0x0E2A47, DEEP = 0x0A1B30, LINE = 0xE8F6FF, CYAN = 0x7FE3FF, DIM = 0x8BB6D8, BLUE = 0x2C66C9;

    private WheelArt() {
    }

    /** Radius of the hub, in GUI units, for a wheel of this diameter. */
    static double hub(int size) {
        return size / 2.0 * 0.4375;
    }

    static double outer(int size) {
        return size / 2.0 - 1;
    }

    static void ensure(int n, int size) {
        if (n == builtN && size == builtSize && baseTex != null) return;
        close();
        int r = Skin.density();
        texels = size * r;
        Minecraft mc = Minecraft.getInstance();
        baseTex = new Skin.Smooth("Build Buddy wheel", paint(n, size, r, false), false);
        wedgeTex = new Skin.Smooth("Build Buddy wheel wedge", paint(n, size, r, true), false);
        mc.getTextureManager().register(BASE, baseTex);
        mc.getTextureManager().register(WEDGE, wedgeTex);
        builtN = n;
        builtSize = size;
    }

    static void close() {
        if (baseTex != null) {
            Minecraft.getInstance().getTextureManager().release(BASE);
            Minecraft.getInstance().getTextureManager().release(WEDGE);
            baseTex = null;
            wedgeTex = null;
        }
        builtN = -1;
    }

    static void drawBase(GuiGraphicsExtractor g, int x, int y, int size, float alpha) {
        draw(g, BASE, x, y, size, alpha);
    }

    /** The lit wedge for segment 0 (straight up); the screen turns it. */
    static void drawWedge(GuiGraphicsExtractor g, int x, int y, int size, float alpha) {
        draw(g, WEDGE, x, y, size, alpha);
    }

    private static void draw(GuiGraphicsExtractor g, Identifier id, int x, int y, int size, float alpha) {
        if (alpha <= 0.003f) return;
        int color = ((int) (Math.min(1f, alpha) * 255) << 24) | 0xFFFFFF;
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, texels, texels, texels, texels, color);
    }

    // ---- painting

    /** Coverage of a shape from its signed distance in texels: 1 inside, 0 outside, a one-texel ramp across the edge. */
    private static double cov(double distTexels) {
        return Math.max(0, Math.min(1, 0.5 - distTexels));
    }

    /** Layers a colour with an opacity over a pixel held as non-premultiplied {r, g, b, a}. */
    private static void over(double[] px, int rgb, double a) {
        if (a <= 0) return;
        double ra = ((rgb >> 16) & 255) / 255.0, ga = ((rgb >> 8) & 255) / 255.0, ba = (rgb & 255) / 255.0;
        double outA = a + px[3] * (1 - a);
        px[0] = (ra * a + px[0] * px[3] * (1 - a)) / outA;
        px[1] = (ga * a + px[1] * px[3] * (1 - a)) / outA;
        px[2] = (ba * a + px[2] * px[3] * (1 - a)) / outA;
        px[3] = outA;
    }

    private static NativeImage paint(int n, int size, int density, boolean wedge) {
        int t = size * density;
        NativeImage img = new NativeImage(t, t, true);
        double rOut = outer(size), rIn = hub(size), step = WheelGeometry.step(n);
        double[] px = new double[4];
        for (int j = 0; j < t; j++) {
            for (int i = 0; i < t; i++) {
                double x = (i + 0.5 - t / 2.0) / density, y = (j + 0.5 - t / 2.0) / density;
                double r = Math.hypot(x, y);
                // clockwise from straight up, signed (-pi..pi) for the wedge
                double theta = Math.atan2(x, -y);
                px[0] = px[1] = px[2] = px[3] = 0;
                if (wedge) paintWedge(px, r, theta, rIn, rOut, step, density);
                else paintBase(px, r, theta, rIn, rOut, step, density);
                int a = (int) Math.round(px[3] * 255);
                int rr = (int) Math.round(px[0] * 255), gg = (int) Math.round(px[1] * 255), bb = (int) Math.round(px[2] * 255);
                img.setPixel(i, j, a << 24 | rr << 16 | gg << 8 | bb);
            }
        }
        return img;
    }

    /** A line of a width (GUI units) centred on a signed distance of zero. */
    private static double line(double dist, double width, int density) {
        return cov((Math.abs(dist) - width / 2) * density);
    }

    private static void paintBase(double[] px, double r, double theta, double rIn, double rOut, double step, int d) {
        double ring = cov(Math.max(r - rOut, rIn - r) * d);
        over(px, PANEL, 0.94 * ring);
        // the hub
        over(px, DEEP, 0.97 * cov((r - rIn) * d));
        // fine rings: an outer double line and the hub's edge
        over(px, CYAN, 0.95 * line(r - (rOut - 0.35), 0.7, d));
        over(px, DIM, 0.45 * line(r - (rOut - 2.6), 0.35, d));
        over(px, CYAN, 0.95 * line(r - rIn, 0.7, d));
        over(px, DIM, 0.4 * line(r - (rIn - 2.4), 0.35, d));
        // the lines between segments: halfway between two icons
        double m = ((theta - step / 2) % step + step) % step;
        double angular = Math.min(m, step - m);
        double perp = r * Math.sin(angular);
        double radial = cov(Math.max(rIn - r, r - (rOut - 0.35)) * d);
        over(px, CYAN, 0.85 * line(perp, 0.55, d) * radial);
        // a short heavier tick at the rim, like the marks on a dial
        double tick = cov(Math.max(r - (rOut - 0.35), (rOut - 3.4) - r) * d);
        over(px, LINE, 0.95 * line(perp, 1.0, d) * tick);
    }

    private static void paintWedge(double[] px, double r, double theta, double rIn, double rOut, double step, int d) {
        double dAng = r * Math.sin(Math.max(-Math.PI / 2, Math.min(Math.PI / 2, Math.abs(theta) - step / 2)));
        // the wedge is the segment pulled in a hair so the lines between segments stay visible around it
        double dist = Math.max(Math.max(r - (rOut - 0.35), rIn - r), dAng);
        // a point past a quarter turn from the wedge is never inside it
        if (Math.abs(theta) > step / 2 + Math.PI / 2) dist = Math.max(dist, 10);
        double fill = cov(dist * d);
        over(px, BLUE, fill);
        over(px, LINE, 0.95 * line(dist + 0.4, 0.7, d));
    }
}
