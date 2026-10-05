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
        LAYERS
    }

    private record Snapshot(Placement placement, BlockPos origin, Orientation orientation) {
    }

    private static final int UNDO_LIMIT = 64;

    private static final List<Placement> ALL = new CopyOnWriteArrayList<>();
    private static final Deque<Snapshot> UNDO = new ArrayDeque<>();
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

    public static void remove(Placement p) {
        ALL.remove(p);
        UNDO.removeIf(s -> s.placement == p);
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
        ALL.clear();
        UNDO.clear();
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

    /** Remembers where a placement is, before a change, so Undo can put it back. */
    public static void remember(Placement p) {
        UNDO.push(new Snapshot(p, p.origin, p.orientation));
        while (UNDO.size() > UNDO_LIMIT) UNDO.removeLast();
    }

    /** Puts the most recently moved placement back. @return the placement, or null if there was nothing to undo */
    public static @Nullable Placement undo() {
        while (!UNDO.isEmpty()) {
            Snapshot s = UNDO.pop();
            if (!ALL.contains(s.placement)) continue;
            s.placement.set(s.origin, s.orientation);
            active = s.placement;
            PlacementStore.markDirty();
            return s.placement;
        }
        return null;
    }

    public static boolean canUndo() {
        return UNDO.stream().anyMatch(s -> ALL.contains(s.placement));
    }
}
