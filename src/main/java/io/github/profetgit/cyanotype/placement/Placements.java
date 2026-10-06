package io.github.profetgit.cyanotype.placement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Every placement of the current world, which one is active, what the player is doing with it, and the undo history of
 * moves. Client main thread; the render thread only reads.
 */
public final class Placements {
    public enum Mode {
        /** Ghosts are shown and nothing intercepts the mouse. */
        IDLE,
        /** The active placement follows the crosshair until a click locks it. */
        PLACING,
        /** The active, locked placement shows its handles. */
        EDIT,
        /** Scroll moves the window of layers shown (the Layers tool). */
        LAYERS,
        /** The Save area tool: corners are picked and the box is sized (see Selecting). */
        SELECT,
        /** The Smart Pick tool: a click picks a whole build (see Picking). */
        PICK
    }

    /**
     * One step of history. Applying an entry reverts a change and yields the entry that puts it back, so undo and redo are
     * the same move in opposite directions: a {@link Move} sets a placement to an earlier spot, a {@link Restore} adds a
     * removed placement back, a {@link Delete} takes one away again.
     */
    private sealed interface Entry permits Move, Restore, Delete, Pasted {
        Placement placement();
    }

    private record Move(Placement placement, BlockPos origin, Orientation orientation) implements Entry {
    }

    private record Restore(Placement placement, int index) implements Entry {
    }

    private record Delete(Placement placement) implements Entry {
    }

    /** A paste into the world (creative): undoing it runs {@code undo}, which puts the old blocks back; there is no redo. */
    private record Pasted(Placement placement, Runnable undo) implements Entry {
    }

    public enum Kind {
        MOVE, RESTORE, DELETE, PASTE
    }

    /** What an undo or redo just did. */
    public record Change(Kind kind, Placement placement) {
    }

    private static final int UNDO_LIMIT = 64;

    private static final List<Placement> ALL = new CopyOnWriteArrayList<>();
    private static final Deque<Entry> UNDO = new ArrayDeque<>(), REDO = new ArrayDeque<>();
    private static volatile @Nullable Placement active;
    private static volatile Mode mode = Mode.IDLE;
    private static int nextAccent;

    private Placements() {
    }

    public static List<Placement> all() {
        return ALL;
    }

    public static @Nullable Placement active() {
        return active;
    }

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode m) {
        mode = m;
    }

    /** Adds a placement (named uniquely, given the next accent colour) and makes it the active one. */
    public static void add(Placement p) {
        p.name = uniqueName(p.name);
        p.accent = Placement.ACCENTS[nextAccent++ % Placement.ACCENTS.length];
        ALL.add(p);
        active = p;
        PlacementStore.markDirty();
    }

    /** Adds a placement read from a save file, keeping its own name and colour. */
    public static void restore(Placement p) {
        ALL.add(p);
    }

    /** Takes a placement away for good: its history goes with it. */
    public static void remove(Placement p) {
        detach(p);
        UNDO.removeIf(e -> e.placement() == p);
        REDO.removeIf(e -> e.placement() == p);
    }

    /** Takes a placement away in a way Undo can bring back (what the player does with Remove or Delete). */
    public static void removeUndoable(Placement p) {
        int index = ALL.indexOf(p);
        if (index < 0) return;
        detach(p);
        push(UNDO, new Restore(p, index));
        REDO.clear();
    }

    private static void detach(Placement p) {
        ALL.remove(p);
        // a material highlight belongs to a placement's verifier: it goes with it
        io.github.profetgit.cyanotype.interaction.CellHighlight.clear();
        if (active == p) {
            active = null;
            if (mode != Mode.IDLE) mode = Mode.IDLE;
        }
        PlacementStore.markDirty();
    }

    public static void select(@Nullable Placement p) {
        active = p;
    }

    public static void clear() {
        io.github.profetgit.cyanotype.interaction.CellHighlight.clear();
        ALL.clear();
        UNDO.clear();
        REDO.clear();
        active = null;
        mode = Mode.IDLE;
        nextAccent = 0;
    }

    public static @Nullable Placement find(String key) {
        try {
            int n = Integer.parseInt(key.trim());
            if (n >= 1 && n <= ALL.size()) return ALL.get(n - 1);
        } catch (NumberFormatException ignored) {
            // a name
        }
        for (Placement p : ALL) if (p.name.equalsIgnoreCase(key.trim())) return p;
        return null;
    }

    private static String uniqueName(String base) {
        String name = base;
        int n = 2;
        while (exists(name)) name = base + " " + n++;
        return name;
    }

    private static boolean exists(String name) {
        for (Placement p : ALL) if (p.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static void push(Deque<Entry> stack, Entry e) {
        stack.push(e);
        while (stack.size() > UNDO_LIMIT) stack.removeLast();
    }

    /** Remembers where a placement is, before a change, so Undo can put it back. A new change ends the Redo history. */
    public static void remember(Placement p) {
        push(UNDO, new Move(p, p.origin, p.orientation));
        REDO.clear();
    }

    /** Remembers that the blueprint was pasted into the world, so Ctrl+Z can take it out again in its turn among the other changes. */
    public static void rememberPaste(Placement p, Runnable undo) {
        push(UNDO, new Pasted(p, undo));
        REDO.clear();
    }

    /** Drops the snapshot just taken, when nothing changed after all (a grab without a move). */
    public static void forgetLast() {
        if (!UNDO.isEmpty() && UNDO.peek() instanceof Move) UNDO.pop();
    }

    /** Reverts the most recent change. @return what was done, or null if there is nothing to undo */
    public static @Nullable Change undo() {
        return step(UNDO, REDO);
    }

    /** Does again what the last Undo reverted. @return what was done, or null if there is nothing to redo */
    public static @Nullable Change redo() {
        return step(REDO, UNDO);
    }

    private static boolean valid(Entry e) {
        return switch (e) {
            case Move m -> ALL.contains(m.placement);
            case Restore r -> !ALL.contains(r.placement);
            case Delete d -> ALL.contains(d.placement);
            case Pasted x -> true;
        };
    }

    private static @Nullable Change step(Deque<Entry> from, Deque<Entry> to) {
        while (!from.isEmpty()) {
            Entry e = from.pop();
            if (!valid(e)) continue;
            Placement p = e.placement();
            switch (e) {
                case Move m -> {
                    push(to, new Move(p, p.origin, p.orientation));
                    p.set(m.origin, m.orientation);
                    active = p;
                    PlacementStore.markDirty();
                    return new Change(Kind.MOVE, p);
                }
                case Restore r -> {
                    ALL.add(Math.min(r.index, ALL.size()), p);
                    push(to, new Delete(p));
                    active = p;
                    mode = Mode.IDLE;
                    PlacementStore.markDirty();
                    return new Change(Kind.RESTORE, p);
                }
                case Delete d -> {
                    int index = ALL.indexOf(p);
                    detach(p);
                    push(to, new Restore(p, index));
                    return new Change(Kind.DELETE, p);
                }
                case Pasted x -> {
                    x.undo.run();
                    return new Change(Kind.PASTE, p);
                }
            }
        }
        return null;
    }

    public static boolean canUndo() {
        return UNDO.stream().anyMatch(Placements::valid);
    }

    public static boolean canRedo() {
        return REDO.stream().anyMatch(Placements::valid);
    }
}
