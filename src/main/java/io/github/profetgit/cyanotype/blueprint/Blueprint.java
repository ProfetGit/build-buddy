package io.github.profetgit.cyanotype.blueprint;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A loaded schematic: metadata plus its regions. Immutable once built. */
public final class Blueprint {
    public record Metadata(String name, String author, String description, long timeCreated, long timeModified, int dataVersion) {
        public static Metadata of(String name) {
            long now = System.currentTimeMillis();
            return new Metadata(name, "", "", now, now, 0);
        }
    }

    public final Metadata meta;
    public final List<Region> regions;
    /** Enclosing box over all regions: min corner and size. */
    public final int minX, minY, minZ, sizeX, sizeY, sizeZ;

    public Blueprint(Metadata meta, List<Region> regions) {
        this.meta = meta;
        this.regions = List.copyOf(regions);
        if (regions.isEmpty()) {
            minX = minY = minZ = sizeX = sizeY = sizeZ = 0;
            return;
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Region r : regions) {
            x0 = Math.min(x0, r.x);
            y0 = Math.min(y0, r.y);
            z0 = Math.min(z0, r.z);
            x1 = Math.max(x1, r.x + r.sx);
            y1 = Math.max(y1, r.y + r.sy);
            z1 = Math.max(z1, r.z + r.sz);
        }
        minX = x0;
        minY = y0;
        minZ = z0;
        sizeX = x1 - x0;
        sizeY = y1 - y0;
        sizeZ = z1 - z0;
    }

    public long volume() {
        long v = 0;
        for (Region r : regions) v += r.volume();
        return v;
    }

    /** Blocks that are not air. */
    public long totalBlocks() {
        long n = 0;
        for (Region r : regions) n += r.nonAir();
        return n;
    }

    /** Ids of blocks the game does not know, so the material panel and the load report can name them. */
    public Set<String> unknownBlocks() {
        Set<String> out = new LinkedHashSet<>();
        for (Region r : regions) {
            for (PaletteEntry e : r.palette) if (e.unknown()) out.add(e.name());
        }
        return out;
    }
}
