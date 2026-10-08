package io.github.profetgit.cyanotype.ghost;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import io.github.profetgit.cyanotype.verify.Verifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns one box of a placed region into vertices, with vanilla's own block renderer: its face culling against the
 * neighbours inside the placement, its tints and offsets, its models. Runs on a worker thread. The result is block-format
 * vertices relative to the box's min corner, one mesh per section (see {@link Ghost}), with the quads in layer order so
 * that a range of layers is a range of quads.
 *
 * <p>With a status mask (from the verifier) the blocks that are already right are left out, and the wrong ones are tinted
 * red and drawn a hair larger so they do not fight the real block they overlap.
 */
final class SectionMesher {
    /**
     * The result and the memory behind it; close both once the mesh is uploaded. {@code layerQuads} counts the dry quads up to
     * the end of each layer; {@code wetQuads} (null when nothing stands in water) is the same for the quads that come after them
     * and belong to blocks standing in water, which are drawn before the water is.
     */
    record Baked(MeshData mesh, ByteBufferBuilder bytes, int quads, int[] layerQuads, int[] wetQuads) implements AutoCloseable {
        @Override
        public void close() {
            mesh.close();
            bytes.close();
        }
    }

    /** A cap mesh: the quads of one layer that the layer next to it hides in the whole build. */
    record BakedCap(MeshData mesh, ByteBufferBuilder bytes, int quads) implements AutoCloseable {
        @Override
        public void close() {
            mesh.close();
            bytes.close();
        }
    }

    private static final int WRONG_TINT = 0xFFFF4A4A;
    private static final float WRONG_GROW = 1.012f;

    private SectionMesher() {
    }

    static RenderType renderType() {
        return RenderTypes.translucentMovingBlock();
    }

    /**
     * @param region the placed region; the box {@code x0..x0+w} etc. is in its own turned coordinates
     * @param wx world position of the region's min corner
     * @param mask the verifier's states of the box (y, then z, then x), or null to draw every block
     * @param wet which blocks of the box stand in water (same order), or null when none does: their quads come last, in layer order too
     * @param format the vertex layout the draw will expect (a shader pack changes it): decided on the render thread when the bake starts
     * @return null when the box has nothing to draw
     */
    static Baked bake(OrientedRegion region, int wx, int wy, int wz, int x0, int y0, int z0, int w, int h, int d, ClientLevel level, byte[] mask, byte[] wet, VertexFormat format) {
        Minecraft mc = Minecraft.getInstance();
        PlacementView view = new PlacementView(region, wx, wy, wz, level);
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
        RenderType type = renderType();
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 16);
        BufferBuilder builder = new BufferBuilder(bytes, type.primitiveTopology(), format);
        PoseStack ps = new PoseStack();
        int[] quads = new int[1];
        boolean[] wrong = new boolean[1];
        BlockQuadOutput out = (x, y, z, quad, instance) -> {
            instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
            ps.pushPose();
            ps.translate(x, y, z);
            if (wrong[0]) {
                instance.multiplyColor(WRONG_TINT);
                ps.translate(0.5f, 0.5f, 0.5f);
                ps.scale(WRONG_GROW, WRONG_GROW, WRONG_GROW);
                ps.translate(-0.5f, -0.5f, -0.5f);
            }
            builder.putBakedQuad(ps.last(), quad, instance);
            ps.popPose();
            quads[0]++;
        };
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        var models = mc.getModelManager().getBlockStateModelSet();
        int[] layerQuads = new int[h + 1];
        int[] wetQuads = wet == null ? null : new int[h + 1];
        // the dry blocks first, then the ones standing in water: both in layer order
        for (int pass = 0; pass < (wet == null ? 1 : 2); pass++) {
            if (pass == 1) wetQuads[0] = quads[0];
            for (int y = y0; y < y0 + h; y++) {
                for (int z = z0; z < z0 + d; z++) {
                    for (int x = x0; x < x0 + w; x++) {
                        int cell = ((y - y0) * d + (z - z0)) * w + (x - x0);
                        if (wet != null && (wet[cell] != 0) != (pass == 1)) continue;
                        byte status = mask == null ? Verifier.MISSING : mask[cell];
                        if (status == Verifier.CORRECT) continue;
                        BlockState state = region.state(x, y, z);
                        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
                        wrong[0] = status == Verifier.WRONG;
                        pos.set(wx + x, wy + y, wz + z);
                        BlockStateModel model = models.get(state);
                        renderer.tesselateBlock(out, x - x0, y - y0, z - z0, view, pos, state, model, state.getSeed(pos));
                    }
                }
                (pass == 0 ? layerQuads : wetQuads)[y - y0 + 1] = quads[0];
            }
        }
        MeshData mesh = builder.build();
        if (mesh == null) {
            bytes.close();
            return null;
        }
        return new Baked(mesh, bytes, quads[0], layerQuads, wetQuads != null && wetQuads[h] > wetQuads[0] ? wetQuads : null);
    }

    /**
     * The faces of one layer of a box that vanilla's culling leaves out because the block above (or below) has a face
     * against them: with the layers above cut away by the Layers tool those faces are open air and must be drawn. Each block
     * of the layer is meshed twice, as the build is and with its neighbour above (or below) read as air; the quads only the
     * second pass makes are the cap.
     *
     * @param ly the layer, counted from the box's bottom
     * @param top true for the faces that point up (the layer above is cut away), false for those that point down
     * @return null when the layer has no such faces
     */
    static BakedCap bakeCap(OrientedRegion region, int wx, int wy, int wz, int x0, int y0, int z0, int w, int h, int d, int ly, boolean top, ClientLevel level, byte[] mask, VertexFormat format) {
        Minecraft mc = Minecraft.getInstance();
        PlacementView view = new PlacementView(region, wx, wy, wz, level);
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
        RenderType type = renderType();
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 12);
        BufferBuilder builder = new BufferBuilder(bytes, type.primitiveTopology(), format);
        PoseStack ps = new PoseStack();
        int[] quads = new int[1];
        boolean[] wrong = new boolean[1];
        java.util.Set<BakedQuad> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        BlockQuadOutput collect = (x, y, z, quad, instance) -> seen.add(quad);
        BlockQuadOutput out = (x, y, z, quad, instance) -> {
            if (seen.contains(quad)) return;
            instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
            ps.pushPose();
            ps.translate(x, y, z);
            if (wrong[0]) {
                instance.multiplyColor(WRONG_TINT);
                ps.translate(0.5f, 0.5f, 0.5f);
                ps.scale(WRONG_GROW, WRONG_GROW, WRONG_GROW);
                ps.translate(-0.5f, -0.5f, -0.5f);
            }
            builder.putBakedQuad(ps.last(), quad, instance);
            ps.popPose();
            quads[0]++;
        };
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        var models = mc.getModelManager().getBlockStateModelSet();
        int y = y0 + ly;
        for (int z = z0; z < z0 + d; z++) {
            for (int x = x0; x < x0 + w; x++) {
                byte status = mask == null ? Verifier.MISSING : mask[((y - y0) * d + (z - z0)) * w + (x - x0)];
                if (status == Verifier.CORRECT) continue;
                BlockState state = region.state(x, y, z);
                if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
                // nothing to uncover when the neighbour is air already
                if (region.state(x, y + (top ? 1 : -1), z).isAir()) continue;
                wrong[0] = status == Verifier.WRONG;
                pos.set(wx + x, wy + y, wz + z);
                BlockStateModel model = models.get(state);
                long seed = state.getSeed(pos);
                seen.clear();
                renderer.tesselateBlock(collect, x - x0, y - y0, z - z0, view, pos, state, model, seed);
                view.hide(top ? pos.above() : pos.below());
                renderer.tesselateBlock(out, x - x0, y - y0, z - z0, view, pos, state, model, seed);
                view.unhide();
            }
        }
        MeshData mesh = builder.build();
        if (mesh == null) {
            bytes.close();
            return null;
        }
        return new BakedCap(mesh, bytes, quads[0]);
    }
}
