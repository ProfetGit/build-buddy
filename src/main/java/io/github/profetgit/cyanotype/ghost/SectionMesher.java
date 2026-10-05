package io.github.profetgit.cyanotype.ghost;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns one box of a placed region into vertices, with vanilla's own block renderer: its face culling against the
 * neighbours inside the placement, its tints and offsets, its models. Runs on a worker thread. The result is block-format
 * vertices relative to the box's min corner, one mesh per section (see {@link Ghost}).
 */
final class SectionMesher {
    /** The result and the memory behind it; close both once the mesh is uploaded. */
    record Baked(MeshData mesh, ByteBufferBuilder bytes, int quads) implements AutoCloseable {
        @Override
        public void close() {
            mesh.close();
            bytes.close();
        }
    }

    private SectionMesher() {
    }

    static RenderType renderType() {
        return RenderTypes.translucentMovingBlock();
    }

    /**
     * @param region the placed region; the box {@code x0..x0+w} etc. is in its own turned coordinates
     * @param wx world position of the region's min corner
     * @return null when the box has nothing to draw
     */
    static Baked bake(OrientedRegion region, int wx, int wy, int wz, int x0, int y0, int z0, int w, int h, int d, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        PlacementView view = new PlacementView(region, wx, wy, wz, level);
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
        RenderType type = renderType();
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 16);
        BufferBuilder builder = new BufferBuilder(bytes, type.primitiveTopology(), type.format());
        PoseStack ps = new PoseStack();
        int[] quads = new int[1];
        BlockQuadOutput out = (x, y, z, quad, instance) -> {
            instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
            ps.pushPose();
            ps.translate(x, y, z);
            builder.putBakedQuad(ps.last(), quad, instance);
            ps.popPose();
            quads[0]++;
        };
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        var models = mc.getModelManager().getBlockStateModelSet();
        for (int y = y0; y < y0 + h; y++) {
            for (int z = z0; z < z0 + d; z++) {
                for (int x = x0; x < x0 + w; x++) {
                    BlockState state = region.state(x, y, z);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
                    pos.set(wx + x, wy + y, wz + z);
                    BlockStateModel model = models.get(state);
                    renderer.tesselateBlock(out, x - x0, y - y0, z - z0, view, pos, state, model, state.getSeed(pos));
                }
            }
        }
        MeshData mesh = builder.build();
        if (mesh == null) {
            bytes.close();
            return null;
        }
        return new Baked(mesh, bytes, quads[0]);
    }
}
