package io.github.profetgit.cyanotype.ghost;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * What the vanilla block renderer sees while it meshes a ghost: the placed region in world coordinates (so offsets and
 * seeds match the real block's), and the real world's biome tints. No light engine: the mesher paints full brightness.
 */
final class PlacementView implements BlockAndTintGetter {
    private final OrientedRegion region;
    private final int wx, wy, wz;
    private final @Nullable ClientLevel level;

    PlacementView(OrientedRegion region, int wx, int wy, int wz, @Nullable ClientLevel level) {
        this.region = region;
        this.wx = wx;
        this.wy = wy;
        this.wz = wz;
        this.level = level;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return region.state(pos.getX() - wx, pos.getY() - wy, pos.getZ() - wz);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return 4096;
    }

    @Override
    public int getMinY() {
        return -2048;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return level != null ? level.cardinalLighting() : CardinalLighting.DEFAULT;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return level != null ? level.getBlockTint(pos, resolver) : -1;
    }
}
