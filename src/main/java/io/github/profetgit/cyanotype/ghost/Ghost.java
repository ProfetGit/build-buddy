package io.github.profetgit.cyanotype.ghost;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.placement.Placement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;

/**
 * The render state of one placement: its regions turned into the placed orientation and cut into 16-block sections,
 * each baked on a worker thread, uploaded to its own GPU buffer, and drawn from there every frame.
 */
public final class Ghost {
    static final int SECTION = 16;

    public final Placement placement;
    final ClientLevel level;
    /** Null until the (worker-thread) preparation has finished. */
    volatile List<Section> sections;
    volatile boolean disposed;

    Ghost(Placement placement, ClientLevel level) {
        this.placement = placement;
        this.level = level;
    }

    /** Builds the turned regions and the section list; called on a worker thread. */
    void prepare() {
        List<Section> out = new ArrayList<>();
        int ox = placement.origin.getX(), oy = placement.origin.getY(), oz = placement.origin.getZ();
        for (Region r : placement.blueprint.regions) {
            OrientedRegion o = OrientedRegion.of(placement.blueprint, r, placement.orientation);
            int wx = ox + o.ox, wy = oy + o.oy, wz = oz + o.oz;
            for (int y = 0; y < o.sy; y += SECTION) {
                for (int z = 0; z < o.sz; z += SECTION) {
                    for (int x = 0; x < o.sx; x += SECTION) {
                        out.add(new Section(this, o, wx, wy, wz, x, y, z, Math.min(SECTION, o.sx - x), Math.min(SECTION, o.sy - y), Math.min(SECTION, o.sz - z)));
                    }
                }
            }
        }
        sections = out;
    }

    /** Frees every GPU buffer and finished mesh; render thread only. */
    void dispose() {
        disposed = true;
        List<Section> list = sections;
        if (list != null) for (Section s : list) s.release();
    }

    /** One 16-block box of one region. */
    static final class Section {
        static final int IDLE = 0, BAKING = 1, BAKED = 2, UPLOADED = 3, EMPTY = 4, FAILED = 5;

        final Ghost ghost;
        final OrientedRegion region;
        final int wx, wy, wz;
        final int x, y, z, w, h, d;
        final AABB bounds;
        volatile int state = IDLE;
        volatile SectionMesher.Baked baked;
        GpuBuffer buffer;
        int indexCount;
        int quads;

        Section(Ghost ghost, OrientedRegion region, int wx, int wy, int wz, int x, int y, int z, int w, int h, int d) {
            this.ghost = ghost;
            this.region = region;
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

        void release() {
            SectionMesher.Baked b = baked;
            baked = null;
            if (b != null) b.close();
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
        }
    }
}
