package io.github.profetgit.cyanotype.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import io.github.profetgit.cyanotype.Cyanotype;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The interface art: one atlas drawn at 4 texels per GUI unit (dev/ui/hires.py), sampled linearly so lines stay fine and
 * smooth at any GUI scale, plus two small tiles that repeat (the paper grid and the progress hatch). Sprites are drawn by
 * name at any size; the ones with a slice are cut into nine pieces and the edges and middle are stretched.
 */
public final class Skin {
    /** A rectangle of the atlas: texels in the file, and the size it has in GUI units. */
    record Sprite(int x, int y, int w, int h, int lw, int lh, int slice) {
    }

    /** A dynamic texture the game samples smoothly (the game's own ones are sharp when shrunk). */
    public static final class Smooth extends DynamicTexture {
        public Smooth(String label, NativeImage image, boolean repeat) {
            super(() -> label, image);
            this.sampler = repeat ? RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR) : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, "skin/atlas"), GRID = Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, "skin/grid"),
        HATCH = Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, "skin/hatch");
    private static final Map<String, Sprite> SPRITES = new HashMap<>();
    private static int texW, texH, density = 4, gridPx, hatchPx;
    private static boolean loaded, failed;

    private Skin() {
    }

    private static void load() {
        if (loaded || failed) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            JsonObject meta;
            try (InputStream in = open("ui/atlas.json")) {
                meta = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            }
            density = meta.get("r").getAsInt();
            JsonObject sprites = meta.getAsJsonObject("sprites");
            for (String name : sprites.keySet()) {
                JsonObject s = sprites.getAsJsonObject(name);
                SPRITES.put(name, new Sprite(s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("w").getAsInt(), s.get("h").getAsInt(),
                    s.get("lw").getAsInt(), s.get("lh").getAsInt(), s.get("slice").getAsInt()));
            }
            NativeImage atlas = image("ui/atlas.png");
            texW = atlas.getWidth();
            texH = atlas.getHeight();
            mc.getTextureManager().register(ATLAS, new Smooth("Cyanotype atlas", atlas, false));
            NativeImage grid = image("ui/grid.png");
            gridPx = grid.getWidth();
            mc.getTextureManager().register(GRID, new Smooth("Cyanotype grid", grid, true));
            NativeImage hatch = image("ui/hatch.png");
            hatchPx = hatch.getWidth();
            mc.getTextureManager().register(HATCH, new Smooth("Cyanotype hatch", hatch, true));
            loaded = true;
        } catch (IOException | RuntimeException e) {
            failed = true;
            Cyanotype.LOG.error("Cannot load the interface art", e);
        }
    }

    private static InputStream open(String path) throws IOException {
        return Minecraft.getInstance().getResourceManager().getResourceOrThrow(Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, path)).open();
    }

    private static NativeImage image(String path) throws IOException {
        try (InputStream in = open(path)) {
            return NativeImage.read(in);
        }
    }

    /** Texels per GUI unit in the art. */
    public static int density() {
        load();
        return density;
    }

    public static boolean has(String name) {
        load();
        return SPRITES.containsKey(name);
    }

    private static int white(float alpha) {
        return ((int) (Math.max(0, Math.min(1, alpha)) * 255) << 24) | 0xFFFFFF;
    }

    /** Draws a sprite at a size, with an opacity. */
    public static void blit(GuiGraphicsExtractor g, String name, int x, int y, int w, int h, float alpha) {
        blit(g, name, x, y, w, h, white(alpha));
    }

    /** Draws a sprite at a size; {@code color} multiplies it (ARGB, white leaves it as drawn). */
    public static void blit(GuiGraphicsExtractor g, String name, int x, int y, int w, int h, int color) {
        load();
        Sprite s = SPRITES.get(name);
        if (s == null || w <= 0 || h <= 0) return;
        if (s.slice() == 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, s.x(), s.y(), w, h, s.w(), s.h(), texW, texH, color);
            return;
        }
        int b = s.slice(), bt = b * density;
        // a nine-slice cannot be smaller than its two borders
        int cb = Math.min(b, Math.min(w, h) / 2);
        int[] sx = {s.x(), s.x() + bt, s.x() + s.w() - bt}, sw = {bt, s.w() - 2 * bt, bt};
        int[] sy = {s.y(), s.y() + bt, s.y() + s.h() - bt}, sh = {bt, s.h() - 2 * bt, bt};
        int[] dx = {x, x + cb, x + w - cb}, dw = {cb, w - 2 * cb, cb};
        int[] dy = {y, y + cb, y + h - cb}, dh = {cb, h - 2 * cb, cb};
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                if (dw[i] <= 0 || dh[j] <= 0) continue;
                g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, dx[i], dy[j], sx[i], sy[j], dw[i], dh[j], sw[i], sh[j], texW, texH, color);
            }
        }
    }

    /** The paper grid over a rectangle: lines every 4 units, fixed to the rectangle's corner. */
    public static void grid(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        tile(g, GRID, gridPx, x, y, w, h, 0, 0, alpha);
    }

    /** The progress hatch over a rectangle, drifting by {@code shiftUnits} GUI units along it. */
    public static void hatch(GuiGraphicsExtractor g, int x, int y, int w, int h, double shiftUnits) {
        tile(g, HATCH, hatchPx, x, y, w, h, (float) (shiftUnits * density), 0, 1f);
    }

    private static void tile(GuiGraphicsExtractor g, Identifier tex, int tilePx, int x, int y, int w, int h, float u, float v, float alpha) {
        load();
        if (!loaded || w <= 0 || h <= 0 || alpha <= 0.003f) return;
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, w * density, h * density, tilePx, tilePx, white(alpha));
    }
}
