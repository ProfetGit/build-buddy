package io.github.profetgit.cyanotype.interaction;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * A box of whole blocks, both corners included (the Save area tool, PRD 7.7). Plain ints, so growing and shrinking it by
 * face can be tested alone.
 */
public record SelectionBox(int x0, int y0, int z0, int x1, int y1, int z1) {
    /** The box between two corner cells, in any order. */
    public static SelectionBox of(int ax, int ay, int az, int bx, int by, int bz) {
        return new SelectionBox(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
    }

    public int sizeX() {
        return x1 - x0 + 1;
    }

    public int sizeY() {
        return y1 - y0 + 1;
    }

    public int sizeZ() {
        return z1 - z0 + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** How many 16 x 16 columns of chunks the box touches. */
    public int chunkColumns() {
        return ((x1 >> 4) - (x0 >> 4) + 1) * ((z1 >> 4) - (z0 >> 4) + 1);
    }

    /**
     * The box with one face moved: {@code steps} blocks outward (negative = inward). A box never gets thinner than one
     * block, so pulling a face past the opposite one stops there.
     */
    public SelectionBox moved(Direction face, int steps) {
        int s = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? steps : -steps;
        return switch (face.getAxis()) {
            case X -> face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? new SelectionBox(x0, y0, z0, Math.max(x0, x1 + s), y1, z1) : new SelectionBox(Math.min(x1, x0 + s), y0, z0, x1, y1, z1);
            case Y -> face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? new SelectionBox(x0, y0, z0, x1, Math.max(y0, y1 + s), z1) : new SelectionBox(x0, Math.min(y1, y0 + s), z0, x1, y1, z1);
            case Z -> face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? new SelectionBox(x0, y0, z0, x1, y1, Math.max(z0, z1 + s)) : new SelectionBox(x0, y0, Math.min(z1, z0 + s), x1, y1, z1);
        };
    }

    /** The box as the world sees it: from the min corner of its first cell to the far corner of its last. */
    public AABB aabb() {
        return new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
    }

    public String sizeText() {
        return sizeX() + " x " + sizeY() + " x " + sizeZ();
    }
}
