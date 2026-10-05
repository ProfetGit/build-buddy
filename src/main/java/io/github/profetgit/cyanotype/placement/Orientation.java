package io.github.profetgit.cyanotype.placement;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a blueprint is turned before it is placed: mirror first, then rotation, the order vanilla structures use.
 * Cell maths works on a box of {@code sx × sz} cells and returns coordinates in the turned box, which starts at 0 again.
 * The eight turns of a square form a group; {@link #linear()}, {@link #rotated} and {@link #flipped} work in it.
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

    /** The turn as a 2x2 matrix {a, b, c, d}: (x, z) goes to (a x + b z, c x + d z), ignoring where the box starts. */
    public int[] linear() {
        int mx = mirror == Mirror.FRONT_BACK ? -1 : 1;
        int mz = mirror == Mirror.LEFT_RIGHT ? -1 : 1;
        // mirror: diag(mx, mz); rotation applied after: CW90 (x,z) -> (-z, x), CW180 -> (-x,-z), CCW90 -> (z,-x)
        return switch (rotation) {
            case NONE -> new int[]{mx, 0, 0, mz};
            case CLOCKWISE_90 -> new int[]{0, -mz, mx, 0};
            case CLOCKWISE_180 -> new int[]{-mx, 0, 0, -mz};
            case COUNTERCLOCKWISE_90 -> new int[]{0, mz, -mx, 0};
        };
    }

    /** The orientation with this turn matrix. Always spelled with a left-right mirror, never a front-back one. */
    public static Orientation fromLinear(int[] m) {
        for (Mirror mirror : new Mirror[]{Mirror.NONE, Mirror.LEFT_RIGHT}) {
            for (Rotation rotation : Rotation.values()) {
                Orientation o = new Orientation(rotation, mirror);
                if (java.util.Arrays.equals(o.linear(), m)) return o;
            }
        }
        throw new IllegalArgumentException("not a square symmetry: " + java.util.Arrays.toString(m));
    }

    /** This orientation, then turned further by {@code by} in the world. */
    public Orientation rotated(Rotation by) {
        return new Orientation(rotation.getRotated(by), mirror);
    }

    /** This orientation, then mirrored in the world across the plane perpendicular to {@code axis} (X or Z). */
    public Orientation flipped(Direction.Axis axis) {
        int[] m = linear();
        int[] f = axis == Direction.Axis.X ? new int[]{-1, 0, 0, 1} : new int[]{1, 0, 0, -1};
        return fromLinear(multiply(f, m));
    }

    /** Whether the turned blueprint is mirrored (an odd number of flips). */
    public boolean isMirrored() {
        int[] m = linear();
        return m[0] * m[3] - m[1] * m[2] < 0;
    }

    private static int[] multiply(int[] a, int[] b) {
        return new int[]{
            a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
            a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3]};
    }
}
