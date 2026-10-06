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
import net.minecraft.client.model.geom.builders.UVPair;
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

    /** One quad of a block's model: corners in the block's own space (0..1), the texture coordinates of each corner (0..1 across the texture, v down), its picture, and the way it faces for the light. */
    public record Quad(float[] p, float[] uv, Tex tex, int dir) {
    }

    /**
     * The six faces in the order +x, -x, +y, -y, +z, -z, and for a block that is not a plain cube (a stair, a slab, a torch, grass,
     * bamboo, a fence...) its model: the quads to draw instead of the cube. {@code model} is null for a cube.
     */
    public record Look(Tex[] face, Quad @Nullable [] model, boolean translucent) {
        public Look(Tex[] face) {
            this(face, null, false);
        }

        public Look(Tex[] face, Quad @Nullable [] model) {
            this(face, model, false);
        }

        /** A plain cube that hides what is behind it (not glass-like water: that one is see-through). */
        public boolean cube() {
            return model == null;
        }

        public static Look solid(int argb) {
            Tex t = Tex.flat(argb);
            return new Look(new Tex[]{t, t, t, t, t, t}, null);
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
        // water has none either: a see-through blue cube, drawn lower at its surface by the preview
        if (s.getBlock() == net.minecraft.world.level.block.Blocks.WATER) {
            Tex t = Tex.flat(0xFF3F76E4);
            return new Look(new Tex[]{t, t, t, t, t, t}, null, true);
        }
        // a banner has no baked model (the game draws it with its own renderer): a pole and a flag of its colour are built here
        if (s.getBlock() instanceof net.minecraft.world.level.block.AbstractBannerBlock banner) return banner(s, banner.getColor().getTextureDiffuseColor() | 0xFF000000);
        BlockStateModel model = models.get(s);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        if (parts.isEmpty()) return null;
        boolean leaves = s.getBlock() instanceof LeavesBlock;
        Tex[] faces = new Tex[6];
        boolean[] full = new boolean[6];
        List<BakedQuad> all = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (int d = 0; d < 6; d++) {
                for (BakedQuad q : part.getQuads(DIRS[d])) {
                    all.add(q);
                    if (faces[d] == null) faces[d] = texOf(q, s, leaves);
                    if (coversFace(q, d)) full[d] = true;
                }
            }
            all.addAll(part.getQuads(null));
        }
        // a block with no quad on a side (a plant drawn as two crossed planes, a torch) shows what it has on every side
        Tex fallback = null;
        for (Tex t : faces) if (t != null) fallback = t;
        if (fallback == null && !all.isEmpty()) fallback = texOf(all.get(0), s, leaves);
        if (fallback == null) return null;
        for (int d = 0; d < 6; d++) if (faces[d] == null) faces[d] = fallback;
        boolean cube = true;
        for (boolean f : full) cube &= f;
        if (cube) return new Look(faces, null);
        Quad[] quads = new Quad[all.size()];
        for (int i = 0; i < quads.length; i++) quads[i] = quadOf(all.get(i), s, leaves);
        return new Look(faces, quads);
    }

    /**
     * A banner: the pole and the bar are brown boxes, the flag a double-sided quad of the banner's colour (the patterns are not
     * drawn). A standing banner turns in sixteenths of a circle; a wall banner hangs flat against its wall. Sizes follow the game's
     * own model (a flag 20 by 40 units, scaled by two thirds).
     */
    private static Look banner(BlockState s, int color) {
        Tex cloth = Tex.flat(color), wood = Tex.flat(0xFF8B6B3F);
        java.util.List<Quad> q = new ArrayList<>();
        double turn;
        double fz;
        boolean wall = s.getBlock() instanceof net.minecraft.world.level.block.WallBannerBlock;
        if (wall) {
            Direction f = s.getValue(net.minecraft.world.level.block.WallBannerBlock.FACING);
            turn = switch (f) {
                case SOUTH -> 0;
                case WEST -> Math.PI / 2;
                case NORTH -> Math.PI;
                default -> -Math.PI / 2;
            };
            fz = -0.4;
        } else {
            turn = s.getValue(net.minecraft.world.level.block.BannerBlock.ROTATION) * Math.PI / 8;
            fz = 0;
        }
        double c = Math.cos(turn), n = Math.sin(turn);
        double half = 0.4167, top = wall ? 1.0 : 1.75, bottom = top - 1.667;
        if (!wall) addBox(q, 0.4583, 0, -0.0417, 0.5417, 1.75, 0.0417, wood, c, n);
        addBox(q, 0.5 - half, top, fz - 0.0417, 0.5 + half, top + 0.0833, fz + 0.0417, wood, c, n);
        float[][] flag = {{(float) (0.5 - half), (float) top, (float) fz}, {(float) (0.5 + half), (float) top, (float) fz}, {(float) (0.5 + half), (float) bottom, (float) fz}, {(float) (0.5 - half), (float) bottom, (float) fz}};
        q.add(quadOf(flag, cloth, 4, c, n));
        Tex[] faces = {cloth, cloth, cloth, cloth, cloth, cloth};
        return new Look(faces, q.toArray(new Quad[0]));
    }

    private static void addBox(List<Quad> out, double x0, double y0, double z0, double x1, double y1, double z1, Tex t, double c, double n) {
        float[][] v = {
            {(float) x0, (float) y0, (float) z0}, {(float) x1, (float) y0, (float) z0}, {(float) x1, (float) y1, (float) z0}, {(float) x0, (float) y1, (float) z0},
            {(float) x0, (float) y0, (float) z1}, {(float) x1, (float) y0, (float) z1}, {(float) x1, (float) y1, (float) z1}, {(float) x0, (float) y1, (float) z1}};
        int[][] side = {{1, 5, 6, 2}, {4, 0, 3, 7}, {3, 2, 6, 7}, {0, 4, 5, 1}, {5, 4, 7, 6}, {0, 1, 2, 3}};
        int[] dir = {0, 1, 2, 3, 4, 5};
        for (int i = 0; i < 6; i++) out.add(quadOf(new float[][]{v[side[i][0]], v[side[i][1]], v[side[i][2]], v[side[i][3]]}, t, dir[i], c, n));
    }

    /** A quad from four corners (in block space, turned about the block's vertical axis through its middle). */
    private static Quad quadOf(float[][] corners, Tex t, int dir, double c, double n) {
        float[] p = new float[12];
        for (int i = 0; i < 4; i++) {
            double x = corners[i][0] - 0.5, z = corners[i][2] - 0.5;
            p[i * 3] = (float) (0.5 + x * c - z * n);
            p[i * 3 + 1] = corners[i][1];
            p[i * 3 + 2] = (float) (0.5 + x * n + z * c);
        }
        return new Quad(p, new float[]{0, 1, 0, 0, 1, 0, 1, 1}, t, dir);
    }

    /** Whether a quad is a whole face of the cube: flat on that side of the block and as big as the block. */
    private static boolean coversFace(BakedQuad q, int d) {
        int axis = d / 2;
        float plane = d % 2 == 0 ? 1f : 0f;
        float lo1 = Float.MAX_VALUE, hi1 = -Float.MAX_VALUE, lo2 = Float.MAX_VALUE, hi2 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float[] c = {q.position(i).x(), q.position(i).y(), q.position(i).z()};
            if (Math.abs(c[axis] - plane) > 1e-3f) return false;
            float a = c[(axis + 1) % 3], b = c[(axis + 2) % 3];
            lo1 = Math.min(lo1, a);
            hi1 = Math.max(hi1, a);
            lo2 = Math.min(lo2, b);
            hi2 = Math.max(hi2, b);
        }
        return lo1 < 1e-3f && hi1 > 1 - 1e-3f && lo2 < 1e-3f && hi2 > 1 - 1e-3f;
    }

    private static Quad quadOf(BakedQuad q, BlockState s, boolean leaves) {
        float[] p = new float[12], uv = new float[8];
        var sprite = q.materialInfo().sprite();
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        for (int i = 0; i < 4; i++) {
            p[i * 3] = q.position(i).x();
            p[i * 3 + 1] = q.position(i).y();
            p[i * 3 + 2] = q.position(i).z();
            long packed = q.packedUV(i);
            float u = UVPair.unpackU(packed), v = UVPair.unpackV(packed);
            uv[i * 2] = u1 == u0 ? 0 : (u - u0) / (u1 - u0);
            uv[i * 2 + 1] = v1 == v0 ? 0 : (v - v0) / (v1 - v0);
        }
        Direction dir = q.direction();
        // Direction's own order is down, up, north, south, west, east; the previews use +x, -x, +y, -y, +z, -z
        int mapped = switch (dir == null ? Direction.UP : dir) {
            case EAST -> 0;
            case WEST -> 1;
            case UP -> 2;
            case DOWN -> 3;
            case SOUTH -> 4;
            case NORTH -> 5;
        };
        return new Quad(p, uv, texOf(q, s, leaves), mapped);
    }

    private static Tex texOf(BakedQuad quad, BlockState s, boolean leaves) {
        Identifier name = quad.materialInfo().sprite().contents().name();
        int tint = quad.materialInfo().isTinted() ? tintOf(s) : 0xFFFFFFFF;
        String key = name + "#" + Integer.toHexString(tint) + (leaves ? "L" : "");
        Tex have = TEXTURES.get(key);
        if (have != null) return have;
        Tex made = read(name, tint, leaves);
        TEXTURES.put(key, made);
        return made;
    }

    private static int tintOf(BlockState s) {
        Block b = s.getBlock();
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (b instanceof LiquidBlock) return 0xFF3F76E4;
        // these leaves have a tinted model but no colour provider in the game: they are drawn as the texture is
        if (id.equals("cherry_leaves") || id.equals("azalea_leaves") || id.equals("flowering_azalea_leaves") || id.equals("pale_oak_leaves")) return 0xFFFFFFFF;
        if (b instanceof LeavesBlock) {
            if (id.startsWith("birch")) return 0xFF80A755;
            if (id.startsWith("spruce")) return 0xFF619961;
            return 0xFF77AB2F;
        }
        if (b instanceof VineBlock || id.equals("leaf_litter")) return 0xFF77AB2F;
        if (id.equals("lily_pad")) return 0xFF208030;
        return 0xFF91BD59;
    }

    /** Reads a block texture through the resource manager (so the active packs apply); the first frame of an animated one. */
    private static Tex read(Identifier sprite, int tint, boolean solid) {
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
            if (solid) {
                // leaves are drawn solid: the holes in the texture are the dark inside of the foliage, so nothing can be clicked through
                int dark = 0xFF000000 | (int) (r / n * 0.45) << 16 | (int) (g / n * 0.45) << 8 | (int) (b / n * 0.45);
                for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) < 128) px[i] = dark;
            }
            return new Tex(w, h, px, avg);
        } catch (java.io.IOException | RuntimeException e) {
            return Tex.flat(0xFFFF00FF);
        }
    }
}
