package io.github.profetgit.cyanotype.verify;

/** How a placement is getting on: blocks by state. Unknown and unloaded blocks are counted but never held against it. */
public record Counts(int correct, int missing, int wrong, int unknown, int unloaded) {
    public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0);

    /** Blocks that still have to be placed or fixed. */
    public int todo() {
        return missing + wrong;
    }

    /** Blocks that can be judged: placed right or still to do. */
    public int judged() {
        return correct + missing + wrong;
    }

    /** Correct blocks as a share of the ones that can be judged, 0..1; a placement with nothing to judge is done. */
    public double progress() {
        int n = judged();
        return n == 0 ? 1.0 : correct / (double) n;
    }

    public boolean done() {
        return todo() == 0 && unloaded == 0;
    }
}
