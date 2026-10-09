package io.github.profetgit.buildbuddy.interaction;

/** The geometry behind picking and dragging handles. Plain doubles, no game classes, so it can be tested alone. */
public final class HandleMath {
    private HandleMath() {
    }

    /**
     * Where a ray enters a box.
     *
     * @return the distance along the ray (in units of the direction's length), or NaN for a miss; 0 if the ray starts inside
     */
    public static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz,
                                double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double tMin = 0, tMax = Double.POSITIVE_INFINITY;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) return Double.NaN;
            } else {
                double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
                if (t1 > t2) {
                    double s = t1;
                    t1 = t2;
                    t2 = s;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return Double.NaN;
            }
        }
        return tMin;
    }

    /**
     * The parameter {@code t} of the point on the line {@code P + t A} that is closest to the ray {@code O + s D}: where
     * a drag along that line has got to when the mouse ray passes by. NaN when the ray and line are parallel.
     */
    public static double closestOnLine(double ox, double oy, double oz, double dx, double dy, double dz,
                                       double px, double py, double pz, double ax, double ay, double az) {
        double wx = ox - px, wy = oy - py, wz = oz - pz;
        double a = dx * dx + dy * dy + dz * dz;
        double b = dx * ax + dy * ay + dz * az;
        double c = ax * ax + ay * ay + az * az;
        double d = dx * wx + dy * wy + dz * wz;
        double e = ax * wx + ay * wy + az * wz;
        double denom = a * c - b * b;
        if (Math.abs(denom) < 1e-9 * a * c) return Double.NaN;
        return (a * e - b * d) / denom;
    }

    /** Where a ray meets the horizontal plane {@code y = planeY}: the distance along it, or NaN if it never does (or points away). */
    public static double rayPlaneY(double oy, double dy, double planeY) {
        if (Math.abs(dy) < 1e-12) return Double.NaN;
        double t = (planeY - oy) / dy;
        return t >= 0 ? t : Double.NaN;
    }

    /** Angle of a point around a centre in the horizontal plane, radians: 0 along +x, growing toward +z (clockwise seen from above). */
    public static double angle(double x, double z, double cx, double cz) {
        return Math.atan2(z - cz, x - cx);
    }

    /** The shortest signed turn from angle a to angle b. */
    public static double angleDelta(double a, double b) {
        double d = b - a;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    /** Whole blocks of a drag distance; a drag has to pass the half to count, so it never feels like it lags or jumps ahead. */
    public static int snap(double distance) {
        return (int) Math.round(distance);
    }

    /** Quarter turns of a drag angle, likewise. */
    public static int quarterTurns(double radians) {
        return (int) Math.round(radians / (Math.PI / 2));
    }

    /**
     * The axis (0 x, 1 y, 2 z) a view direction points along the most. The current axis is kept until another one is more
     * than 15 % stronger, so the choice does not flicker where two are about equal (at 45 degrees).
     */
    public static int dominantAxis(double lx, double ly, double lz, int current) {
        double[] c = {Math.abs(lx), Math.abs(ly), Math.abs(lz)};
        int best = 0;
        for (int i = 1; i < 3; i++) if (c[i] > c[best]) best = i;
        return best != current && c[best] > c[current] * 1.15 ? best : current;
    }
}
