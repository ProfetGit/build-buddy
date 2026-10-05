package io.github.profetgit.cyanotype.ghost;

/** The small curves that make the ghost move like a held object: easing toward a target and a damped spring on lock. */
public final class Feel {
    /** How far above its place a ghost floats while it is held, in blocks. */
    public static final double LIFT = 0.18;
    /** Seconds the eased position takes to close most of a gap. */
    public static final double FOLLOW = 0.05;
    /** Seconds a lock spring lasts. */
    public static final double SETTLE_SECONDS = 0.7;

    private static final double ZETA = 0.42, OMEGA = 20.0;

    private Feel() {
    }

    /** Moves {@code current} toward {@code target} so that a fixed share of the gap closes per time, whatever the frame rate. */
    public static double ease(double current, double target, double dt, double timeConstant) {
        double d = target - current;
        if (Math.abs(d) < 0.0005) return target;
        return current + d * (1 - Math.exp(-dt / timeConstant));
    }

    /**
     * A damped spring from 1 at t = 0 to 0, overshooting once to about -0.2 before it settles: the drop when a held
     * ghost is locked. Interruptible: it is a function of time, so a new lock simply starts it again.
     */
    public static double spring(double t) {
        if (t <= 0) return 1;
        if (t >= SETTLE_SECONDS) return 0;
        double wd = OMEGA * Math.sqrt(1 - ZETA * ZETA);
        return Math.exp(-ZETA * OMEGA * t) * (Math.cos(wd * t) + ZETA * OMEGA / wd * Math.sin(wd * t));
    }
}
