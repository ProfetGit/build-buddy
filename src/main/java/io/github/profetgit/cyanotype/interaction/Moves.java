package io.github.profetgit.cyanotype.interaction;

/** Where a placement goes when it is turned or aimed. Whole blocks only. */
public final class Moves {
    private Moves() {
    }

    /** The origin that keeps the centre of the footprint in place when the box goes from {@code osx × osz} to {@code nsx × nsz}. */
    public static int[] keepCenter(int ox, int oz, int osx, int osz, int nsx, int nsz) {
        double cx = ox + osx / 2.0, cz = oz + osz / 2.0;
        return new int[]{(int) Math.floor(cx - nsx / 2.0 + 0.5), (int) Math.floor(cz - nsz / 2.0 + 0.5)};
    }

    /** The origin that centres a {@code sx × sz} footprint on a target cell. */
    public static int[] centerOn(int tx, int tz, int sx, int sz) {
        return new int[]{tx - Math.floorDiv(sx, 2), tz - Math.floorDiv(sz, 2)};
    }
}
