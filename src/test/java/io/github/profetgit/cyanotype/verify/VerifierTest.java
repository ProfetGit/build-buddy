package io.github.profetgit.cyanotype.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.ghost.OrientedRegion;
import io.github.profetgit.cyanotype.placement.Orientation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VerifierTest {
    @BeforeAll
    static void boot() {
        io.github.profetgit.cyanotype.TestBootstrap.init();
    }

    /** A world that is a map of blocks, with chunks that can be unloaded. */
    static final class FakeWorld implements WorldView {
        final Map<Long, BlockState> blocks = new HashMap<>();
        final Set<Long> unloaded = new HashSet<>();

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        public BlockState get(int x, int y, int z) {
            return blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean loaded(int cx, int cz) {
            return !unloaded.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL));
        }
    }

    private static Blueprint blueprint(int sx, int sy, int sz, java.util.function.BiFunction<int[], Integer, BlockState> fill) {
        List<PaletteEntry> palette = new ArrayList<>(List.of(PaletteEntry.AIR));
        Map<BlockState, Integer> index = new HashMap<>();
        short[] blocks = new short[sx * sy * sz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState s = fill.apply(new int[]{x, y, z}, 0);
                    if (s == null || s.isAir()) continue;
                    int id = index.computeIfAbsent(s, k -> {
                        palette.add(PaletteEntry.of(k));
                        return palette.size() - 1;
                    });
                    blocks[(y * sz + z) * sx + x] = (short) id;
                }
            }
        }
        Region r = new Region("main", 0, 0, 0, sx, sy, sz, palette.toArray(new PaletteEntry[0]), blocks, new ArrayList<>());
        return new Blueprint(Blueprint.Metadata.of("t"), List.of(r));
    }

    private static Verifier verifier(Blueprint bp, Orientation o, int ox, int oy, int oz) {
        List<OrientedRegion> regions = new ArrayList<>();
        for (Region r : bp.regions) regions.add(OrientedRegion.of(bp, r, o));
        return new Verifier(regions, ox, oy, oz, Matcher.LENIENT);
    }

    private static void run(Verifier v, WorldView w) {
        for (int i = 0; i < 100000 && v.process(w, 50_000_000L, 0, 0, 0); i++) {
            // keep going until nothing is left
        }
    }

    /** Builds the whole placement into the fake world exactly as the blueprint says. */
    private static void buildAll(Verifier v, FakeWorld w) {
        for (Verifier.Part p : v.parts) {
            OrientedRegion r = p.region;
            for (int y = 0; y < r.sy; y++) for (int z = 0; z < r.sz; z++) for (int x = 0; x < r.sx; x++) {
                BlockState s = r.state(x, y, z);
                if (!s.isAir()) w.set(p.wx + x, p.wy + y, p.wz + z, s);
            }
        }
    }

    private static final BlockState STONE = Blocks.STONE.defaultBlockState(), PLANKS = Blocks.OAK_PLANKS.defaultBlockState(), DIRT = Blocks.DIRT.defaultBlockState();

    @Test
    void countsMissingWrongAndCorrect() {
        // a 4 x 3 x 4 box: stone floor (16), planks column at (1,1,1) and (1,2,1)
        Blueprint bp = blueprint(4, 3, 4, (c, k) -> c[1] == 0 ? STONE : (c[0] == 1 && c[2] == 1 ? PLANKS : null));
        Verifier v = verifier(bp, Orientation.NONE, 100, 64, -50);
        FakeWorld w = new FakeWorld();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) w.set(100 + x, 64, -50 + z, STONE);
        w.set(101, 65, -49, PLANKS);          // right
        w.set(101, 66, -49, DIRT);            // wrong: something else stands where planks belong
        // remove one floor block and put water in another: both are "missing" (water can be built over)
        w.set(100, 64, -50, Blocks.AIR.defaultBlockState());
        w.set(102, 64, -50, Blocks.WATER.defaultBlockState());
        run(v, w);
        Counts c = v.counts();
        assertEquals(16 - 2 + 1, c.correct());
        assertEquals(2, c.missing());
        assertEquals(1, c.wrong());
        assertEquals(0, c.unknown());
        assertEquals(3, c.todo());
        assertEquals(14 / 18.0 + 1 / 18.0, c.progress(), 1e-9);
        assertFalse(c.done());
        assertEquals(Verifier.MISSING, v.statusAt(100, 64, -50));
        assertEquals(Verifier.MISSING, v.statusAt(102, 64, -50));
        assertEquals(Verifier.CORRECT, v.statusAt(101, 65, -49));
        assertEquals(Verifier.WRONG, v.statusAt(101, 66, -49));
        assertEquals(Verifier.NONE, v.statusAt(103, 66, -47), "air in the blueprint is not part of the build");
        assertEquals(Verifier.NONE, v.statusAt(0, 0, 0), "outside the placement");
        // layers add up
        assertEquals(14, v.layerCount(0, Verifier.CORRECT));
        assertEquals(2, v.layerCount(0, Verifier.MISSING));
        assertEquals(1, v.layerCount(1, Verifier.CORRECT));
        assertEquals(1, v.layerCount(2, Verifier.WRONG));
    }

    @Test
    void aChangeIsPickedUpAtOnceAndOnlyBumpsItsSection() {
        // two sections wide: x 0..31
        Blueprint bp = blueprint(32, 2, 4, (c, k) -> c[1] == 0 ? STONE : null);
        Verifier v = verifier(bp, Orientation.NONE, 0, 0, 0);
        FakeWorld w = new FakeWorld();
        buildAll(v, w);
        run(v, w);
        assertTrue(v.counts().done());
        int left = v.version(0, 0), right = v.version(0, 1);
        // break a block in the right-hand section
        w.set(20, 0, 2, Blocks.AIR.defaultBlockState());
        v.markDirty(BlockPos.asLong(20, 0, 2));
        run(v, w);
        assertEquals(1, v.counts().missing());
        assertEquals(left, v.version(0, 0), "the left section did not change");
        assertEquals(right + 1, v.version(0, 1));
        // mending it brings it back
        w.set(20, 0, 2, STONE);
        v.markDirty(BlockPos.asLong(20, 0, 2));
        run(v, w);
        assertTrue(v.counts().done());
        // a change outside the placement costs nothing
        v.markDirty(BlockPos.asLong(500, 0, 2));
        assertTrue(v.settled());
    }

    @Test
    void theScanIsDoneInSlicesAndNearestFirst() {
        Blueprint bp = blueprint(64, 1, 16, (c, k) -> STONE);
        Verifier v = verifier(bp, Orientation.NONE, 0, 0, 0);
        FakeWorld w = new FakeWorld();
        buildAll(v, w);
        // a budget of nothing still makes progress: at least one section per call, the player's own first
        assertTrue(v.process(w, 0, 60, 0, 8));
        assertTrue(v.ready(0, 3), "the section the player stands over is checked first");
        assertFalse(v.ready(0, 0));
        int calls = 1;
        while (v.process(w, 0, 60, 0, 8)) calls++;
        // the call that does the last section reports nothing left, so three more calls returned true
        assertEquals(3, calls, "one section per call, four sections");
        assertTrue(v.counts().done());
    }

    @Test
    void unloadedChunksAreNotHeldAgainstTheBuilder() {
        Blueprint bp = blueprint(32, 1, 16, (c, k) -> STONE);
        Verifier v = verifier(bp, Orientation.NONE, 0, 0, 0);
        FakeWorld w = new FakeWorld();
        buildAll(v, w);
        w.unloaded.add((1L << 32) | 0);    // chunk column x = 1, z = 0
        run(v, w);
        assertEquals(256, v.counts().correct());
        assertEquals(256, v.counts().unloaded());
        assertFalse(v.counts().done(), "unloaded blocks mean it cannot be called done");
        assertEquals(1.0, v.counts().progress(), 1e-9, "but nothing judged is wrong");
        // the chunk arrives: the poll notices and the section is looked at again
        w.unloaded.clear();
        for (int i = 0; i < 40; i++) v.process(w, 50_000_000L, 0, 0, 0);
        assertEquals(512, v.counts().correct());
        assertEquals(0, v.counts().unloaded());
        assertTrue(v.counts().done());
    }

    @Test
    void unknownBlocksCanNeverBeCorrect() {
        List<PaletteEntry> palette = List.of(PaletteEntry.AIR, PaletteEntry.read(unknownTag()));
        Region r = new Region("main", 0, 0, 0, 2, 1, 1, palette.toArray(new PaletteEntry[0]), new short[]{1, 1}, new ArrayList<>());
        Blueprint bp = new Blueprint(Blueprint.Metadata.of("t"), List.of(r));
        Verifier v = verifier(bp, Orientation.NONE, 0, 0, 0);
        FakeWorld w = new FakeWorld();
        w.set(0, 0, 0, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());   // looks like the stand-in, is not it
        run(v, w);
        assertEquals(2, v.counts().unknown());
        assertEquals(0, v.counts().correct());
        assertEquals(0, v.counts().todo());
    }

    private static net.minecraft.nbt.CompoundTag unknownTag() {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putString("Name", "othermod:gadget");
        return t;
    }

    @Test
    void aTurnedPlacementIsJudgedInItsOwnCoordinates() {
        // an asymmetric build: stairs, a door, a slab, and a block in each corner of a 3 x 2 x 5 box
        Blueprint bp = blueprint(3, 2, 5, (c, k) -> {
            if (c[0] == 0 && c[2] == 0) return STONE;
            if (c[0] == 2 && c[2] == 4) return PLANKS;
            if (c[0] == 1 && c[1] == 0 && c[2] == 2) return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
            if (c[0] == 2 && c[1] == 1 && c[2] == 1) return Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
            return null;
        });
        for (Rotation rot : Rotation.values()) {
            for (Mirror mir : Mirror.values()) {
                Verifier v = verifier(bp, new Orientation(rot, mir), 10, 70, 20);
                FakeWorld w = new FakeWorld();
                buildAll(v, w);
                run(v, w);
                assertTrue(v.counts().done(), rot + " " + mir + ": " + v.counts());
                assertEquals(6, v.counts().correct(), rot + " " + mir);
                // and one block taken out shows as missing, in the turned coordinates
                Verifier.Part p = v.parts.get(0);
                int hit = -1;
                for (int i = 0; i < p.status.length && hit < 0; i++) if (p.status[i] == Verifier.CORRECT) hit = i;
                int lx = hit % p.region.sx, lz = (hit / p.region.sx) % p.region.sz, ly = hit / (p.region.sx * p.region.sz);
                w.set(p.wx + lx, p.wy + ly, p.wz + lz, Blocks.AIR.defaultBlockState());
                v.markDirty(BlockPos.asLong(p.wx + lx, p.wy + ly, p.wz + lz));
                run(v, w);
                assertEquals(1, v.counts().missing(), rot + " " + mir);
                assertEquals(5, v.counts().correct(), rot + " " + mir);
            }
        }
    }

    @Test
    void nextTargetBuildsBottomUpNearestFirst() {
        Blueprint bp = blueprint(6, 3, 6, (c, k) -> STONE);
        Verifier v = verifier(bp, Orientation.NONE, 0, 0, 0);
        FakeWorld w = new FakeWorld();
        buildAll(v, w);
        // layer 0 has a hole at (5,0,5), layer 1 a hole at (0,1,0)
        w.blocks.remove(BlockPos.asLong(5, 0, 5));
        w.blocks.remove(BlockPos.asLong(0, 1, 0));
        run(v, w);
        assertEquals(BlockPos.asLong(5, 0, 5), v.nextTarget(0, 5, 0, -1, -1), "the lowest unfinished layer first, wherever the player is");
        assertEquals(BlockPos.asLong(0, 1, 0), v.nextTarget(0, 5, 0, 1, -1), "limited to layers 1 and up");
        assertEquals(Verifier.NO_TARGET, v.nextTarget(0, 5, 0, 2, 2), "layer 2 is complete");
        // two holes in the bottom layer: the nearer to the player
        w.blocks.remove(BlockPos.asLong(0, 0, 3));
        v.markDirty(BlockPos.asLong(0, 0, 3));
        run(v, w);
        assertEquals(BlockPos.asLong(0, 0, 3), v.nextTarget(0, 1, 3, -1, -1));
        assertEquals(BlockPos.asLong(5, 0, 5), v.nextTarget(6, 1, 6, -1, -1));
    }

    @Test
    void lenientMatchingIgnoresWhatPlayersAndTheGameChange() {
        Matcher m = Matcher.LENIENT;
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState();
        assertTrue(m.matches(stairs.setValue(StairBlock.SHAPE, StairsShape.INNER_LEFT), stairs), "the corner shape follows the neighbours");
        assertFalse(m.matches(stairs.setValue(StairBlock.FACING, Direction.SOUTH), stairs), "facing is the build");
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        assertTrue(m.matches(door.setValue(DoorBlock.OPEN, true), door), "doors get opened");
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        assertTrue(m.matches(leaves.setValue(LeavesBlock.DISTANCE, 5), leaves));
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState();
        assertFalse(m.matches(slab.setValue(SlabBlock.TYPE, SlabType.TOP), slab.setValue(SlabBlock.TYPE, SlabType.BOTTOM)), "top or bottom is the build");
        assertTrue(m.matches(Blocks.OAK_FENCE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceBlock.NORTH, true), Blocks.OAK_FENCE.defaultBlockState()), "fences connect by themselves");
        assertTrue(m.matches(Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState()));
        assertFalse(m.matches(STONE, Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(m.matches(Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3)));
        // strict wants every property
        assertFalse(Matcher.STRICT.matches(door.setValue(DoorBlock.OPEN, true), door));
    }
}
