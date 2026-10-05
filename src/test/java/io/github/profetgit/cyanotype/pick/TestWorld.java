package io.github.profetgit.cyanotype.pick;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** A made-up world for the picker's tests: flat ground (stone, dirt, grass at y 0) with builds set into it. */
final class TestWorld implements Picker.Field {
    final Map<Long, BlockState> blocks = new HashMap<>();
    /** Cells the test calls "the build": what a perfect pick would return. */
    final LongOpenHashSet built = new LongOpenHashSet();
    int loadedBelowX = Integer.MAX_VALUE;
    boolean flat = true;

    @Override
    public BlockState state(int x, int y, int z) {
        BlockState s = blocks.get(BlockPos.asLong(x, y, z));
        if (s != null) return s;
        if (!flat) return Blocks.AIR.defaultBlockState();
        if (y == 0) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (y < 0 && y >= -5) return Blocks.DIRT.defaultBlockState();
        if (y < -5) return Blocks.STONE.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public boolean loaded(int x, int z) {
        return x < loadedBelowX;
    }

    void set(int x, int y, int z, Block b) {
        set(x, y, z, b.defaultBlockState(), true);
    }

    void set(int x, int y, int z, BlockState s, boolean isBuilt) {
        blocks.put(BlockPos.asLong(x, y, z), s);
        if (isBuilt && !s.isAir()) built.add(BlockPos.asLong(x, y, z));
    }

    /** Terrain, plants, trees: set into the world but not part of any build. */
    void scenery(int x, int y, int z, Block b) {
        blocks.put(BlockPos.asLong(x, y, z), b.defaultBlockState());
    }

    void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) set(x, y, z, b);
    }

    void sceneryFill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) scenery(x, y, z, b);
    }

    /** A box of walls with a floor and a flat roof, hollow inside. */
    void hut(int x0, int z0, int x1, int z1, int h, Block wall, Block floor, Block roof) {
        fill(x0, 1, z0, x1, 1, z1, floor);
        for (int y = 2; y < 2 + h; y++) {
            for (int x = x0; x <= x1; x++) {
                set(x, y, z0, wall);
                set(x, y, z1, wall);
            }
            for (int z = z0; z <= z1; z++) {
                set(x0, y, z, wall);
                set(x1, y, z, wall);
            }
        }
        fill(x0, 2 + h, z0, x1, 2 + h, z1, roof);
    }

    void remove(int x, int y, int z) {
        blocks.put(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        built.remove(BlockPos.asLong(x, y, z));
    }

    static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** Runs a pick to the end. */
    Picker.Result pick(int x, int y, int z, int reach) {
        return pick(x, y, z, reach, Picker.DEFAULT_LIMIT, null);
    }

    Picker.Result pick(int x, int y, int z, int reach, int limit, Block force) {
        Picker.Job job = new Picker.Job(this, key(x, y, z), reach, limit, force);
        while (!job.step(50_000_000L)) {
            // run
        }
        return job.result();
    }

    /** The cells of the seed's part. */
    static LongOpenHashSet seedPart(Picker.Result r) {
        return new LongOpenHashSet(r.members(r.seedPart));
    }
}
