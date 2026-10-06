package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.cyanotype.Cyanotype;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What a block looks like in the previews: the real texture of each of its six faces, read through the game's own resource
 * manager, so a resource pack in use shows up (the pack's textures, its resolution, its colours). The faces come from the block's
 * baked model: for each direction the quad that faces that way, and the sprite on it. Blocks are drawn as full cubes (a stair or a
 * slab still shows its textures but not its shape). Biome-tinted textures (grass, leaves, water) get a fixed default tint.
 * Without a running game (unit tests) or when anything fails a block falls back to its map colour, so a preview never fails.
 *
 * <p>Looks are cached per block state and thrown away when the models are baked again (a resource reload): the cache belongs to
 * the {@code BlockStateModelSet} it was made from.
 */
public final class BlockLook {
    private BlockLook() {
    }

    /** One face's picture: ARGB texels, or null for a flat face of the average colour. {@code avg} is the average of the visible texels. */
    public record Tex(int w, int h, int @Nullable [] px, int avg) {
        public static Tex flat(int argb) {
            return new Tex(1, 1, null, argb | 0xFF000000);
        }
    }

    /** The six faces in the order +x, -x, +y, -y, +z, -z. */
    public record Look(Tex[] face) {
        public static Look solid(int argb) {
            Tex t = Tex.flat(argb);
            return new Look(new Tex[]{t, t, t, t, t, t});
        }

        /** The colour for the small previews: the average of the sides and the top, weighted toward the top as the eye sees it. */
        public int average() {
            int r = 0, g = 0, b = 0;
            int[] weight = {1, 1, 3, 0, 1, 1};
            int total = 0;
            for (int d = 0; d < 6; d++) {
                int c = face[d].avg();
                r += ((c >> 16) & 255) * weight[d];
                g += ((c >> 8) & 255) * weight[d];
                b += (c & 255) * weight[d];
                total += weight[d];
            }
            return 0xFF000000 | (r / total) << 16 | (g / total) << 8 | (b / total);
        }
    }

    private static final Map<BlockState, Look> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Tex> TEXTURES = new ConcurrentHashMap<>();
    private static volatile Object owner;
    private static volatile boolean warned;
    private static final Direction[] DIRS = {Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH};

    /** The look of a block state. Safe to call from any thread. */
    public static Look of(BlockState s) {
        Object current = currentModels();
        if (current != owner) {
            synchronized (BlockLook.class) {
                if (current != owner) {
                    CACHE.clear();
                    TEXTURES.clear();
                    owner = current;
                }
            }
        }
        Look have = CACHE.get(s);
        if (have != null) return have;
        Look made;
        try {
            made = current == null ? null : fromModel(s, (BlockStateModelSet) current);
        } catch (RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                Cyanotype.LOG.warn("No texture look for {} (map colours are used): {}", s, e.toString(), e);
            }
            made = null;
        }
        if (made == null) {
            if (!warned && current != null) {
                warned = true;
                Cyanotype.LOG.debug("No texture look for {}: the model gave no faces (map colours are used)", s);
            }
            made = Look.solid(mapColor(s));
        }
        CACHE.put(s, made);
        return made;
    }

    /** The colour of a block for the small previews. */
    public static int average(BlockState s) {
        return of(s).average();
    }

    static int mapColor(BlockState s) {
        int c = s.getBlock().defaultMapColor().col;
        if (c == 0) c = 0x9B9B9B;
        return c | 0xFF000000;
    }

    private static @Nullable Object currentModels() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getModelManager() == null) return null;
            return mc.getModelManager().getBlockStateModelSet();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static @Nullable Look fromModel(BlockState s, BlockStateModelSet models) {
        BlockStateModel model = models.get(s);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        if (parts.isEmpty()) return null;
        Tex[] faces = new Tex[6];
        BakedQuad any = null;
        for (BlockStateModelPart part : parts) {
            for (int d = 0; d < 6; d++) {
                if (faces[d] != null) continue;
                List<BakedQuad> quads = part.getQuads(DIRS[d]);
                if (!quads.isEmpty()) faces[d] = texOf(quads.get(0), s);
            }
            if (any == null) {
                List<BakedQuad> loose = part.getQuads(null);
                if (!loose.isEmpty()) any = loose.get(0);
            }
        }
        // a block with no quad on a side (a plant drawn as two crossed planes, a torch) shows what it has on every side
        Tex fallback = null;
        for (Tex t : faces) if (t != null) fallback = t;
        if (fallback == null && any != null) fallback = texOf(any, s);
        if (fallback == null) return null;
        for (int d = 0; d < 6; d++) if (faces[d] == null) faces[d] = fallback;
        return new Look(faces);
    }

    private static Tex texOf(BakedQuad quad, BlockState s) {
        Identifier name = quad.materialInfo().sprite().contents().name();
        int tint = quad.materialInfo().isTinted() ? tintOf(s) : 0xFFFFFFFF;
        String key = name + "#" + Integer.toHexString(tint);
        Tex have = TEXTURES.get(key);
        if (have != null) return have;
        Tex made = read(name, tint);
        TEXTURES.put(key, made);
        return made;
    }

    private static int tintOf(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof LiquidBlock) return 0xFF3F76E4;
        if (b instanceof LeavesBlock || b instanceof VineBlock) return 0xFF4FA030;
        if (b instanceof VegetationBlock) return 0xFF79C05A;
        return 0xFF79C05A;
    }

    /** Reads a block texture through the resource manager (so the active packs apply); the first frame of an animated one. */
    private static Tex read(Identifier sprite, int tint) {
        Identifier file = Identifier.fromNamespaceAndPath(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
        if (res.isEmpty()) return Tex.flat(0xFFFF00FF);
        try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            int w = img.getWidth(), h = Math.min(img.getHeight(), img.getWidth());
            int[] px = new int[w * h];
            long r = 0, g = 0, b = 0, n = 0;
            boolean cut = false;
            int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = img.getPixel(x, y);
                    int a = c >>> 24;
                    if (tint != 0xFFFFFFFF) {
                        int rr = ((c >> 16) & 255) * tr / 255, gg = ((c >> 8) & 255) * tg / 255, bb = (c & 255) * tb / 255;
                        c = a << 24 | rr << 16 | gg << 8 | bb;
                    }
                    px[y * w + x] = c;
                    if (a < 128) {
                        cut = true;
                    } else {
                        r += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                        n++;
                    }
                }
            }
            if (n == 0) return new Tex(w, h, px, 0xFF000000 | 0x808080);
            int avg = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            return new Tex(w, h, px, avg);
        } catch (java.io.IOException | RuntimeException e) {
            return Tex.flat(0xFFFF00FF);
        }
    }
}
