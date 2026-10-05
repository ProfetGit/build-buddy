package io.github.profetgit.cyanotype.placement;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a blueprint is turned before it is placed: mirror first, then rotation, the order vanilla structures use.
 * Cell maths works on a box of {@code sx × sz} cells and returns coordinates in the turned box, which starts at 0 again.
 */
public record Orientation(Rotation rotation, Mirror mirror) {
    public static final Orientation NONE = new Orientation(Rotation.NONE, Mirror.NONE);

    public boolean swapsXZ() {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
    }

    public int sizeX(int sx, int sz) {
        return swapsXZ() ? sz : sx;
    }

    public int sizeZ(int sx, int sz) {
        return swapsXZ() ? sx : sz;
    }

    public int mapX(int x, int z, int sx, int sz) {
        if (mirror == Mirror.FRONT_BACK) x = sx - 1 - x;
        else if (mirror == Mirror.LEFT_RIGHT) z = sz - 1 - z;
        return switch (rotation) {
            case NONE -> x;
            case CLOCKWISE_90 -> sz - 1 - z;
            case CLOCKWISE_180 -> sx - 1 - x;
            case COUNTERCLOCKWISE_90 -> z;
        };
    }

    public int mapZ(int x, int z, int sx, int sz) {
        if (mirror == Mirror.FRONT_BACK) x = sx - 1 - x;
        else if (mirror == Mirror.LEFT_RIGHT) z = sz - 1 - z;
        return switch (rotation) {
            case NONE -> z;
            case CLOCKWISE_90 -> x;
            case CLOCKWISE_180 -> sz - 1 - z;
            case COUNTERCLOCKWISE_90 -> sx - 1 - x;
        };
    }

    /** The state a block of the blueprint becomes in this orientation (stairs turn, doors swing, logs lie along the other axis). */
    public BlockState state(BlockState state) {
        return state.mirror(mirror).rotate(rotation);
    }
}
