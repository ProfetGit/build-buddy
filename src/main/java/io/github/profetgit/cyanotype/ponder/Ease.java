package io.github.profetgit.cyanotype.ponder;

/**
 * How a value travels between two keyframes, as a curve from 0 to 1 over the segment that ENDS at the keyframe carrying the
 * ease. {@link #STEP} holds the old value until the keyframe's time (switches, "mirrored", "down"); {@link #SPRING} overshoots
 * once and settles (a ghost being put down).
 */
public enum Ease {
    LINEAR, IN, OUT, IN_OUT, SPRING, STEP;

    public double apply(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return switch (this) {
            case LINEAR -> x;
            case IN -> x * x * x;
            case OUT -> 1 - Math.pow(1 - x, 3);
            case IN_OUT -> x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
            case SPRING -> 1 - Math.exp(-6.5 * x) * Math.cos(2 * Math.PI * 1.35 * x);
            case STEP -> 0;
        };
    }

    /** The ease a JSON name stands for, or null for an unknown name. */
    public static Ease parse(String name) {
        return switch (name) {
            case "linear" -> LINEAR;
            case "in" -> IN;
            case "out" -> OUT;
            case "inOut" -> IN_OUT;
            case "spring" -> SPRING;
            case "step" -> STEP;
            default -> null;
        };
    }
}
