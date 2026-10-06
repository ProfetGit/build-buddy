package io.github.profetgit.cyanotype.paste;

import io.github.profetgit.cyanotype.ghost.OrientedRegion;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Puts a placed blueprint into the world in one go (creative mode, a world the player hosts), a few milliseconds of the
 * server's time per tick so the game never stalls, and remembers what each cell held so the paste can be undone. It runs on
 * the server thread: {@link #step} places, {@link #undoStep} puts the old blocks back. Cells are placed layer by layer from the
 * bottom so nothing stands without its support; chunks that are not loaded are left alone (and counted).
 *
 * <p>Blocks go in with {@code UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE}: the states are exactly the blueprint's (no neighbour
 * pass turns a fence or a stair into something else), the block's own {@code onPlace} runs. A cell that holds a block entity
 * is first swapped for a barrier without drops, so the items of a chest it overwrites are not scattered; the old block entity
 * is kept in the undo log with its items.
 */
public final class PasteJob {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** Replace a block that has a block entity without its drops: the flags a structure placement uses for the same job. */
    private static final int QUIET = 820;

    /** One turned region of the placed blueprint, where it stands in the world, and the block entity data per cell. */
    public record Part(OrientedRegion region, int wx, int wy, int wz, Map<Integer, CompoundTag> blockEntities) {
        public static Part of(OrientedRegion region, int wx, int wy, int wz) {
            Map<Integer, CompoundTag> be = new HashMap<>();
            for (CompoundTag te : region.source.blockEntities) {
                int idx = region.indexOfSource(te.getIntOr("x", -1), te.getIntOr("y", -1), te.getIntOr("z", -1));
                if (idx < 0) continue;
                CompoundTag data = te.copy();
                data.remove("x");
                data.remove("y");
                data.remove("z");
                be.put(idx, data);
            }
            return new Part(region, wx, wy, wz, be);
        }
    }

    public enum State {
        RUNNING, DONE, UNDOING, UNDONE
    }

    private final ServerLevel level;
    private final List<Part> parts;
    private final long total;
    private int partIdx, colIdx, lx, ly, lz;
    private boolean inColumn;
    private @Nullable ColumnOrder order;
    private volatile State state = State.RUNNING;
    private volatile long placed, same, unloaded;
    private volatile String stopped = "";
    private volatile boolean undoRequested;

    // what the cells held before, for the undo
    private final LongArrayList undoPos;
    private final ShortArrayList undoState;
    private final List<BlockState> undoPalette = new ArrayList<>();
    private final Map<BlockState, Integer> undoIndex = new HashMap<>();
    private final Map<Long, CompoundTag> undoEntities = new HashMap<>();
    private int undoAt;

    public PasteJob(ServerLevel level, List<Part> parts) {
        this.level = level;
        this.parts = parts;
        long n = 0;
        for (Part p : parts) {
            boolean[] skip = new boolean[p.region.states.length];
            for (int i = 0; i < skip.length; i++) skip[i] = p.region.states[i].isAir() || p.region.unknown[i];
            for (short b : p.region.blocks) if (!skip[b & 0xFFFF]) n++;
        }
        this.total = n;
        // sized once for what can go in: a list that doubles as it grows would briefly hold twice the memory
        int expect = (int) Math.min(n, 50_000_000L);
        this.undoPos = new LongArrayList(Math.max(16, expect));
        this.undoState = new ShortArrayList(Math.max(16, expect));
    }

    /** What the undo log of a paste of this many blocks needs, in bytes: a position and a state for each, plus room for the lists. */
    public static long undoBytes(long blocks) {
        return blocks * 12L + (1L << 20);
    }

    /** How many blocks the paste puts in at most: the cells of the blueprint that are not air and not unknown. */
    public long total() {
        return total;
    }

    public State state() {
        return state;
    }

    public long placed() {
        return placed;
    }

    /** Cells that already held exactly the right block. */
    public long same() {
        return same;
    }

    /** Cells left alone because their chunk was not loaded. */
    public long unloaded() {
        return unloaded;
    }

    public String stopped() {
        return stopped;
    }

    /** How far along, 0..1. */
    public double progress() {
        if (state == State.UNDOING) return undoPos.isEmpty() ? 1 : 1.0 - (undoPos.size() - undoAt) / (double) undoPos.size();
        return total == 0 ? 1 : Math.min(1.0, (placed + same + unloaded) / (double) total);
    }

    /** Places blocks for about this long, but not more than {@code maxBlocks} cells of work. @return true when the whole blueprint has been put in */
    public boolean step(long budgetNs, int maxBlocks) {
        if (state != State.RUNNING) return true;
        long end = System.nanoTime() + budgetNs;
        long limit = placed + same + maxBlocks;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (partIdx < parts.size()) {
            Part p = parts.get(partIdx);
            OrientedRegion r = p.region;
            if (order == null) {
                order = new ColumnOrder(r.sx, r.sz, p.wx, p.wz);
                colIdx = 0;
                inColumn = false;
            }
            short[] blocks = r.blocks;
            int layer = r.sz * r.sx;
            while (colIdx < order.count()) {
                int[] col = order.column(colIdx);
                if (!inColumn) {
                    inColumn = true;
                    lx = col[0];
                    lz = col[2];
                    ly = 0;
                    if (!level.hasChunk(col[4], col[5])) {
                        // a chunk that is not loaded is left alone, and counted, once for the whole column
                        for (int y = 0; y < r.sy; y++) for (int z = col[2]; z < col[3]; z++) for (int x = col[0]; x < col[1]; x++) {
                            int pal = blocks[(y * r.sz + z) * r.sx + x] & 0xFFFF;
                            if (!r.states[pal].isAir() && !r.unknown[pal]) unloaded++;
                        }
                        ly = r.sy;
                    }
                }
                int budgetCheck = 0;
                while (ly < r.sy) {
                    while (lz < col[3]) {
                        while (lx < col[1]) {
                            int x = lx++;
                            int idx = ly * layer + lz * r.sx + x;
                            int pal = blocks[idx] & 0xFFFF;
                            BlockState want = r.states[pal];
                            if (want.isAir() || r.unknown[pal]) continue;
                            pos.set(p.wx + x, p.wy + ly, p.wz + lz);
                            place(pos, want, p.blockEntities.get(idx));
                            if ((++budgetCheck & 63) == 0 && (System.nanoTime() >= end || placed + same >= limit)) return false;
                        }
                        lx = col[0];
                        lz++;
                    }
                    lz = col[2];
                    ly++;
                }
                colIdx++;
                inColumn = false;
            }
            partIdx++;
            order = null;
        }
        state = State.DONE;
        return true;
    }

    private void place(BlockPos.MutableBlockPos pos, BlockState want, @Nullable CompoundTag data) {
        BlockState have = level.getBlockState(pos);
        BlockEntity old = level.getBlockEntity(pos);
        if (have == want && data == null && old == null) {
            same++;
            return;
        }
        BlockPos at = pos.immutable();
        record(at, have, old);
        if (old != null) level.setBlock(at, Blocks.BARRIER.defaultBlockState(), QUIET);
        level.setBlock(at, want, FLAGS);
        if (data != null) {
            BlockEntity made = level.getBlockEntity(at);
            if (made != null) made.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
        }
        placed++;
    }

    private void record(BlockPos at, BlockState have, @Nullable BlockEntity entity) {
        int slot = undoIndex.computeIfAbsent(have, k -> {
            undoPalette.add(k);
            return undoPalette.size() - 1;
        });
        undoPos.add(at.asLong());
        undoState.add((short) slot);
        if (entity != null) {
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            entity.saveWithId(out);
            undoEntities.put(at.asLong(), out.buildResult());
        }
    }

    /** Stops placing; what went in so far can still be undone. */
    public void stop(String why) {
        if (state == State.RUNNING) {
            stopped = why;
            state = State.DONE;
        }
    }

    /** Whether an undo was asked for and has not started. */
    public boolean undoPending() {
        return undoRequested && state != State.UNDOING && state != State.UNDONE;
    }

    /** Asks for the old blocks to be put back; the server thread starts on it at its next slice (also while the paste is still running). */
    public void beginUndo() {
        undoRequested = true;
    }

    /** One slice of whatever is to be done: placing, or putting back. @return true when there is nothing more to do */
    public boolean work(long budgetNs, int maxBlocks) {
        if (undoRequested && state != State.UNDOING && state != State.UNDONE) {
            state = State.UNDOING;
            undoAt = undoPos.size();
        }
        return switch (state) {
            case RUNNING -> step(budgetNs, maxBlocks);
            case UNDOING -> undoStep(budgetNs, maxBlocks);
            default -> true;
        };
    }

    /** Puts old blocks back for about this long, newest first. @return true when all are back */
    public boolean undoStep(long budgetNs, int maxBlocks) {
        if (state != State.UNDOING) return true;
        long end = System.nanoTime() + budgetNs;
        int stopAt = Math.max(0, undoAt - maxBlocks);
        while (undoAt > 0) {
            int i = --undoAt;
            BlockPos at = BlockPos.of(undoPos.getLong(i));
            BlockState old = undoPalette.get(undoState.getShort(i));
            if (level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) {
                if (level.getBlockEntity(at) != null) level.setBlock(at, Blocks.BARRIER.defaultBlockState(), QUIET);
                level.setBlock(at, old, FLAGS);
                CompoundTag data = undoEntities.get(at.asLong());
                if (data != null) {
                    BlockEntity made = level.getBlockEntity(at);
                    if (made != null) made.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
                }
            }
            if ((i & 63) == 0 && (System.nanoTime() >= end || i <= stopAt)) return false;
        }
        state = State.UNDONE;
        return true;
    }

    /** Blocks that went in and can be taken out again. */
    public int undoable() {
        return undoPos.size();
    }
}
