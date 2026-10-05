package io.github.profetgit.cyanotype.placement;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import net.minecraft.core.BlockPos;

/**
 * A blueprint put somewhere in the world. {@code origin} is the world position of the min corner of the turned
 * blueprint's enclosing box. Changing any field means the ghost must be rebuilt (see {@code Ghosts}).
 */
public final class Placement {
    public final Blueprint blueprint;
    public final String name;
    public BlockPos origin;
    public Orientation orientation;
    /** Ghost opacity 0..1. */
    public float opacity = 0.6f;
    public boolean visible = true;

    public Placement(String name, Blueprint blueprint, BlockPos origin, Orientation orientation) {
        this.name = name;
        this.blueprint = blueprint;
        this.origin = origin;
        this.orientation = orientation;
    }

    /** Size of the enclosing box in the placed orientation. */
    public int sizeX() {
        return orientation.sizeX(blueprint.sizeX, blueprint.sizeZ);
    }

    public int sizeY() {
        return blueprint.sizeY;
    }

    public int sizeZ() {
        return orientation.sizeZ(blueprint.sizeX, blueprint.sizeZ);
    }
}
