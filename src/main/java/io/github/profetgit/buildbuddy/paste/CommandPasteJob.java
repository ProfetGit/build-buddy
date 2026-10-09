package io.github.profetgit.buildbuddy.paste;

import io.github.profetgit.buildbuddy.ghost.OrientedRegion;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Pastes a placed blueprint on a server for an operator: the same cells as {@link PasteJob}, but as {@code /fill} and
 * {@code /setblock} commands the client sends, a few a tick. The client's own view of the world is the snapshot for Ctrl+Z, so
 * the undo is another set of commands. Runs on the client thread.
 *
 * <p>Order: first every full cube (bottom up, in slabs of {@link #SLAB} layers), then everything else (torches, doors, signs,
 * stairs...), so what needs a support finds it: commands update neighbours, unlike the flags the local paste uses. Runs of the
 * same state are merged into boxes (at most {@link #CAP} cells, far under the {@code max_block_modifications} limit). A block
 * entity cell is one {@code /setblock} with its saved data. Container items were never saved and cannot be read back from the
 * client, so an undo does not restore what chests held.
 */
public final class CommandPasteJob implements PasteRun {
    static final int SLAB = 8, CAP = 4096;
    private static final int MAX_COMMAND = 30000, WINDOW = 120, FAIL_TICKS = 200, SETTLE_TICKS = 5;

    /** One command, what it covers, and what those cells held before (for the undo). */
    static final class Cmd {
        final String text;
        final int cells;
        final int x1, y1, z1, x2, y2, z2;
        final short @Nullable [] old;
        final @Nullable BlockPos probe;
        final @Nullable BlockState want;
        long sentTick;

        Cmd(String text, int cells, int x1, int y1, int z1, int x2, int y2, int z2, short @Nullable [] old, @Nullable BlockPos probe, @Nullable BlockState want) {
            this.text = text;
            this.cells = cells;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.old = old;
            this.probe = probe;
            this.want = want;
        }
    }

    private final ClientLevel level;
    private final List<PasteJob.Part> parts;
    private final long total;
    private volatile PasteJob.State state = PasteJob.State.RUNNING;
    private volatile long placed, same, unloaded, acked;
    private volatile String stopped = "";
    private volatile boolean undoRequested;
    private int undone;

    private int pass, partIdx, slabY;
    private final ArrayDeque<Cmd> queue = new ArrayDeque<>();
    private final ArrayDeque<Cmd> inFlight = new ArrayDeque<>();
    private final List<Cmd> sent = new ArrayList<>();
    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
    private long tick, settleAt;
    private int failed, sentCount, lostContainers;
    private double credit, lag = 1.0;
    private long undoCells, undoDone;
    private boolean planned;

    public CommandPasteJob(ClientLevel level, List<PasteJob.Part> parts) {
        this.level = level;
        this.parts = parts;
        long n = 0;
        for (PasteJob.Part p : parts) {
            boolean[] skip = new boolean[p.region().states.length];
            for (int i = 0; i < skip.length; i++) skip[i] = p.region().states[i].isAir() || p.region().unknown[i];
            for (short b : p.region().blocks) if (!skip[b & 0xFFFF]) n++;
        }
        this.total = n;
    }

    @Override
    public long total() {
        return total;
    }

    @Override
    public PasteJob.State state() {
        return state;
    }

    @Override
    public long placed() {
        return placed;
    }

    @Override
    public long same() {
        return same;
    }

    @Override
    public long unloaded() {
        return unloaded;
    }

    @Override
    public String stopped() {
        return stopped;
    }

    /** How many commands have been sent so far (paste or undo). */
    public int commandsSent() {
        return sentCount;
    }

    /** Commands the server did not answer for a long time, or whose blocks never showed up. */
    public int failed() {
        return failed;
    }

    /** Cells that held a block entity before the paste: their contents are not restored by an undo. */
    public int lostContainers() {
        return lostContainers;
    }

    @Override
    public double progress() {
        if (state == PasteJob.State.UNDOING || state == PasteJob.State.UNDONE) return undoCells == 0 ? 1 : Math.min(1.0, undoDone / (double) undoCells);
        return total == 0 ? 1 : Math.min(1.0, (acked + same + unloaded) / (double) total);
    }

    @Override
    public int undoable() {
        long n = 0;
        for (Cmd c : sent) n += c.cells;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(n, undone));
    }

    @Override
    public void stop(String why) {
        if (state == PasteJob.State.RUNNING) {
            stopped = why;
            queue.clear();
            state = PasteJob.State.DONE;
        }
    }

    @Override
    public boolean undoPending() {
        return undoRequested && state != PasteJob.State.UNDOING && state != PasteJob.State.UNDONE;
    }

    @Override
    public void beginUndo() {
        undoRequested = true;
    }

    /** Once per client tick: sends up to {@code perTick} commands (fewer when the server is slow to show them) and watches them arrive. */
    public void tick(Minecraft mc, double perTick) {
        tick++;
        watch();
        if (undoRequested && state != PasteJob.State.UNDOING && state != PasteJob.State.UNDONE) startUndo();
        if (state == PasteJob.State.DONE || state == PasteJob.State.UNDONE) return;
        credit = Math.min(perTick * 3, credit + perTick * lag);
        int n = (int) credit;
        credit -= n;
        var conn = mc.getConnection();
        if (conn == null) return;
        while (n-- > 0 && inFlight.size() < WINDOW) {
            if (queue.isEmpty() && state == PasteJob.State.RUNNING && !planned) plan(mc);
            Cmd c = queue.poll();
            if (c == null) break;
            conn.sendCommand(c.text);
            sentCount++;
            c.sentTick = tick;
            if (state == PasteJob.State.RUNNING) sent.add(c);
            if (c.probe != null && !arrived(c)) inFlight.add(c);
            else ack(c);
        }
        if (queue.isEmpty() && (planned || state == PasteJob.State.UNDOING) && inFlight.isEmpty()) {
            if (settleAt == 0) settleAt = tick + SETTLE_TICKS;
            if (tick >= settleAt) finish();
        }
    }

    private void finish() {
        if (state == PasteJob.State.UNDOING) {
            state = PasteJob.State.UNDONE;
            sent.clear();
        } else {
            if (failed > 0 && stopped.isEmpty()) stopped = failed + " commands had no effect (the server may have refused them, or is very slow)";
            state = PasteJob.State.DONE;
        }
    }

    private boolean arrived(Cmd c) {
        return level.getBlockState(c.probe).getBlock() == c.want.getBlock();
    }

    private void ack(Cmd c) {
        if (state == PasteJob.State.UNDOING) undoDone += c.cells;
        else {
            acked += c.cells;
            placed += c.cells;
        }
    }

    /** Looks at the oldest commands: the blocks should show up soon; a server that is slow to show them slows the sending down. */
    private void watch() {
        inFlight.removeIf(c -> {
            if (arrived(c)) {
                ack(c);
                return true;
            }
            if (tick - c.sentTick > FAIL_TICKS) {
                failed++;
                if (state == PasteJob.State.UNDOING) undoDone += c.cells;
                else acked += c.cells;
                return true;
            }
            return false;
        });
        long oldest = inFlight.isEmpty() ? 0 : tick - inFlight.peekFirst().sentTick;
        if (tick % 5 == 0) {
            if (oldest > 30) lag = Math.max(0.1, lag * 0.7);
            else if (oldest < 10) lag = Math.min(1.0, lag * 1.1 + 0.02);
        }
    }

    // ---- planning

    private int slot(BlockState s) {
        return paletteIndex.computeIfAbsent(s, k -> {
            palette.add(k);
            return palette.size() - 1;
        });
    }

    private static boolean full(BlockState s) {
        return s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private void plan(Minecraft mc) {
        while (queue.isEmpty()) {
            if (pass > 1) {
                planned = true;
                return;
            }
            if (partIdx >= parts.size()) {
                partIdx = 0;
                slabY = 0;
                pass++;
                continue;
            }
            PasteJob.Part p = parts.get(partIdx);
            if (slabY >= p.region().sy) {
                partIdx++;
                slabY = 0;
                continue;
            }
            planSlab(p, slabY, Math.min(p.region().sy, slabY + SLAB), pass == 0);
            slabY += SLAB;
        }
    }

    private void planSlab(PasteJob.Part part, int y0, int y1, boolean solids) {
        OrientedRegion r = part.region();
        int h = y1 - y0;
        int[] grid = new int[r.sx * h * r.sz];
        java.util.Arrays.fill(grid, -1);
        short[] oldGrid = new short[grid.length];
        boolean[] isFull = new boolean[r.states.length];
        String[] text = new String[r.states.length];
        for (int i = 0; i < isFull.length; i++) isFull[i] = !r.states[i].isAir() && full(r.states[i]);
        List<Cmd> cmds = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        boolean loaded = false;
        for (int y = y0; y < y1; y++) for (int z = 0; z < r.sz; z++) for (int x = 0; x < r.sx; x++) {
            int idx = (y * r.sz + z) * r.sx + x;
            int pal = r.blocks[idx] & 0xFFFF;
            BlockState want = r.states[pal];
            if (want.isAir() || r.unknown[pal] || isFull[pal] != solids) continue;
            int wx = part.wx() + x, wy = part.wy() + y, wz = part.wz() + z;
            int cx = wx >> 4, cz = wz >> 4;
            if (cx != lastCx || cz != lastCz) {
                lastCx = cx;
                lastCz = cz;
                loaded = level.hasChunk(cx, cz);
            }
            if (!loaded) {
                unloaded++;
                continue;
            }
            pos.set(wx, wy, wz);
            BlockState have = level.getBlockState(pos);
            CompoundTag data = part.blockEntities().get(idx);
            if (have == want && data == null) {
                same++;
                continue;
            }
            if (have.hasBlockEntity()) lostContainers++;
            int g = ((y - y0) * r.sz + z) * r.sx + x;
            oldGrid[g] = (short) slot(have);
            if (data != null) {
                if (text[pal] == null) text[pal] = BlockStateParser.serialize(want);
                CompoundTag d = data.copy();
                d.remove("id");
                String cmd = "setblock " + wx + " " + wy + " " + wz + " " + text[pal] + d;
                if (cmd.length() > MAX_COMMAND) cmd = "setblock " + wx + " " + wy + " " + wz + " " + text[pal];
                cmds.add(new Cmd(cmd, 1, wx, wy, wz, wx, wy, wz, new short[]{oldGrid[g]}, have.getBlock() == want.getBlock() ? null : pos.immutable(), want));
            } else {
                grid[g] = pal;
            }
        }
        for (int[] b : boxes(grid, r.sx, h, r.sz, CAP)) {
            BlockState want = r.states[b[6]];
            if (text[b[6]] == null) text[b[6]] = BlockStateParser.serialize(want);
            int x1 = part.wx() + b[0], y1w = part.wy() + y0 + b[1], z1 = part.wz() + b[2], x2 = part.wx() + b[3], y2 = part.wy() + y0 + b[4], z2 = part.wz() + b[5];
            int cells = (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
            short[] old = new short[cells];
            BlockPos probe = null;
            int i = 0;
            for (int yy = b[1]; yy <= b[4]; yy++) for (int zz = b[2]; zz <= b[5]; zz++) for (int xx = b[0]; xx <= b[3]; xx++) {
                old[i] = oldGrid[(yy * r.sz + zz) * r.sx + xx];
                if (palette.get(old[i]).getBlock() != want.getBlock()) probe = new BlockPos(part.wx() + xx, part.wy() + y0 + yy, part.wz() + zz);
                i++;
            }
            String cmd = cells == 1
                ? "setblock " + x1 + " " + y1w + " " + z1 + " " + text[b[6]]
                : "fill " + x1 + " " + y1w + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + text[b[6]] ;
            cmds.add(new Cmd(cmd, cells, x1, y1w, z1, x2, y2, z2, old, probe, want));
        }
        cmds.sort(Comparator.comparingInt((Cmd c) -> c.y1).thenComparingInt(c -> c.z1).thenComparingInt(c -> c.x1));
        queue.addAll(cmds);
    }

    /**
     * Greedy boxes over a grid (index {@code (y * d + z) * w + x}, -1 = leave alone): each box is a run along x, widened along z while
     * whole rows match, then stacked along y while whole rectangles match, at most {@code cap} cells. Each result is
     * {x1, y1, z1, x2, y2, z2, state}. The grid is used up.
     */
    static List<int[]> boxes(int[] grid, int w, int h, int d, int cap) {
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) {
            int s = grid[(y * d + z) * w + x];
            if (s < 0) continue;
            int x2 = x;
            while (x2 + 1 < w && x2 - x + 1 < cap && grid[(y * d + z) * w + x2 + 1] == s) x2++;
            int lenX = x2 - x + 1;
            int z2 = z;
            while (z2 + 1 < d && lenX * (z2 - z + 2) <= cap && rowIs(grid, w, d, y, z2 + 1, x, x2, s)) z2++;
            int area = lenX * (z2 - z + 1);
            int y2 = y;
            while (y2 + 1 < h && area * (y2 - y + 2) <= cap) {
                boolean ok = true;
                for (int zz = z; zz <= z2 && ok; zz++) ok = rowIs(grid, w, d, y2 + 1, zz, x, x2, s);
                if (!ok) break;
                y2++;
            }
            for (int yy = y; yy <= y2; yy++) for (int zz = z; zz <= z2; zz++) for (int xx = x; xx <= x2; xx++) grid[(yy * d + zz) * w + xx] = -1;
            out.add(new int[]{x, y, z, x2, y2, z2, s});
        }
        return out;
    }

    private static boolean rowIs(int[] grid, int w, int d, int y, int z, int x1, int x2, int s) {
        int base = (y * d + z) * w;
        for (int x = x1; x <= x2; x++) if (grid[base + x] != s) return false;
        return true;
    }

    // ---- undo

    /** Turns what was sent into the commands that put the old blocks back: air first, then full cubes, then the rest, each newest first. */
    private void startUndo() {
        state = PasteJob.State.UNDOING;
        queue.clear();
        planned = false;
        undoCells = 0;
        undoDone = 0;
        settleAt = 0;
        credit = 0;
        int[] kind = new int[palette.size()];
        for (int i = 0; i < kind.length; i++) kind[i] = palette.get(i).isAir() ? 0 : full(palette.get(i)) ? 1 : 2;
        // a command that was sent but not yet seen is still covered: its cells are put back as well
        for (int g = 0; g < 3; g++) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                Cmd c = sent.get(i);
                if (c.old == null) continue;
                int w = c.x2 - c.x1 + 1, hh = c.y2 - c.y1 + 1, d = c.z2 - c.z1 + 1;
                int[] grid = new int[c.old.length];
                boolean any = false;
                for (int k = 0; k < grid.length; k++) {
                    grid[k] = kind[c.old[k]] == g ? c.old[k] : -1;
                    any |= grid[k] >= 0;
                }
                if (!any) continue;
                List<int[]> bs = boxes(grid, w, hh, d, CAP);
                bs.sort(Comparator.comparingInt((int[] b) -> -b[1]));
                for (int[] b : bs) {
                    BlockState old = palette.get(b[6]);
                    String st = BlockStateParser.serialize(old);
                    int x1 = c.x1 + b[0], y1 = c.y1 + b[1], z1 = c.z1 + b[2], x2 = c.x1 + b[3], y2 = c.y1 + b[4], z2 = c.z1 + b[5];
                    int cells = (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
                    String cmd = cells == 1 ? "setblock " + x1 + " " + y1 + " " + z1 + " " + st : "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + st ;
                    queue.add(new Cmd(cmd, cells, x1, y1, z1, x2, y2, z2, null, new BlockPos(x1, y1, z1), old));
                    undoCells += cells;
                }
            }
        }
        undone = (int) Math.min(Integer.MAX_VALUE, undoCells);
        undoRequested = false;
        inFlight.clear();
    }
}
