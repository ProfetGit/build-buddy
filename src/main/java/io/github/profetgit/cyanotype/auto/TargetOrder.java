package io.github.profetgit.cyanotype.auto;

import java.util.Comparator;

/** Which block to place first: the lowest layer, then the one nearest the player (PRD 7.8: bottom to top). */
public final class TargetOrder {
    /** A block still to place: its packed position, its layer (counted up from the base of the build) and its squared distance from the player's eyes. */
    public record Cand(long pos, int layer, double distSq) {
    }

    public static final Comparator<Cand> ORDER = Comparator.comparingInt(Cand::layer).thenComparingDouble(Cand::distSq).thenComparingLong(Cand::pos);

    private TargetOrder() {
    }
}
