package io.github.profetgit.cyanotype.interaction;

import net.minecraft.world.phys.Vec3;

/**
 * Carrying a build by grabbing it: the point of the build under the crosshair stays under the crosshair while the player
 * looks around, whatever the build's size or distance. That is why it feels the same on a shed and on a hundred-block
 * tower (an arrow's drag is a line far away, where a pixel of mouse is many blocks).
 *
 * <p>Sideways movement follows the point where the view meets a flat plane at the grabbed point's height; when the view
 * cannot meet that plane (a roof grabbed from the ground, then the view lowered to the ground) it follows the point where the
 * view meets the floor of the build instead, and the two take over from each other without a jump; a grabbed point level
 * with the eye uses the floor alone, since its own plane could never be met. With Shift held the build
 * goes up and down instead, following the view's crossing of an upright plane through the grabbed point. Each part
 * moves in whole blocks from where it was grabbed. Pure maths, tested.
 */
final class Grab {
    /** The farthest a view line is followed to its plane: beyond it the build stays where it was last. */
    static final double MAX_T = 600;
    /** A plane closer than this to the eye in height is no use for sideways movement (the view runs along it). */
    static final double MIN_PLANE_GAP = 3.0;

    /** Beyond this fraction of a block past the rounding point the offset changes: a view resting on the line between two blocks does not flicker. */
    static final double HOLD = 0.62;

    final Vec3 start;
    final double planeY;
    /** The floor of the build, the second plane to follow when the first is not met; NaN when it is the same plane. */
    private final double floorY;
    /** Which plane the offsets are taken from now: 0 the grabbed height, 1 the floor. */
    private int plane;
    private double hx, hz;
    private final double nx, nz;
    /** How far from the eye (sideways) the grabbed point was, and how far the build may be taken: see {@link #soft}. */
    private final double r0, reachMax;
    int dx, dy, dz;
    private double vOff;
    private boolean wasVertical, switched;

    private Grab(Vec3 start, double planeY, double floorY, double hx, double hz, double nx, double nz, double r0) {
        this.start = start;
        this.planeY = planeY;
        this.floorY = Math.abs(floorY - planeY) < 0.5 ? Double.NaN : floorY;
        this.hx = hx;
        this.hz = hz;
        this.nx = nx;
        this.nz = nz;
        this.r0 = r0;
        this.reachMax = r0 * 1.5 + 30;
    }

    /**
     * Starts a grab of the point {@code p0} seen from {@code eye} along {@code look}; {@code floorY} is the height of the
     * build's base, the fallback plane when the grabbed point is level with the eye.
     */
    static Grab start(Vec3 eye, Vec3 look, Vec3 p0, double floorY) {
        double plane = p0.y;
        if (Math.abs(p0.y - eye.y) < MIN_PLANE_GAP && Math.abs(floorY - eye.y) >= 0.5) plane = floorY;
        // where the view meets the plane now; when it does not (looking level), the first frame it does is the start
        double t = Math.abs(look.y) < 1e-9 ? -1 : (plane - eye.y) / look.y;
        double sx = t > 0 ? eye.x + look.x * t : Double.NaN, sz = t > 0 ? eye.z + look.z * t : Double.NaN;
        double h = Math.hypot(look.x, look.z);
        double nx = h < 1e-3 ? 0 : look.x / h, nz = h < 1e-3 ? -1 : look.z / h;
        return new Grab(p0, plane, floorY, sx, sz, nx, nz, Math.hypot(p0.x - eye.x, p0.z - eye.z));
    }

    /**
     * Keeps a far reach from running away: a view that skims the plane (looking toward the horizon, or at the sky when the
     * grab was low) meets it a very long way off, so beyond the distance the point was grabbed at the reach is squeezed
     * toward {@link #reachMax} (tanh: the same speed up to there, then slower and slower, never past it).
     */
    private double soft(double r) {
        if (r <= r0) return r;
        double room = reachMax - r0;
        return r0 + room * Math.tanh((r - r0) / room);
    }

    /** Follows the view for this frame. @param vertical Shift is held: only up and down change, else only sideways */
    void update(Vec3 eye, Vec3 look, boolean vertical) {
        // changing between sideways and up/down must not jump: the new mode takes up from where the build is now
        if (vertical != wasVertical) {
            wasVertical = vertical;
            switched = true;
        }
        if (vertical) {
            double den = look.x * nx + look.z * nz;
            if (den < 1e-3) return;
            double t = ((start.x - eye.x) * nx + (start.z - eye.z) * nz) / den;
            if (t <= 0 || t > MAX_T) return;
            double raw = eye.y + look.y * t - start.y;
            if (switched) {
                vOff = dy - raw;
                switched = false;
            }
            double v = raw + vOff;
            // the same squeeze for height, symmetric: lifting 30 blocks is 30, never a hundred and ten thousand
            double bound = reachMax;
            dy = (int) Math.round(bound * Math.tanh(v / bound));
        } else {
            // the grabbed height first; when the view cannot meet it, the floor of the build
            int which = 0;
            double[] at = meet(eye, look, planeY);
            if (at == null && !Double.isNaN(floorY)) {
                at = meet(eye, look, floorY);
                which = 1;
            }
            if (at == null) return;
            double x = at[0], z = at[1];
            double r = Math.hypot(x - eye.x, z - eye.z);
            if (r > 1e-6) {
                double k = soft(r) / r;
                x = eye.x + (x - eye.x) * k;
                z = eye.z + (z - eye.z) * k;
            }
            if (Double.isNaN(hx)) {
                hx = x;
                hz = z;
            }
            if (switched || which != plane) {
                // a new mode or a new plane takes up from where the build is now
                hx = x - dx;
                hz = z - dz;
                switched = false;
                plane = which;
            }
            double fx = x - hx, fz = z - hz;
            // a view resting between two blocks keeps the block it had until it is clearly past
            if (Math.abs(fx - dx) > HOLD) dx = (int) Math.round(fx);
            if (Math.abs(fz - dz) > HOLD) dz = (int) Math.round(fz);
        }
    }

    /** Where the view line meets a flat plane at this height in front of the eye (within {@link #MAX_T}), or null. */
    private static double[] meet(Vec3 eye, Vec3 look, double y) {
        if (Math.abs(look.y) < 1e-9) return null;
        double t = (y - eye.y) / look.y;
        if (t <= 0 || t > MAX_T) return null;
        return new double[]{eye.x + look.x * t, eye.z + look.z * t};
    }

    boolean moved() {
        return dx != 0 || dy != 0 || dz != 0;
    }

    /** "12 east, 3 north, 2 up" for what has moved so far. */
    String words() {
        StringBuilder sb = new StringBuilder();
        add(sb, dx, dx > 0 ? "east" : "west");
        add(sb, dz, dz > 0 ? "south" : "north");
        add(sb, dy, dy > 0 ? "up" : "down");
        return sb.length() == 0 ? "not moved" : sb.toString();
    }

    private static void add(StringBuilder sb, int n, String word) {
        if (n == 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(Math.abs(n)).append(' ').append(word);
    }
}
