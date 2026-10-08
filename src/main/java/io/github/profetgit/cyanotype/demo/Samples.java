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
        final List<net.minecraft.nbt.CompoundTag> tes = new ArrayList<>();

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

        /** A sign's block entity: the four lines of its front text. */
        void signText(int x, int y, int z, String... lines) {
            net.minecraft.nbt.CompoundTag te = new net.minecraft.nbt.CompoundTag();
            te.putString("id", "minecraft:sign");
            te.putInt("x", x);
            te.putInt("y", y);
            te.putInt("z", z);
            net.minecraft.nbt.CompoundTag front = new net.minecraft.nbt.CompoundTag();
            net.minecraft.nbt.ListTag messages = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < 4; i++) messages.add(net.minecraft.nbt.StringTag.valueOf(i < lines.length ? lines[i] : ""));
            front.put("messages", messages);
            front.putString("color", "black");
            front.putBoolean("has_glowing_text", false);
            te.put("front_text", front);
            tes.add(te);
        }

        Blueprint build(String name) {
            Region r = new Region("main", 0, 0, 0, sx, sy, sz, palette.toArray(new PaletteEntry[0]), blocks, tes);
            return new Blueprint(new Blueprint.Metadata(name, "Cyanotype demo", "dev sample", System.currentTimeMillis(), System.currentTimeMillis(), 0), List.of(r));
        }
    }

    /** A blueprint of one group of a lesson: the lesson's own cottage, so a recorded take shows the same blocks the lesson draws. */
    static Blueprint fromGroup(io.github.profetgit.cyanotype.ponder.Scene scene, String groupId, String name) {
        io.github.profetgit.cyanotype.ponder.Scene.Group grp = scene.group(groupId);
        Grid g = new Grid(grp.sx, grp.sy, grp.sz);
        for (int y = 0; y < grp.sy; y++) {
            for (int z = 0; z < grp.sz; z++) {
                for (int x = 0; x < grp.sx; x++) {
                    int c = grp.cell[grp.index(x, y, z)];
                    if (c != 0) g.set(x, y, z, io.github.profetgit.cyanotype.ponder.PonderLooks.INSTANCE.state(scene.palette.get(c - 1)));
                }
            }
        }
        return g.build(name);
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

    /**
     * 5 wide (x), 4 tall (y), 3 deep (z) with the kinds auto-placing has to get right: a floor, a ring of walls with log corners,
     * panes and a door, a torch on a wall, a hanging lantern, top and bottom slabs, and a roof of stairs facing all four ways.
     */
    public static Blueprint autoTest() {
        Grid g = new Grid(5, 4, 3);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState logY = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        g.fill(0, 0, 0, 4, 0, 2, Blocks.STONE_BRICKS.defaultBlockState());
        for (int y = 1; y <= 2; y++) {
            for (int x = 0; x <= 4; x++) {
                g.set(x, y, 0, planks);
                g.set(x, y, 2, planks);
            }
            g.set(0, y, 1, pane);
            g.set(4, y, 1, pane);
            for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 2}, {4, 2}}) g.set(c[0], y, c[1], logY);
        }
        g.set(2, 1, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        g.set(2, 2, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        g.set(1, 1, 1, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        g.set(3, 1, 1, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        g.set(1, 2, 1, Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, Direction.SOUTH));
        g.set(2, 2, 1, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
        for (int x = 0; x <= 4; x++) {
            g.set(x, 3, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH).setValue(StairBlock.HALF, Half.BOTTOM));
            g.set(x, 3, 2, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH).setValue(StairBlock.HALF, Half.BOTTOM));
        }
        g.set(0, 3, 1, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.BOTTOM));
        g.set(4, 3, 1, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.HALF, Half.BOTTOM));
        for (int x = 1; x <= 3; x++) g.set(x, 3, 1, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        return g.build("Auto test");
    }

    /** Auto-placing in a world of the player's own: a base, stairs facing all four ways, and a row sticking out two cells over air. */
    public static Blueprint autoOwn() {
        Grid g = new Grid(7, 3, 5);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        g.fill(1, 0, 1, 5, 0, 3, Blocks.STONE_BRICKS.defaultBlockState());
        g.set(3, 1, 2, planks);
        g.set(3, 2, 2, planks);
        g.set(3, 1, 1, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH).setValue(StairBlock.HALF, Half.BOTTOM));
        g.set(2, 1, 3, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH).setValue(StairBlock.HALF, Half.BOTTOM));
        g.set(1, 1, 2, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.BOTTOM));
        g.set(5, 1, 2, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.HALF, Half.BOTTOM));
        g.set(3, 2, 3, planks);
        g.set(3, 2, 4, planks);
        return g.build("Auto own");
    }

    /** Rows of blocks that read their neighbours, written with their plain default states (the reader gives them their shape). */
    static Blueprint shapes() {
        Grid g = new Grid(9, 3, 7);
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        // panes between stone pillars, two storeys
        for (int y = 0; y < 3; y++) {
            for (int x : new int[]{0, 4, 8}) g.set(x, y, 0, stone);
            for (int x : new int[]{1, 2, 3, 5, 6, 7}) g.set(x, y, 0, Blocks.GLASS_PANE.defaultBlockState());
        }
        // a fence row with a post at each end, and one made of stained panes
        for (int x = 0; x < 9; x++) g.set(x, 0, 2, Blocks.OAK_FENCE.defaultBlockState());
        for (int x = 0; x < 9; x++) g.set(x, 1, 2, Blocks.OAK_FENCE.defaultBlockState());
        // iron bars in a plus shape
        for (int x = 1; x <= 7; x++) g.set(x, 0, 4, Blocks.IRON_BARS.defaultBlockState());
        for (int z = 2; z <= 6; z++) if (z != 2 && z != 6) g.set(4, 0, z, Blocks.IRON_BARS.defaultBlockState());
        // a cobblestone wall with a gap
        for (int x = 0; x < 9; x++) if (x != 4) g.set(x, 0, 6, Blocks.COBBLESTONE_WALL.defaultBlockState());
        return g.build("Cyanotype shapes");
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

    /** A square tower with a door, windows and battlements. */
    /** A stone pillar with a water source on top: the water runs over the edges and down the sides, outside the blueprint's own cells. */
    static Blueprint waterPillar() {
        Grid g = new Grid(1, 5, 1);
        g.fill(0, 0, 0, 0, 3, 0, Blocks.STONE.defaultBlockState());
        g.set(0, 4, 0, Blocks.WATER.defaultBlockState());
        return g.build("Cyanotype water pillar");
    }

    public static Blueprint tower() {
        Grid g = new Grid(9, 24, 9);
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState(), mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        for (int y = 0; y < 20; y++) {
            for (int z = 0; z < 9; z++) {
                for (int x = 0; x < 9; x++) {
                    boolean wall = x == 0 || z == 0 || x == 8 || z == 8;
                    if (wall) g.set(x, y, z, (x + y + z) % 7 == 0 ? mossy : brick);
                }
            }
            if (y % 5 == 3) for (int i = 3; i <= 5; i++) {
                g.set(i, y, 0, Blocks.AIR.defaultBlockState());
                g.set(i, y, 8, Blocks.AIR.defaultBlockState());
            }
        }
        g.fill(0, 20, 0, 8, 20, 8, Blocks.SPRUCE_PLANKS.defaultBlockState());
        for (int i = 0; i < 9; i += 2) {
            g.set(i, 21, 0, brick);
            g.set(i, 21, 8, brick);
            g.set(0, 21, i, brick);
            g.set(8, 21, i, brick);
        }
        g.fill(3, 22, 3, 5, 22, 5, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        return g.build("Watch tower");
    }

    /** A stepped sandstone pyramid. */
    public static Blueprint pyramid() {
        int n = 17;
        Grid g = new Grid(n, 9, n);
        for (int y = 0; y < 9; y++) {
            g.fill(y, y, y, n - 1 - y, y, n - 1 - y, y % 2 == 0 ? Blocks.SANDSTONE.defaultBlockState() : Blocks.CUT_SANDSTONE.defaultBlockState());
            if (y > 0) g.fill(y + 1, y, y + 1, n - 2 - y, y, n - 2 - y, Blocks.AIR.defaultBlockState());
        }
        g.fill(7, 0, 0, 9, 2, 1, Blocks.AIR.defaultBlockState());
        g.set(8, 8, 8, Blocks.GOLD_BLOCK.defaultBlockState());
        return g.build("Little pyramid");
    }

    /** A stone arch bridge over a gap. */
    public static Blueprint bridge() {
        Grid g = new Grid(27, 9, 5);
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState(), slab = Blocks.STONE_BRICK_SLAB.defaultBlockState();
        for (int x = 0; x < 27; x++) {
            double u = (x - 13) / 13.0;
            int top = (int) Math.round(5 - 4 * u * u);
            g.fill(x, top, 0, x, top, 4, stone);
            g.fill(x, Math.max(0, top - 1), 1, x, top - 1, 3, stone);
            if (x % 3 == 0) {
                g.set(x, top + 1, 0, Blocks.STONE_BRICK_WALL.defaultBlockState());
                g.set(x, top + 1, 4, Blocks.STONE_BRICK_WALL.defaultBlockState());
            }
        }
        for (int y = 0; y < 3; y++) {
            g.fill(0, y, 0, 1, y, 4, stone);
            g.fill(25, y, 0, 26, y, 4, stone);
        }
        g.set(13, 6, 2, slab);
        return g.build("Arch bridge");
    }

    /** A box of stone bricks, solid: what a mass fill can match exactly. */
    static Blueprint uniform(int sx, int sy, int sz) {
        Grid g = new Grid(sx, sy, sz);
        g.fill(0, 0, 0, sx - 1, sy - 1, sz - 1, Blocks.STONE_BRICKS.defaultBlockState());
        return g.build("Cyanotype uniform " + sx + "x" + sy + "x" + sz);
    }

    /** A cube where each cell is filled with the given chance: nearly every block shows several faces, the worst case. */
    static Blueprint noise(int side, double density) {
        Grid g = new Grid(side, side, side);
        BlockState[] mix = {Blocks.OAK_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.BIRCH_PLANKS.defaultBlockState(), Blocks.DEEPSLATE_BRICKS.defaultBlockState()};
        Random r = new Random(23);
        for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) if (r.nextDouble() < density) g.set(x, y, z, mix[r.nextInt(mix.length)]);
        return g.build("Cyanotype noise " + side);
    }

    private static BlockState slab(SlabType t) {
        return Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, t);
    }

    /**
     * 9 wide (x), 4 tall (y), 4 deep (z): a stone floor, a plank wall behind (z 1), and in front of it (z 0) bottom, top and double
     * oak slabs on the floor, on each other, beside a block and under the roof of planks. With {@code floating}, three more in the air
     * at the back (z 3): a top, a double and a bottom slab with nothing near them. A bottom slab on the floor at the back holds up a plank block.
     */
    public static Blueprint slabs(boolean floating) {
        Grid g = new Grid(9, 4, 4);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        g.fill(0, 0, 0, 8, 0, 3, Blocks.STONE_BRICKS.defaultBlockState());
        g.fill(0, 1, 1, 8, 2, 1, planks);
        g.fill(0, 3, 0, 8, 3, 1, planks);
        g.set(1, 1, 0, slab(SlabType.BOTTOM));
        g.set(3, 1, 0, slab(SlabType.TOP));
        g.set(5, 1, 0, slab(SlabType.DOUBLE));
        g.set(7, 1, 0, planks);
        g.set(1, 2, 0, slab(SlabType.TOP));
        g.set(3, 2, 0, slab(SlabType.DOUBLE));
        g.set(5, 2, 0, slab(SlabType.BOTTOM));
        g.set(7, 2, 0, slab(SlabType.TOP));
        // behind the wall, on the floor: a block that only a half slab holds up
        g.set(7, 1, 3, slab(SlabType.BOTTOM));
        g.set(7, 2, 3, planks);
        if (floating) {
            g.set(1, 2, 3, slab(SlabType.TOP));
            g.set(3, 2, 3, slab(SlabType.DOUBLE));
            g.set(5, 2, 3, slab(SlabType.BOTTOM));
        }
        return g.build(floating ? "Slabs floating" : "Slabs");
    }

    /** A plank cottage with one standing sign and one wall sign, each with text, and a hanging sign under the roof. */
    public static Blueprint signs() {
        Grid g = new Grid(7, 4, 4);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        g.fill(0, 0, 0, 6, 0, 3, Blocks.STONE_BRICKS.defaultBlockState());
        g.fill(0, 1, 3, 6, 2, 3, planks);
        g.fill(0, 3, 0, 6, 3, 3, planks);
        g.set(1, 1, 1, Blocks.OAK_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, 0));
        g.signText(1, 1, 1, "Standing", "sign");
        g.set(3, 1, 2, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.WallSignBlock.FACING, Direction.NORTH));
        g.signText(3, 1, 2, "Wall sign", "of the", "blueprint");
        g.set(5, 2, 2, Blocks.OAK_HANGING_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.CeilingHangingSignBlock.ROTATION, 0));
        g.signText(5, 2, 2, "Hanging", "text");
        return g.build("Signs");
    }

    /** A 7x7 stone floor under walls three high on its rim and a plank roof: five layers. */
    public static Blueprint layers() {
        Grid g = new Grid(7, 5, 7);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        g.fill(0, 0, 0, 6, 0, 6, Blocks.STONE_BRICKS.defaultBlockState());
        for (int y = 1; y <= 3; y++) {
            g.fill(0, y, 0, 6, y, 0, planks);
            g.fill(0, y, 6, 6, y, 6, planks);
            g.fill(0, y, 1, 0, y, 5, planks);
            g.fill(6, y, 1, 6, y, 5, planks);
        }
        g.fill(0, 4, 0, 6, 4, 6, planks);
        return g.build("Layers");
    }
}
