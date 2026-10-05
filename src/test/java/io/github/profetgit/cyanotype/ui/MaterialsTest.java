package io.github.profetgit.cyanotype.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.TestBootstrap;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.ghost.OrientedRegion;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.verify.Matcher;
import io.github.profetgit.cyanotype.verify.Verifier;
import io.github.profetgit.cyanotype.verify.WorldView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaterialsTest {
    // the block constants below load vanilla's registries: they need the bootstrap first
    static {
        TestBootstrap.init();
    }

    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static final class FakeWorld implements WorldView {
        final Map<Long, BlockState> blocks = new HashMap<>();

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        public BlockState get(int x, int y, int z) {
            return blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean loaded(int cx, int cz) {
            return true;
        }
    }

    private static Blueprint blueprint(int sx, int sy, int sz, BiFunction<int[], Integer, BlockState> fill) {
        List<PaletteEntry> palette = new ArrayList<>(List.of(PaletteEntry.AIR));
        Map<BlockState, Integer> index = new HashMap<>();
        short[] blocks = new short[sx * sy * sz];
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            BlockState s = fill.apply(new int[]{x, y, z}, 0);
            if (s == null || s.isAir()) continue;
            int id = index.computeIfAbsent(s, k -> {
                palette.add(PaletteEntry.of(k));
                return palette.size() - 1;
            });
            blocks[(y * sz + z) * sx + x] = (short) id;
        }
        Region r = new Region("main", 0, 0, 0, sx, sy, sz, palette.toArray(new PaletteEntry[0]), blocks, new ArrayList<>());
        return new Blueprint(Blueprint.Metadata.of("t"), List.of(r));
    }

    private static Verifier scan(Blueprint bp, FakeWorld w) {
        List<OrientedRegion> regions = new ArrayList<>();
        for (Region r : bp.regions) regions.add(OrientedRegion.of(bp, r, Orientation.NONE));
        Verifier v = new Verifier(regions, 0, 0, 0, Matcher.LENIENT);
        for (int i = 0; i < 100000 && v.process(w, 50_000_000L, 0, 0, 0); i++) {
            // until nothing is left
        }
        return v;
    }

    private static final BlockState STONE = Blocks.STONE.defaultBlockState(), OAK = Blocks.OAK_PLANKS.defaultBlockState(),
        SPRUCE = Blocks.SPRUCE_PLANKS.defaultBlockState(), DIRT = Blocks.DIRT.defaultBlockState();

    /** floor of stone (layer 0, 16 blocks), 4 oak planks on layer 1, 3 spruce planks on layer 2. */
    private static Blueprint house() {
        return blueprint(4, 3, 4, (c, k) -> {
            if (c[1] == 0) return STONE;
            if (c[1] == 1 && c[2] == 0) return OAK;
            if (c[1] == 2 && c[2] == 0 && c[0] < 3) return SPRUCE;
            return null;
        });
    }

    /** Item names and stack sizes need the game's data; here an item's id and 64 stand in. */
    private static final Materials.ItemInfo INFO = new Materials.ItemInfo() {
        @Override
        public String name(Item item) {
            return Materials.idOf(item);
        }

        @Override
        public int stackSize(Item item) {
            return item == Items.OAK_DOOR ? 1 : 64;
        }
    };

    private static Materials.Result compute(Verifier v, int lo, int hi, java.util.function.ToIntFunction<Item> have, boolean group) {
        return Materials.compute(v, lo, hi, have, group, INFO);
    }

    private static Materials.Row row(Materials.Result r, Item item) {
        return r.rows().stream().filter(x -> x.icon() == item).findFirst().orElse(null);
    }

    // ---- stacks

    @Test
    void stacksAreWrittenTheWayPlayersCountThem() {
        assertEquals("0", Materials.stacks(0, 64));
        assertEquals("12", Materials.stacks(12, 64));
        assertEquals("63", Materials.stacks(63, 64));
        assertEquals("1 stack", Materials.stacks(64, 64));
        assertEquals("1 stack + 1", Materials.stacks(65, 64));
        assertEquals("3 stacks + 12", Materials.stacks(3 * 64 + 12, 64));
        assertEquals("2 stacks", Materials.stacks(128, 64));
        assertEquals("2 stacks + 8", Materials.stacks(40, 16));
        assertEquals("5", Materials.stacks(5, 1), "things that do not stack are just counted");
    }

    // ---- blocks to items

    @Test
    void doubleSlabsCostTwoAndOtherSlabsOne() {
        BlockState bottom = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState dbl = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        assertEquals(new Materials.Cost(Items.OAK_SLAB, 1), Materials.costOf(bottom));
        assertEquals(new Materials.Cost(Items.OAK_SLAB, 2), Materials.costOf(dbl));
    }

    @Test
    void thingsThatComeWithAnotherBlockCostNothing() {
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        assertEquals(new Materials.Cost(Items.OAK_DOOR, 1), Materials.costOf(door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)));
        assertNull(Materials.costOf(door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)), "the top half of a door");
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState();
        assertEquals(new Materials.Cost(Items.BED.pick(DyeColor.RED), 1), Materials.costOf(bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)));
        assertNull(Materials.costOf(bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)), "the head of a bed");
        assertNull(Materials.costOf(Blocks.WATER.defaultBlockState()));
        assertNull(Materials.costOf(Blocks.LAVA.defaultBlockState()));
        assertNull(Materials.costOf(Blocks.PISTON_HEAD.defaultBlockState()));
        assertNull(Materials.costOf(Blocks.MOVING_PISTON.defaultBlockState()));
        assertNull(Materials.costOf(Blocks.AIR.defaultBlockState()));
        assertTrue(Materials.companion(Blocks.WATER.defaultBlockState()));
        assertFalse(Materials.companion(OAK));
    }

    @Test
    void blocksThatStandForSeveralItemsCountThemAll() {
        assertEquals(new Materials.Cost(Items.DYED_CANDLE.pick(DyeColor.WHITE), 3), Materials.costOf(Blocks.DYED_CANDLE.pick(DyeColor.WHITE).defaultBlockState().setValue(BlockStateProperties.CANDLES, 3)));
        assertEquals(new Materials.Cost(Items.SNOW, 5), Materials.costOf(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 5)));
        assertEquals(new Materials.Cost(Items.SEA_PICKLE, 2), Materials.costOf(Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES, 2)));
        assertEquals(new Materials.Cost(Items.TURTLE_EGG, 4), Materials.costOf(Blocks.TURTLE_EGG.defaultBlockState().setValue(BlockStateProperties.EGGS, 4)));
    }

    @Test
    void wallVariantsAskForTheSameItem() {
        Materials.Cost standing = Materials.costOf(Blocks.TORCH.defaultBlockState());
        Materials.Cost wall = Materials.costOf(Blocks.WALL_TORCH.defaultBlockState());
        assertNotNull(standing);
        assertNotNull(wall);
        assertEquals(standing.item(), wall.item());
    }

    // ---- families

    @Test
    void variantsOfWoodAndDyeShareAFamily() {
        assertEquals(Materials.familyOf("oak_planks"), Materials.familyOf("spruce_planks"));
        assertEquals("Planks (any wood)", Materials.familyOf("dark_oak_planks").label());
        assertEquals(Materials.familyOf("red_wool"), Materials.familyOf("light_blue_wool"));
        assertEquals("Wool (any colour)", Materials.familyOf("light_gray_wool").label());
        assertEquals("Stripped log (any wood)", Materials.familyOf("stripped_birch_log").label());
        assertFalse(Materials.familyOf("oak_planks").equals(Materials.familyOf("red_wool")));
        assertNull(Materials.familyOf("stone_bricks"));
        assertNull(Materials.familyOf("oak"), "a bare wood name has nothing after it");
        assertNull(Materials.familyOf("cobblestone"));
    }

    // ---- the list

    @Test
    void needsAreTheMissingAndWrongBlocksPerItem() {
        FakeWorld w = new FakeWorld();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) if (x + z < 4) w.set(x, 0, z, STONE);   // 10 of 16 floor blocks
        w.set(0, 1, 0, OAK);                                                                                // 1 of 4 oak right
        w.set(1, 1, 0, DIRT);                                                                               // wrong: still needs oak
        Verifier v = scan(house(), w);
        Materials.Result r = compute(v, -1, -1, item -> 0, false);
        assertEquals(6, row(r, Items.STONE).needed());
        assertEquals(3, row(r, Items.OAK_PLANKS).needed(), "one is right, one is dirt, two are missing");
        assertEquals(3, row(r, Items.SPRUCE_PLANKS).needed());
        assertEquals(12, r.blocks());
        assertEquals(0, r.noItem());
        assertEquals(3, r.rows().size());
    }

    @Test
    void whatYouHaveComesOffAndNeverGoesBelowZero() {
        Verifier v = scan(house(), new FakeWorld());
        Map<Item, Integer> have = Map.of(Items.STONE, 20, Items.OAK_PLANKS, 3);
        Materials.Result r = compute(v, -1, -1, i -> have.getOrDefault(i, 0), false);
        Materials.Row stone = row(r, Items.STONE), oak = row(r, Items.OAK_PLANKS), spruce = row(r, Items.SPRUCE_PLANKS);
        assertEquals(16, stone.needed());
        assertEquals(0, stone.missing(), "more than enough is not a negative shortage");
        assertEquals(4, oak.needed());
        assertEquals(1, oak.missing());
        assertEquals(3, spruce.missing());
        assertEquals("1", oak.missingText());
        assertEquals("", stone.missingText());
    }

    @Test
    void biggestShortageComesFirst() {
        Verifier v = scan(house(), new FakeWorld());
        Materials.Result r = compute(v, -1, -1, item -> 0, false);
        assertEquals(Items.STONE, r.rows().get(0).icon());
        assertEquals(Items.OAK_PLANKS, r.rows().get(1).icon());
        assertEquals(Items.SPRUCE_PLANKS, r.rows().get(2).icon());
        // having stone moves it to the end
        Materials.Result r2 = compute(v, -1, -1, i -> i == Items.STONE ? 99 : 0, false);
        assertEquals(Items.STONE, r2.rows().get(2).icon());
        assertEquals(Items.OAK_PLANKS, r2.rows().get(0).icon());
    }

    @Test
    void aLayerFilterCountsOnlyThoseLayers() {
        Verifier v = scan(house(), new FakeWorld());
        Materials.Result floor = compute(v, 0, 0, item -> 0, false);
        assertEquals(1, floor.rows().size());
        assertEquals(16, floor.rows().get(0).needed());
        Materials.Result second = compute(v, 1, 1, item -> 0, false);
        assertEquals(1, second.rows().size());
        assertEquals(Items.OAK_PLANKS, second.rows().get(0).icon());
        Materials.Result both = compute(v, 1, 2, item -> 0, false);
        assertEquals(2, both.rows().size());
        Materials.Result fromTwo = compute(v, 2, -1, item -> 0, false);
        assertEquals(Items.SPRUCE_PLANKS, fromTwo.rows().get(0).icon());
        assertEquals(1, fromTwo.rows().size());
        Materials.Result all = compute(v, -1, -1, item -> 0, false);
        assertEquals(16 + 4 + 3, all.blocks());
    }

    @Test
    void groupingAddsUpVariantsAndShowsOneLine() {
        Verifier v = scan(house(), new FakeWorld());
        Materials.Result r = compute(v, -1, -1, i -> i == Items.OAK_PLANKS ? 5 : 0, true);
        assertEquals(2, r.rows().size(), "stone and one line for both planks");
        Materials.Row planks = r.rows().stream().filter(Materials.Row::group).findFirst().orElseThrow();
        assertEquals("Planks (any wood)", planks.name());
        assertEquals(7, planks.needed());
        assertEquals(5, planks.have());
        assertEquals(2, planks.missing(), "oak planks you hold cover spruce ones you need");
        assertEquals(2, planks.variants());
        assertEquals(2, planks.slots().size());
        assertEquals(Items.OAK_PLANKS, planks.icon(), "the most needed one is drawn");
        assertEquals(4 + 3, planks.cells());
    }

    @Test
    void aLoneVariantIsNotAGroup() {
        Blueprint bp = blueprint(2, 1, 2, (c, k) -> Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
        Materials.Result r = compute(scan(bp, new FakeWorld()), -1, -1, i -> 0, true);
        assertEquals(1, r.rows().size());
        assertFalse(r.rows().get(0).group());
        assertEquals(Items.WOOL.pick(DyeColor.RED), r.rows().get(0).icon());
    }

    @Test
    void doorsSlabsAndBedsAreCountedAsPlayersBuildThem() {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        BlockState dbl = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        Blueprint bp = blueprint(3, 2, 1, (c, k) -> c[0] == 0 ? (c[1] == 0 ? lower : upper) : c[0] == 1 ? dbl : Blocks.WATER.defaultBlockState());
        Materials.Result r = compute(scan(bp, new FakeWorld()), -1, -1, i -> 0, false);
        assertEquals(1, row(r, Items.OAK_DOOR).needed(), "one door, not two halves");
        assertEquals(4, row(r, Items.STONE_SLAB).needed(), "two double slabs are four slabs");
        assertEquals(2, r.rows().size(), "water is not an item");
        assertEquals(1 + 2, r.blocks());
    }

    @Test
    void blocksWithNoItemAreCountedApart() {
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState();
        Blueprint bp = blueprint(2, 1, 1, (c, k) -> c[0] == 0 ? portal : STONE);
        Materials.Result r = compute(scan(bp, new FakeWorld()), -1, -1, i -> 0, false);
        assertEquals(1, r.noItem());
        assertEquals(1, r.rows().size());
    }

    @Test
    void thePlacementCanBeTurnedWithoutChangingTheList() {
        Blueprint bp = house();
        List<OrientedRegion> regions = new ArrayList<>();
        for (Region r : bp.regions) regions.add(OrientedRegion.of(bp, r, Orientation.NONE.rotated(net.minecraft.world.level.block.Rotation.CLOCKWISE_90)));
        Verifier v = new Verifier(regions, 0, 0, 0, Matcher.LENIENT);
        for (int i = 0; i < 1000 && v.process(new FakeWorld(), 50_000_000L, 0, 0, 0); i++) {
            // until nothing is left
        }
        Materials.Result r = compute(v, -1, -1, item -> 0, false);
        assertEquals(16, row(r, Items.STONE).needed());
        assertEquals(4, row(r, Items.OAK_PLANKS).needed());
    }

    @Test
    void theShoppingListNamesWhatIsStillToGet() {
        Verifier v = scan(house(), new FakeWorld());
        Materials.Result r = compute(v, -1, -1, i -> i == Items.OAK_PLANKS ? 4 : 0, false);
        String text = Materials.shoppingList("Sample house", "layers 1-2", r.rows());
        assertTrue(text.startsWith("Shopping list: Sample house (layers 1-2)\n"), text);
        assertTrue(text.contains("- 16  "), text);
        assertTrue(text.contains("- 3  "), text);
        assertFalse(text.contains("- 4  "), "the oak planks you have are not listed: " + text);
        String done = Materials.shoppingList("x", "", List.of());
        assertTrue(done.contains("Nothing left to build."), done);
        Materials.Result enough = compute(v, -1, -1, i -> 99, false);
        assertTrue(Materials.shoppingList("x", "", enough.rows()).contains("You have everything you need."));
    }
}
