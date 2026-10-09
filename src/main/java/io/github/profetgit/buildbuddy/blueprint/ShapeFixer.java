package io.github.profetgit.buildbuddy.blueprint;

import io.github.profetgit.buildbuddy.BuildBuddy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gives the blocks whose look depends on their neighbours the look they have once they stand where the blueprint puts
 * them: a glass pane between two walls is a pane, not a post; a fence meets the fence beside it; a wall, a gate in a wall and
 * a stair corner follow their neighbours. The game does this itself when a block is placed ({@code updateShape}), and a
 * schematic saved from a world already has it, but a blueprint made some other way (a generator, a hand-written file, an
 * older format) holds the plain default state. The result is a new blueprint with extra palette slots for the variants; the
 * file on disk is not touched. Only blocks inside the same region are looked at.
 */
public final class ShapeFixer {
    private static boolean warned;

    private ShapeFixer() {
    }

    /** Blocks that read their neighbours: panes and bars, fences, walls, fence gates and stairs. */
    public static boolean dependsOnNeighbours(BlockState s) {
        Block b = s.getBlock();
        return b instanceof IronBarsBlock || b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof FenceGateBlock || b instanceof StairBlock;
    }

    /** @return the blueprint itself when nothing in it needs a different shape, else a new one */
    public static Blueprint fix(Blueprint bp) {
        List<Region> out = new ArrayList<>();
        boolean any = false;
        for (Region r : bp.regions) {
            Region f = fix(r);
            any |= f != r;
            out.add(f);
        }
        return any ? new Blueprint(bp.meta, out) : bp;
    }

    /** @return the region itself when nothing in it changes */
    public static Region fix(Region r) {
        boolean[] dependent = new boolean[r.palette.length];
        boolean any = false;
        for (int i = 0; i < dependent.length; i++) {
            dependent[i] = !r.palette[i].unknown() && dependsOnNeighbours(r.palette[i].state());
            any |= dependent[i];
        }
        if (!any) return r;

        Function<BlockPos, BlockState> stateAt = p -> r.contains(p.getX(), p.getY(), p.getZ()) ? r.palette[r.blocks[r.index(p.getX(), p.getY(), p.getZ())] & 0xFFFF].state() : AIR;
        LevelReader level = reader(stateAt);
        ScheduledTickAccess ticks = noTicks();
        RandomSource random = RandomSource.create(0L);

        short[] blocks = r.blocks.clone();
        List<PaletteEntry> palette = new ArrayList<>(List.of(r.palette));
        Map<BlockState, Integer> slotOf = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) if (!palette.get(i).unknown()) slotOf.putIfAbsent(palette.get(i).state(), i);
        boolean changed = false;
        for (int y = 0; y < r.sy; y++) {
            for (int z = 0; z < r.sz; z++) {
                for (int x = 0; x < r.sx; x++) {
                    int i = r.index(x, y, z);
                    int slot = r.blocks[i] & 0xFFFF;
                    if (!dependent[slot]) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState before = r.palette[slot].state(), state = before;
                    try {
                        for (Direction d : Direction.values()) {
                            BlockPos neighbour = pos.relative(d);
                            state = state.updateShape(level, ticks, pos, d, neighbour, stateAt.apply(neighbour), random);
                        }
                    } catch (RuntimeException e) {
                        if (!warned) {
                            warned = true;
                            BuildBuddy.LOG.warn("Could not work out the shape of {}: {}", before, e.toString());
                        }
                        continue;
                    }
                    if (state == before || state.equals(before)) continue;
                    Integer found = slotOf.get(state);
                    if (found == null) {
                        found = palette.size();
                        palette.add(PaletteEntry.of(state));
                        slotOf.put(state, found);
                    }
                    blocks[i] = (short) (int) found;
                    changed = true;
                }
            }
        }
        if (!changed || palette.size() > 65535) return r;
        return new Region(r.name, r.x, r.y, r.z, r.sx, r.sy, r.sz, palette.toArray(new PaletteEntry[0]), blocks, r.blockEntities);
    }

    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

    /** A level that is only the blocks of a region: what {@code updateShape} asks for, answered from it. */
    private static LevelReader reader(Function<BlockPos, BlockState> states) {
        InvocationHandler handler = (proxy, m, args) -> {
            switch (m.getName()) {
                case "getBlockState":
                    return states.apply((BlockPos) args[0]);
                case "getFluidState":
                    return states.apply((BlockPos) args[0]).getFluidState();
                case "getBlockEntity":
                    return null;
                case "getHeight":
                    return 4096;
                case "getMinY":
                    return -2048;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "RegionReader";
                default:
                    if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
                    throw new UnsupportedOperationException(m.getName());
            }
        };
        return (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(), new Class<?>[]{LevelReader.class}, handler);
    }

    /** Ticks nobody schedules: a waterlogged pane asks for one when its shape is updated. */
    private static ScheduledTickAccess noTicks() {
        InvocationHandler handler = (proxy, m, args) -> {
            switch (m.getName()) {
                case "scheduleTick":
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "NoTicks";
                default:
                    throw new UnsupportedOperationException(m.getName());
            }
        };
        return (ScheduledTickAccess) Proxy.newProxyInstance(ScheduledTickAccess.class.getClassLoader(), new Class<?>[]{ScheduledTickAccess.class}, handler);
    }
}
