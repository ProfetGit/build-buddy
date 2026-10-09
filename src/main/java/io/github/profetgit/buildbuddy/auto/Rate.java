package io.github.profetgit.buildbuddy.auto;

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

    /** Called every tick auto-placing is running: fills the bucket for one tick. */
    public void tick(int perSecond) {
        double per = clamp(perSecond) / 20.0;
        // never more than one block's worth saved and the part of a tick that came with it, so a pause buys no burst
        bucket = Math.min(1.0 + per, bucket + per);
    }

    /** Whether a block may be placed now. */
    public boolean ready() {
        return bucket >= 1.0 - 1e-9;
    }

    /** Pays for a block that was placed. */
    public void spend() {
        bucket -= 1.0;
    }

    /** Convenience for one tick that places at most one block: fills, and spends when ready. @return whether a block may go this tick */
    public boolean take(int perSecond) {
        tick(perSecond);
        if (!ready()) return false;
        spend();
        return true;
    }

    /** Forgets what was saved: the next block may go at once. */
    public void reset() {
        bucket = 1.0;
    }
}
