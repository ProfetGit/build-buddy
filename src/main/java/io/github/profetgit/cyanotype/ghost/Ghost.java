package io.github.profetgit.cyanotype.ghost;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.verify.Verifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * The render state of one placement as baked for one position and orientation: its regions turned into the placed
 * orientation and cut into 16-block sections, each baked on a worker thread, uploaded to its own GPU buffer, and drawn
 * from there every frame. Once the placement is locked a {@link Verifier} watches the world for this ghost, and the
 * sections are baked without the blocks that are already right (and with the wrong ones tinted red).
 */
public final class Ghost {
    static final int SECTION = 16;

    public final Placement placement;
    final ClientLevel level;
    final Blueprint blueprint;
    /** Where and how it was baked: the meshes are in world coordinates of this origin, turned by this orientation. */
    final BlockPos bakeOrigin;
    final Orientation bakeOrientation;
    /** Null until the (worker-thread) preparation has finished. */
    volatile List<Section> sections;
    volatile List<OrientedRegion> regions;
    volatile boolean disposed;
    /** Sections in range that still wait to be baked or uploaded; -1 until a frame has looked. */
    volatile int pendingInRange = -1;
    /** Compares the world with this ghost's blueprint; null while the placement is still being placed. */
    Verifier verifier;

    Ghost(Placement placement, Blueprint blueprint, ClientLevel level, BlockPos bakeOrigin, Orientation bakeOrientation) {
        this.placement = placement;
        this.blueprint = blueprint;
        this.level = level;
        this.bakeOrigin = bakeOrigin;
        this.bakeOrientation = bakeOrientation;
    }

    int sizeX() {
        return bakeOrientation.sizeX(blueprint.sizeX, blueprint.sizeZ);
    }

    int sizeZ() {
        return bakeOrientation.sizeZ(blueprint.sizeX, blueprint.sizeZ);
    }

    /** Whether this ghost was baked for where and how the placement is right now. */
    boolean matches(Placement p) {
        return bakeOrigin.equals(p.origin) && bakeOrientation.equals(p.orientation);
    }

    /** Builds the turned regions and the section list; called on a worker thread. */
    void prepare() {
        List<Section> out = new ArrayList<>();
        List<OrientedRegion> rs = new ArrayList<>();
        int ox = bakeOrigin.getX(), oy = bakeOrigin.getY(), oz = bakeOrigin.getZ();
        for (Region r : blueprint.regions) {
            OrientedRegion o = OrientedRegion.of(blueprint, r, bakeOrientation);
            int part = rs.size();
            rs.add(o);
            int wx = ox + o.ox, wy = oy + o.oy, wz = oz + o.oz;
            int nx = (o.sx + SECTION - 1) / SECTION, nz = (o.sz + SECTION - 1) / SECTION;
            for (int y = 0; y < o.sy; y += SECTION) {
                for (int z = 0; z < o.sz; z += SECTION) {
                    for (int x = 0; x < o.sx; x += SECTION) {
                        int vsec = ((y / SECTION) * nz + (z / SECTION)) * nx + (x / SECTION);
                        out.add(new Section(this, o, part, vsec, wx, wy, wz, x, y, z, Math.min(SECTION, o.sx - x), Math.min(SECTION, o.sy - y), Math.min(SECTION, o.sz - z)));
                    }
                }
            }
        }
        regions = rs;
        sections = out;
    }

    /** Frees every GPU buffer and finished mesh; render thread only. */
    void dispose() {
        disposed = true;
        List<Section> list = sections;
        if (list != null) for (Section s : list) s.release();
    }

    /**
     * One 16-block box of one region. A section is IDLE until it is baked (or when it must be baked again because the
     * world changed: it keeps drawing its old buffer meanwhile), BAKING while a worker has it, BAKED when a mesh waits
     * to be uploaded, UPLOADED when the buffer (or nothing, for an empty section) is up to date.
     */
    static final class Section {
        static final int IDLE = 0, BAKING = 1, BAKED = 2, UPLOADED = 3, FAILED = 5;

        final Ghost ghost;
        final OrientedRegion region;
        /** Index of the region in {@link Ghost#regions} and of this section in the verifier's grid of it. */
        final int part, vsec;
        final int wx, wy, wz;
        final int x, y, z, w, h, d;
        final AABB bounds;
        volatile int state = IDLE;
        volatile SectionMesher.Baked baked;
        /** The verifier version the mesh in the buffer was made from, and the one being made. */
        int bakedVersion = -1, bakingVersion;
        GpuBuffer buffer;
        /** The vertex format the mesh in the buffer was written in (null when there is no mesh). */
        com.mojang.renderpearl.api.vertex.VertexFormat meshFormat;
        /** Failed bakes or uploads so far, and when the last one was: a failed section is tried again after a while, a few times. */
        int failures;
        long failedNs;
        int indexCount;
        int quads;
        /** Quads emitted up to the end of each layer of the section (length h + 1): a range of layers is a range of quads. */
        int[] layerQuads;
        /** The same for the quads of blocks that stand in water (null when none does): they come after the dry ones in the buffer. */
        int[] wetQuads;

        Section(Ghost ghost, OrientedRegion region, int part, int vsec, int wx, int wy, int wz, int x, int y, int z, int w, int h, int d) {
            this.ghost = ghost;
            this.region = region;
            this.part = part;
            this.vsec = vsec;
            this.wx = wx;
            this.wy = wy;
            this.wz = wz;
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
            this.d = d;
            this.bounds = new AABB(wx + x, wy + y, wz + z, wx + x + w, wy + y + h, wz + z + d);
        }

        /** Cap meshes for the Layers tool, by {@code layer * 2 + (top ? 1 : 0)}; only exist while the placement is layered. Render thread only. */
        final java.util.Map<Integer, Cap> caps = new java.util.HashMap<>();

        void release() {
            SectionMesher.Baked b = baked;
            baked = null;
            if (b != null) b.close();
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            for (Cap c : caps.values()) c.release();
            caps.clear();
        }
    }

    /**
     * The faces of one layer of a section that its neighbour layer hides in the whole build and a layer cut would uncover
     * (see {@link SectionMesher#bakeCap}). Baked and uploaded like a section, but small, and only for the layers at the edges
     * of the window and the ones next to them, so scrolling the window finds them ready.
     */
    static final class Cap {
        final boolean top;
        /** The layer, counted from the section's bottom. */
        final int layer;
        volatile int state = Section.IDLE;
        volatile SectionMesher.BakedCap baked;
        volatile boolean dead;
        /** Whether the window's edge is exactly this layer (those are drawn; the neighbours are only kept ready). */
        boolean exact;
        int bakedVersion = -1, bakingVersion;
        GpuBuffer buffer;
        com.mojang.renderpearl.api.vertex.VertexFormat meshFormat;
        int indexCount, quads;

        Cap(boolean top, int layer) {
            this.top = top;
            this.layer = layer;
        }

        void release() {
            dead = true;
            SectionMesher.BakedCap b = baked;
            baked = null;
            if (b != null) b.close();
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
        }
    }
}
