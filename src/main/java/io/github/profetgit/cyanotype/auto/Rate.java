package io.github.profetgit.cyanotype.auto;

/**
 * How fast auto-placing may go. The ceiling is part of the mod, not a setting: {@link #MAX} blocks a second, whatever the
 * config file says (PRD 7.8). One tick at a time, no saving up: after a pause the first block comes at once and then
 * the pace is the pace.
 */
public final class Rate {
    public static final int MIN = 1, DEFAULT = 4, MAX = 20;

    private double bucket = 1.0;

    private Rate() {
    }

    public static Rate create() {
        return new Rate();
    }

    public static int clamp(int perSecond) {
        return Math.max(MIN, Math.min(MAX, perSecond));
    }

    /** Called once a tick that wants to place. @return whether a block may be placed this tick */
    public boolean take(int perSecond) {
        bucket = Math.min(1.0, bucket + clamp(perSecond) / 20.0);
        if (bucket >= 1.0 - 1e-9) {
            bucket -= 1.0;
            return true;
        }
        return false;
    }

    /** Forgets what was saved: the next block may go at once. */
    public void reset() {
        bucket = 1.0;
    }
}
