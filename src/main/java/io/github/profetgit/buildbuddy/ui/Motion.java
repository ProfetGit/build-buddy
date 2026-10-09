package io.github.profetgit.buildbuddy.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * The small curves behind the interface's feel (PRD 6b): everything that changes state moves between its two looks and is
 * always interruptible, because each value only ever eases toward its target from where it is. With "reduce motion" on
 * every value jumps to its target instead.
 */
public final class Motion {
    private static final Map<String, float[]> VALUES = new HashMap<>();
    private static long lastNs, frameNo, seenFrame = -1;
    private static double dt = 0.016;

    private Motion() {
    }

    /** Called at the end of every rendered frame. */
    public static void endFrame() {
        frameNo++;
    }

    /**
     * Call before drawing anything: the first call in a rendered frame measures the time since the last one, later calls in
     * the same frame (the HUD and an open screen both call it) change nothing, or the second would see a dt of about zero.
     */
    public static void frame() {
        if (seenFrame == frameNo) return;
        seenFrame = frameNo;
        long now = System.nanoTime();
        dt = lastNs == 0 ? 0.016 : Math.min(0.1, (now - lastNs) / 1e9);
        lastNs = now;
    }

    public static double dt() {
        return dt;
    }

    public static boolean reduced() {
        return Settings.get().reduceMotion;
    }

    /**
     * A value that follows a target with a time constant, keyed by a name so it survives from frame to frame. Hover uses
     * 0.07 s (the move is done in about 100 ms), press 0.04 s.
     */
    public static float follow(String key, float target, double timeConstant) {
        float[] v = VALUES.computeIfAbsent(key, k -> new float[]{target});
        if (reduced()) {
            v[0] = target;
            return target;
        }
        double d = target - v[0];
        if (Math.abs(d) < 0.003) {
            v[0] = target;
        } else {
            v[0] += (float) (d * (1 - Math.exp(-dt / timeConstant)));
        }
        return v[0];
    }

    public static float hover(String key, boolean on) {
        return follow(key + "#h", on ? 1f : 0f, 0.07);
    }

    public static float press(String key, boolean on) {
        return follow(key + "#p", on ? 1f : 0f, 0.04);
    }

    /** Forgets a key (the thing it animated went away), so it starts fresh next time. */
    public static void drop(String key) {
        VALUES.remove(key);
        VALUES.remove(key + "#h");
        VALUES.remove(key + "#p");
    }

    public static void clear() {
        VALUES.clear();
    }

    /** Ease-out cubic: fast at first, settling. */
    public static double easeOut(double t) {
        t = Math.max(0, Math.min(1, t));
        double u = 1 - t;
        return 1 - u * u * u;
    }

    /**
     * The release of a pressed control: a damped spring from the pressed look back through its normal one with a single
     * overshoot (about a pixel), done in about 140 ms. Returns the offset from rest as a share of that pixel.
     */
    public static double releaseSpring(double seconds) {
        if (reduced() || seconds >= 0.14) return 0;
        double t = seconds / 0.14;
        return Math.exp(-5 * t) * Math.cos(9 * t);
    }
}
