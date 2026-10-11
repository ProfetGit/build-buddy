package io.github.profetgit.buildbuddy.verify;

/** How a placement is getting on: blocks by state. Unknown and unloaded blocks are counted but never held against it; neither are {@code off} ones, the right block standing in another state (a turned stair), which count as built. */
public record Counts(int correct, int missing, int wrong, int unknown, int unloaded, int off) {
    public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0);

    /** Blocks that still have to be placed or fixed. */
    public int todo() {
        return missing + wrong;
    }

    /** Blocks that can be judged: placed right or still to do. */
    public int judged() {
        return correct + off + missing + wrong;
    }

    /** Correct blocks as a share of the ones that can be judged, 0..1; a placement with nothing to judge is done. */
    public double progress() {
        int n = judged();
        return n == 0 ? 1.0 : (correct + off) / (double) n;
    }

    public boolean done() {
        return todo() == 0 && unloaded == 0;
    }
}
