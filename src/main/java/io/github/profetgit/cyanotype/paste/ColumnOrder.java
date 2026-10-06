package io.github.profetgit.cyanotype.paste;

/**
 * The order a blueprint goes into the world in: one chunk column at a time (a 16 x 16 piece of the footprint, all its layers from
 * the bottom up), the columns in a snake so neighbours follow each other. The game's chunk sections are re-drawn (and re-lit) by
 * the client each time something in them changes; going layer by layer across a whole footprint touches every section on every
 * layer and so redraws each one a dozen times, a column at a time touches it once. Within a column the bottom goes first, so
 * nothing stands without what is under it. Pure, so it is tested alone.
 */
final class ColumnOrder {
    private final int sx, sz, wx, wz;
    private final int firstCx, firstCz, ncx, ncz;

    /** @param sx,sz the region's footprint; @param wx,wz where its min corner is in the world */
    ColumnOrder(int sx, int sz, int wx, int wz) {
        this.sx = sx;
        this.sz = sz;
        this.wx = wx;
        this.wz = wz;
        this.firstCx = wx >> 4;
        this.firstCz = wz >> 4;
        this.ncx = ((wx + sx - 1) >> 4) - firstCx + 1;
        this.ncz = ((wz + sz - 1) >> 4) - firstCz + 1;
    }

    int count() {
        return ncx * ncz;
    }

    /** Column {@code i}: {local x from, x to (exclusive), local z from, z to (exclusive), chunk x, chunk z}. */
    int[] column(int i) {
        int ci = i / ncz, cj = i % ncz;
        if ((ci & 1) == 1) cj = ncz - 1 - cj;
        int cx = firstCx + ci, cz = firstCz + cj;
        return new int[]{Math.max(0, (cx << 4) - wx), Math.min(sx, ((cx + 1) << 4) - wx), Math.max(0, (cz << 4) - wz), Math.min(sz, ((cz + 1) << 4) - wz), cx, cz};
    }
}
