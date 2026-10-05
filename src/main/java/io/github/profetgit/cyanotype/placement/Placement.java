package io.github.profetgit.cyanotype.placement;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * A blueprint put somewhere in the world. {@code origin} is the world position of the min corner of the turned
 * blueprint's enclosing box. Fields are read by the render thread and written by the main thread (the same thread in
 * practice) and by blueprint loading, hence the volatile ones.
 */
public final class Placement {
    public enum Status {
        /** Its file is being read. */
        LOADING,
        READY,
        /** Its file is gone; the placement is kept so the file can come back. */
        MISSING,
        /** Its file cannot be read; {@link #problem} says why. */
        FAILED
    }

    /** Accent colours (opaque ARGB) handed out in turn, used for outlines and handles. */
    public static final int[] ACCENTS = {0xFF7FE3FF, 0xFFFFC857, 0xFFF78AE0, 0xFFA6E22E, 0xFFFF8A6B, 0xFFA78BFA};

    public volatile @Nullable Blueprint blueprint;
    public volatile Status status;
    public volatile String problem = "";
    public String name;
    /** Where the blueprint came from (see BlueprintLibrary), kept so the placement can be saved and reloaded. */
    public String ref;
    /** Dimension id the placement belongs to; coordinates only mean something there. */
    public String dimension;
    public BlockPos origin;
    public Orientation orientation;
    /** Ghost opacity 0..1. */
    public float opacity = 0.6f;
    public boolean visible = true;
    /** False while it still follows the crosshair. */
    public boolean locked;
    public int accent = ACCENTS[0];

    // render-thread animation state (see GhostRenderer): the eased position the ghost is drawn at, and the lock spring
    public double vx, vy, vz;
    public boolean visualReady;
    public long settleStartNs;
    /** When origin or orientation last changed; the ghost is rebuilt for its true position after a pause. */
    public volatile long lastMoveNs = System.nanoTime();

    public Placement(String name, @Nullable Blueprint blueprint, String ref, String dimension, BlockPos origin, Orientation orientation) {
        this.name = name;
        this.blueprint = blueprint;
        this.ref = ref;
        this.dimension = dimension;
        this.origin = origin;
        this.orientation = orientation;
        this.status = blueprint != null ? Status.READY : Status.LOADING;
    }

    public boolean ready() {
        return status == Status.READY && blueprint != null;
    }

    /** Size of the enclosing box in the placed orientation. */
    public int sizeX() {
        Blueprint b = blueprint;
        return b == null ? 0 : orientation.sizeX(b.sizeX, b.sizeZ);
    }

    public int sizeY() {
        Blueprint b = blueprint;
        return b == null ? 0 : b.sizeY;
    }

    public int sizeZ() {
        Blueprint b = blueprint;
        return b == null ? 0 : orientation.sizeZ(b.sizeX, b.sizeZ);
    }

    public AABB bounds() {
        return new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + sizeX(), origin.getY() + sizeY(), origin.getZ() + sizeZ());
    }

    /** Centre of the footprint, at the base. */
    public double centerX() {
        return origin.getX() + sizeX() / 2.0;
    }

    public double centerZ() {
        return origin.getZ() + sizeZ() / 2.0;
    }

    /** Sets a new position or orientation and notes the time, so rebuilding waits for the movement to pause. */
    public void set(BlockPos origin, Orientation orientation) {
        if (!origin.equals(this.origin) || !orientation.equals(this.orientation)) {
            this.origin = origin;
            this.orientation = orientation;
            this.lastMoveNs = System.nanoTime();
        }
    }
}
