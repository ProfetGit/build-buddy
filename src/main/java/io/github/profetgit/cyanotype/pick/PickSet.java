package io.github.profetgit.cyanotype.pick;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * What the player has picked so far (the Smart Pick tool's state, PRD 7.11): one or more floods, the parts of them that
 * were chosen, the cells taken out again, and the reach. Everything that needs the world runs as a sliced
 * {@link Picker.Job}; until a new state is ready the old one stays on show, and a state that fails (too big) is thrown
 * away with a notice instead of replacing it.
 */
public final class PickSet {
    private final Picker.Field field;
    private final int limit;

    // the committed state
    private final List<Long> anchors = new ArrayList<>();
    private final List<Block> forced = new ArrayList<>();
    private final List<Picker.Result> floods = new ArrayList<>();
    private final List<Long> chosen = new ArrayList<>();
    private final LongOpenHashSet removed = new LongOpenHashSet();
    private int reach = Picker.DEFAULT_REACH;

    // derived from it
    private LongOpenHashSet picked = new LongOpenHashSet();
    private LongOpenHashSet doubtful = new LongOpenHashSet();
    private boolean unloaded;

    // the state being worked out
    private List<Long> nextAnchors;
    private List<Block> nextForced;
    private List<Long> nextChosen;
    private int nextReach;
    private List<Picker.Result> nextFloods;
    private Picker.Job job;
    private String notice = "";
    private boolean restart;
    private int version;

    public PickSet(Picker.Field field, int limit) {
        this.field = field;
        this.limit = limit;
    }

    // ---- reading

    /** Changes whenever what is picked changes, so a drawing of it knows when to be made again. */
    public int version() {
        return version;
    }

    public boolean empty() {
        return floods.isEmpty();
    }

    public boolean working() {
        return job != null;
    }

    /** 0..1 while working. */
    public double progress() {
        if (job == null) return 1;
        int n = Math.max(1, nextAnchors.size());
        return (nextFloods.size() + job.progress()) / n;
    }

    public int reach() {
        return reach;
    }

    /** What the next state will use, while it is being worked out. */
    public int reachWanted() {
        return job != null ? nextReach : reach;
    }

    /** A message for the player about something that did not work (consumed by reading it). */
    public String takeNotice() {
        String n = notice;
        notice = "";
        return n;
    }

    public LongOpenHashSet picked() {
        return picked;
    }

    public LongOpenHashSet doubtful() {
        return doubtful;
    }

    public boolean touchedUnloaded() {
        return unloaded;
    }

    /** Cells that were reached but are not picked (other parts, and parts taken out), for showing and for adding back. */
    public boolean isContext(long cell) {
        if (picked.contains(cell)) return false;
        for (Picker.Result r : floods) if (r.cells.contains(cell)) return true;
        return false;
    }

    public List<Picker.Result> floods() {
        return floods;
    }

    public int partsAvailable() {
        int n = 0;
        for (Picker.Result r : floods) n += r.partCount();
        return n;
    }

    /** Everything the picker reached that is not picked: other parts and parts that were taken out. */
    public LongOpenHashSet context() {
        LongOpenHashSet out = new LongOpenHashSet();
        for (Picker.Result r : floods) for (long c : r.cells) if (!picked.contains(c)) out.add(c);
        return out;
    }

    /** Smallest box holding the picked cells: {minX, minY, minZ, maxX, maxY, maxZ}, or null when nothing is picked. */
    public int @Nullable [] bounds() {
        if (picked.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long c : picked) {
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            if (x < x0) x0 = x;
            if (y < y0) y0 = y;
            if (z < z0) z0 = z;
            if (x > x1) x1 = x;
            if (y > y1) y1 = y;
            if (z > z1) z1 = z;
        }
        return new int[]{x0, y0, z0, x1, y1, z1};
    }

    // ---- changing

    /** Starts over from a block. */
    public void start(long seed, @Nullable Block force) {
        nextAnchors = new ArrayList<>(List.of(seed));
        nextForced = new ArrayList<>();
        nextForced.add(force);
        nextChosen = new ArrayList<>(List.of(seed));
        restart = true;
        begin(reach);
    }

    /** Adds the part a cell belongs to (a part already reached, or a new build elsewhere). */
    public void addPart(long cell, @Nullable Block force) {
        if (job != null) return;
        for (Picker.Result r : floods) {
            if (r.cells.contains(cell)) {
                // taken out before? then it comes back
                removed.removeAll(r.members(r.partOf(cell)));
                if (!chosen.contains(cell)) chosen.add(cell);
                derive();
                return;
            }
        }
        nextAnchors = new ArrayList<>(anchors);
        nextAnchors.add(cell);
        nextForced = new ArrayList<>(forced);
        nextForced.add(force);
        nextChosen = new ArrayList<>(chosen);
        nextChosen.add(cell);
        begin(reach);
    }

    /** Takes the part a picked cell belongs to out again. @return false when the cell is not picked */
    public boolean removePart(long cell) {
        if (job != null || !picked.contains(cell)) return false;
        for (Picker.Result r : floods) {
            int p = r.partOf(cell);
            if (p >= 0) {
                removed.addAll(r.members(p));
                derive();
                return true;
            }
        }
        return false;
    }

    /** Changes how wide a gap the picker jumps; the old pick stays until the new one is ready. */
    public void setReach(int newReach) {
        int r = Math.max(0, Math.min(Picker.MAX_REACH, newReach));
        if (floods.isEmpty() || (job == null && r == reach) || (job != null && r == nextReach)) return;
        nextAnchors = new ArrayList<>(anchors);
        nextForced = new ArrayList<>(forced);
        nextChosen = new ArrayList<>(chosen);
        begin(r);
    }

    public void clear() {
        anchors.clear();
        forced.clear();
        floods.clear();
        chosen.clear();
        removed.clear();
        picked = new LongOpenHashSet();
        version++;
        doubtful = new LongOpenHashSet();
        unloaded = false;
        job = null;
        nextFloods = null;
        notice = "";
    }

    private void begin(int wantedReach) {
        nextReach = wantedReach;
        nextFloods = new ArrayList<>();
        job = new Picker.Job(field, nextAnchors.get(0), nextReach, limit, nextForced.get(0));
    }

    /** Works for about this long. @return true when nothing is running */
    public boolean step(long budgetNs) {
        long end = System.nanoTime() + budgetNs;
        while (job != null) {
            if (!job.step(Math.max(100_000, end - System.nanoTime()))) return false;
            if (job.failure() != null) {
                notice = job.failure();
                restart = false;
                job = null;
                nextFloods = null;
                return true;
            }
            nextFloods.add(job.result());
            int i = nextFloods.size();
            if (i < nextAnchors.size()) {
                job = new Picker.Job(field, nextAnchors.get(i), nextReach, limit, nextForced.get(i));
            } else {
                job = null;
                commit();
            }
            if (System.nanoTime() >= end && job != null) return false;
        }
        return true;
    }

    private void commit() {
        boolean fresh = floods.isEmpty() || !nextAnchors.equals(anchors);
        anchors.clear();
        anchors.addAll(nextAnchors);
        forced.clear();
        forced.addAll(nextForced);
        floods.clear();
        floods.addAll(nextFloods);
        chosen.clear();
        chosen.addAll(nextChosen);
        reach = nextReach;
        if (restart) removed.clear();
        restart = false;
        nextFloods = null;
        derive();
        if (picked.isEmpty() && fresh) notice = "Nothing is left of that pick.";
    }

    /** Works out which cells are picked from the floods, the chosen cells and the removed ones. */
    private void derive() {
        int expect = 0;
        for (Picker.Result r : floods) for (int p = 0; p < r.partCount(); p++) expect += r.partSize[p];
        LongOpenHashSet out = new LongOpenHashSet(Math.min(expect, Picker.DEFAULT_LIMIT * 2)), doubt = new LongOpenHashSet();
        boolean touched = false;
        for (Picker.Result r : floods) {
            IntOpenHashSet want = new IntOpenHashSet();
            for (long c : chosen) {
                int p = r.partOf(c);
                if (p >= 0) want.add(p);
            }
            if (want.isEmpty()) continue;
            touched |= r.touchedUnloaded;
            boolean anyDoubt = !r.unsure.isEmpty(), anyRemoved = !removed.isEmpty();
            for (int p : want) {
                var members = r.members(p);
                for (int i = 0; i < members.size(); i++) {
                    long c = members.getLong(i);
                    if (anyRemoved && removed.contains(c)) continue;
                    out.add(c);
                    if (anyDoubt && r.unsure.contains(c)) doubt.add(c);
                }
            }
        }
        picked = out;
        version++;
        doubtful = doubt;
        unloaded = touched;
    }
}
