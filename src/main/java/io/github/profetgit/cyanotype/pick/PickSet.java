package io.github.profetgit.cyanotype.pick;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * What Smart Pick found for one click (PRD 7.11): the flood from the block clicked, split into parts, and the part the
 * click was in (with the thin groups that hang on it) as the pick. The pick is only used to fit a box round the build and
 * to know which neighbouring parts to leave out when asked; the player edits the box, not the pick. The work needs the
 * world, so it runs as a sliced {@link Picker.Job}; a result that fails (too big) is thrown away with a notice.
 */
public final class PickSet {
    private final Picker.Field field;
    private final int limit;

    private Picker.@Nullable Result flood;
    private long seed;
    private int reach = Picker.DEFAULT_REACH;

    private LongOpenHashSet picked = new LongOpenHashSet();
    private LongOpenHashSet doubtful = new LongOpenHashSet();
    private boolean unloaded;

    private Picker.@Nullable Job job;
    private @Nullable Block nextForced;
    private long nextSeed;
    private String notice = "";
    private int version;

    public PickSet(Picker.Field field, int limit) {
        this.field = field;
        this.limit = limit;
    }

    // ---- reading

    /** Changes whenever what is picked changes. */
    public int version() {
        return version;
    }

    public boolean empty() {
        return flood == null;
    }

    public boolean working() {
        return job != null;
    }

    /** 0..1 while working. */
    public double progress() {
        return job == null ? 1 : job.progress();
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

    /** Cells that were reached but are not picked: the other parts. */
    public LongOpenHashSet context() {
        LongOpenHashSet out = new LongOpenHashSet();
        if (flood != null) for (long c : flood.cells) if (!picked.contains(c)) out.add(c);
        return out;
    }

    /** How many parts the picker reached that are not in the pick. */
    public int otherParts() {
        if (flood == null) return 0;
        IntOpenHashSet in = new IntOpenHashSet();
        for (long c : picked) {
            int p = flood.partOf(c);
            if (p >= 0) in.add(p);
        }
        return Math.max(0, flood.partCount() - in.size());
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
        nextSeed = seed;
        nextForced = force;
        job = new Picker.Job(field, seed, reach, limit, force);
    }

    public void clear() {
        flood = null;
        picked = new LongOpenHashSet();
        doubtful = new LongOpenHashSet();
        unloaded = false;
        job = null;
        notice = "";
        version++;
    }

    /** Works for about this long. @return true when nothing is running */
    public boolean step(long budgetNs) {
        if (job == null) return true;
        if (!job.step(budgetNs)) return false;
        Picker.Job done = job;
        job = null;
        if (done.failure() != null) {
            notice = done.failure();
            return true;
        }
        flood = done.result();
        seed = nextSeed;
        derive();
        if (picked.isEmpty()) notice = "Nothing is left of that pick.";
        return true;
    }

    /** The picked cells: the part the click was in, with what hangs on it. */
    private void derive() {
        LongOpenHashSet out = new LongOpenHashSet(), doubt = new LongOpenHashSet();
        Picker.Result r = flood;
        int p = r.partOf(seed);
        if (p >= 0) {
            var members = r.members(p);
            boolean anyDoubt = !r.unsure.isEmpty();
            for (int i = 0; i < members.size(); i++) {
                long c = members.getLong(i);
                out.add(c);
                if (anyDoubt && r.unsure.contains(c)) doubt.add(c);
            }
        }
        picked = out;
        doubtful = doubt;
        unloaded = r.touchedUnloaded;
        version++;
    }
}
