package io.github.profetgit.cyanotype.demo;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Dev-only blueprints the demo places: a small house with the awkward block kinds, and big ones for the perf lab. */
public final class Samples {
    private Samples() {
    }

    private static final class Grid {
        final int sx, sy, sz;
        final short[] blocks;
        final List<PaletteEntry> palette = new ArrayList<>(List.of(PaletteEntry.AIR));
        final Map<BlockState, Integer> index = new HashMap<>();

        Grid(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.blocks = new short[sx * sy * sz];
        }

        void set(int x, int y, int z, BlockState s) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return;
            int id = index.computeIfAbsent(s, k -> {
                palette.add(PaletteEntry.of(k));
                return palette.size() - 1;
            });
            blocks[(y * sz + z) * sx + x] = (short) id;
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) set(x, y, z, s);
        }

        Blueprint build(String name) {
            Region r = new Region("main", 0, 0, 0, sx, sy, sz, palette.toArray(new PaletteEntry[0]), blocks, new ArrayList<>());
            return new Blueprint(new Blueprint.Metadata(name, "Cyanotype demo", "dev sample", System.currentTimeMillis(), System.currentTimeMillis(), 0), List.of(r));
        }
    }

    /** 11 wide (x), 9 tall (y), 13 deep (z); the front door faces north (z = 0). */
    public static Blueprint house() {
        Grid g = new Grid(11, 10, 13);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState logY = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState glass = Blocks.GLASS_PANE.defaultBlockState();
        g.fill(0, 0, 0, 10, 0, 12, cobble);
        g.fill(1, 1, 1, 9, 4, 11, planks);
        g.fill(2, 1, 2, 8, 4, 10, Blocks.AIR.defaultBlockState());
        g.fill(0, 1, 0, 0, 4, 0, logY);
        g.fill(10, 1, 0, 10, 4, 0, logY);
        g.fill(0, 1, 12, 0, 4, 12, logY);
        g.fill(10, 1, 12, 10, 4, 12, logY);
        g.fill(0, 1, 1, 0, 4, 11, planks);
        g.fill(10, 1, 1, 10, 4, 11, planks);
        g.fill(1, 1, 0, 9, 4, 0, planks);
        g.fill(1, 1, 12, 9, 4, 12, planks);
        g.fill(1, 1, 1, 9, 4, 11, Blocks.AIR.defaultBlockState());
        for (int z : new int[]{3, 6, 9}) {
            g.set(0, 2, z, glass);
            g.set(10, 2, z, glass);
        }
        g.set(2, 2, 0, glass);
        g.set(8, 2, 0, glass);
        g.set(5, 1, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        g.set(5, 2, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        for (int z = 0; z <= 12; z++) {
            for (int i = 0; i < 5; i++) {
                g.set(i, 5 + i, z, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.BOTTOM));
                g.set(10 - i, 5 + i, z, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.HALF, Half.BOTTOM));
            }
            g.set(5, 9, z, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        }
        for (int i = 0; i < 4; i++) {
            g.fill(i + 1, 5 + i, 0, 9 - i, 5 + i, 0, planks);
            g.fill(i + 1, 5 + i, 12, 9 - i, 5 + i, 12, planks);
        }
        g.fill(8, 5, 9, 8, 9, 9, cobble);
        g.set(2, 1, 6, Blocks.LANTERN.defaultBlockState());
        g.set(5, 4, 6, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
        for (int x = 1; x <= 9; x++) {
            g.set(x, 1, 11, Blocks.OAK_FENCE.defaultBlockState());
        }
        return g.build("Cyanotype sample house");
    }

    /** A cube of stone bricks, hollow. */
    static Blueprint shell(int side, int thickness) {
        Grid g = new Grid(side, side, side);
        BlockState a = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState b = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        BlockState c = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        Random r = new Random(5);
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    boolean wall = x < thickness || y < thickness || z < thickness || x >= side - thickness || y >= side - thickness || z >= side - thickness;
                    if (!wall) continue;
                    int k = r.nextInt(8);
                    g.set(x, y, z, k == 0 ? b : k == 1 ? c : a);
                }
            }
        }
        return g.build("Cyanotype shell " + side);
    }

    /** A solid cube of mixed stone: only the outside shows. */
    static Blueprint solid(int side) {
        Grid g = new Grid(side, side, side);
        BlockState[] mix = {Blocks.STONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.DIORITE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()};
        Random r = new Random(11);
        for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) g.set(x, y, z, mix[r.nextInt(mix.length)]);
        return g.build("Cyanotype solid " + side);
    }

    /** A cube where each cell is filled with the given chance: nearly every block shows several faces, the worst case. */
    static Blueprint noise(int side, double density) {
        Grid g = new Grid(side, side, side);
        BlockState[] mix = {Blocks.OAK_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.BIRCH_PLANKS.defaultBlockState(), Blocks.DEEPSLATE_BRICKS.defaultBlockState()};
        Random r = new Random(23);
        for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) if (r.nextDouble() < density) g.set(x, y, z, mix[r.nextInt(mix.length)]);
        return g.build("Cyanotype noise " + side);
    }
}
