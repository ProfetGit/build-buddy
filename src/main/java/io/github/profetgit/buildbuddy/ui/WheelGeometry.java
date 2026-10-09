package io.github.profetgit.buildbuddy.ui;

/**
 * Where the segments of the tool wheel are, for any number of tools: segment 0 is centred straight up, the rest follow
 * clockwise at equal steps, and the lines between segments run halfway between two centres. Angles are in radians,
 * clockwise from straight up. Pure maths, so the drawing, the icons and the mouse all agree.
 */
public final class WheelGeometry {
    private WheelGeometry() {
    }

    public static double step(int n) {
        return 2 * Math.PI / n;
    }

    /** The middle of segment {@code i}: where its icon goes. */
    public static double center(int i, int n) {
        return i * step(n);
    }

    /** The line between segment {@code i} and the next one. */
    public static double boundary(int i, int n) {
        return (i + 0.5) * step(n);
    }

    /** The segment a point (relative to the middle of the wheel, screen y down) is over, or -1 inside the hub or past {@code outer}. */
    public static int segmentAt(double dx, double dy, double inner, double outer, int n) {
        double r = Math.hypot(dx, dy);
        if (r < inner || r > outer) return -1;
        double a = Math.atan2(dx, -dy) + step(n) / 2;
        a = ((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        return (int) (a / step(n)) % n;
    }

    /** Screen offset of a point at a radius along the middle of segment {@code i}. */
    public static double[] pointAt(int i, int n, double radius) {
        double a = center(i, n);
        return new double[]{Math.sin(a) * radius, -Math.cos(a) * radius};
    }
}
