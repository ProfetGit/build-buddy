package io.github.profetgit.cyanotype.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import io.github.profetgit.cyanotype.TestBootstrap;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LitematicTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static CompoundTag vec(int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        return t;
    }

    private static CompoundTag legacyEntry(String name, String... props) {
        CompoundTag e = new CompoundTag();
        e.putString("Name", name);
        if (props.length > 0) {
            CompoundTag p = new CompoundTag();
            for (int i = 0; i < props.length; i += 2) p.putString(props[i], props[i + 1]);
            e.put("Properties", p);
        }
        return e;
    }

    /** A litematic as an older Litematica wrote it: {Name, Properties} palette entries, hand-packed block data. */
    private static CompoundTag rawFile(int dataVersion, int px, int py, int pz, int rx, int ry, int rz, List<CompoundTag> palette, short[] blocks, List<CompoundTag> tiles) {
        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", dataVersion);
        root.putInt("Version", 6);
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", "raw");
        root.put("Metadata", meta);
        CompoundTag region = new CompoundTag();
        region.put("Position", vec(px, py, pz));
        region.put("Size", vec(rx, ry, rz));
        ListTag pal = new ListTag();
        palette.forEach(pal::add);
        region.put("BlockStatePalette", pal);
        region.putLongArray("BlockStates", BitPacking.pack(blocks, BitPacking.bitsFor(palette.size())));
        ListTag tes = new ListTag();
        tiles.forEach(tes::add);
        region.put("TileEntities", tes);
        CompoundTag regions = new CompoundTag();
        regions.put("main", region);
        root.put("Regions", regions);
        return root;
    }

    @Test
    void negativeSizeRunsFromPositionBackwards() {
        short[] blocks = new short[2 * 3 * 4];
        blocks[0] = 1;
        blocks[blocks.length - 1] = 1;
        CompoundTag root = rawFile(0, 10, 20, 30, -2, -3, -4, List.of(legacyEntry("minecraft:air"), legacyEntry("minecraft:stone")), blocks, List.of());
        Blueprint bp = LitematicReader.fromTag(root, "x");
        Region r = bp.regions.get(0);
        assertEquals(9, r.x);
        assertEquals(18, r.y);
        assertEquals(27, r.z);
        assertEquals(2, r.sx);
        assertEquals(3, r.sy);
        assertEquals(4, r.sz);
        assertTrue(r.at(0, 0, 0).state().is(Blocks.STONE));
        assertTrue(r.at(1, 2, 3).state().is(Blocks.STONE));
        assertTrue(r.at(1, 0, 0).isAir());
        assertEquals(2, r.nonAir());
    }

    @Test
    void indexRunsXThenZThenY() {
        short[] blocks = new short[3 * 2 * 4];
        // x=2, y=1, z=3 must be the last cell: (1*4 + 3)*3 + 2 = 23
        blocks[23] = 1;
        // x=1, y=0, z=2: (0*4 + 2)*3 + 1 = 7
        blocks[7] = 2;
        CompoundTag root = rawFile(0, 0, 0, 0, 3, 2, 4, List.of(legacyEntry("minecraft:air"), legacyEntry("minecraft:stone"), legacyEntry("minecraft:dirt")), blocks, List.of());
        Region r = LitematicReader.fromTag(root, "x").regions.get(0);
        assertTrue(r.at(2, 1, 3).state().is(Blocks.STONE));
        assertTrue(r.at(1, 0, 2).state().is(Blocks.DIRT));
        assertTrue(r.at(0, 0, 0).isAir());
    }

    @Test
    void olderGameVersionGoesThroughTheDataFixer() {
        // 2586 is 1.16.5: grass_path was renamed dirt_path in 1.17, and state entries had no id/properties spelling yet
        short[] blocks = {0, 1, 2};
        List<CompoundTag> palette = List.of(
            legacyEntry("minecraft:air"),
            legacyEntry("minecraft:grass_path"),
            legacyEntry("minecraft:oak_stairs", "facing", "north", "half", "top", "shape", "straight", "waterlogged", "false"));
        Blueprint bp = LitematicReader.fromTag(rawFile(2586, 0, 0, 0, 3, 1, 1, palette, blocks, List.of()), "x");
        Region r = bp.regions.get(0);
        assertTrue(r.at(1, 0, 0).state().is(Blocks.DIRT_PATH), "grass_path should have become dirt_path, got " + r.at(1, 0, 0).state());
        BlockState stairs = r.at(2, 0, 0).state();
        assertTrue(stairs.is(Blocks.OAK_STAIRS));
        assertEquals(Direction.NORTH, stairs.getValue(StairBlock.FACING));
        assertEquals(net.minecraft.world.level.block.state.properties.Half.TOP, stairs.getValue(StairBlock.HALF));
        assertTrue(bp.unknownBlocks().isEmpty());
    }

    @Test
    void blockEntitiesSurviveTheFixerWithTheirPosition() {
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 1);
        chest.putInt("y", 0);
        chest.putInt("z", 0);
        ListTag items = new ListTag();
        CompoundTag stack = new CompoundTag();
        stack.putByte("Slot", (byte) 0);
        stack.putString("id", "minecraft:stone");
        stack.putByte("Count", (byte) 3);
        items.add(stack);
        chest.put("Items", items);
        short[] blocks = {0, 1};
        Blueprint bp = LitematicReader.fromTag(
            rawFile(2586, 0, 0, 0, 2, 1, 1, List.of(legacyEntry("minecraft:air"), legacyEntry("minecraft:chest", "facing", "north", "type", "single", "waterlogged", "false")), blocks, List.of(chest)), "x");
        Region r = bp.regions.get(0);
        assertEquals(1, r.blockEntities.size());
        CompoundTag te = r.blockEntities.get(0);
        assertEquals(1, te.getIntOr("x", -1));
        assertEquals(0, te.getIntOr("y", -1));
        assertEquals(0, te.getIntOr("z", -1));
        assertFalse(te.getListOrEmpty("Items").isEmpty() && te.getCompoundOrEmpty("components").isEmpty(), "the chest's contents should still be there: " + te);
    }

    @Test
    void unknownBlocksBecomePlaceholdersAndWriteBackUntouched() throws IOException {
        short[] blocks = {0, 1, 2};
        List<CompoundTag> palette = List.of(legacyEntry("minecraft:air"), legacyEntry("othermod:gadget", "mode", "fast"), legacyEntry("minecraft:stone"));
        Blueprint bp = LitematicReader.fromTag(rawFile(0, 0, 0, 0, 3, 1, 1, palette, blocks, List.of()), "x");
        Region r = bp.regions.get(0);
        assertTrue(r.at(1, 0, 0).unknown());
        assertTrue(r.at(1, 0, 0).state().is(Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED)));
        assertFalse(r.at(2, 0, 0).unknown());
        assertEquals(java.util.Set.of("othermod:gadget"), bp.unknownBlocks());
        assertEquals(2, r.nonAir());

        Blueprint again = roundTrip(bp);
        Region r2 = again.regions.get(0);
        assertTrue(r2.at(1, 0, 0).unknown());
        assertEquals("othermod:gadget", r2.at(1, 0, 0).name());
        assertEquals("fast", r2.at(1, 0, 0).source().getCompoundOrEmpty("Properties").getStringOr("mode", r2.at(1, 0, 0).source().getCompoundOrEmpty("properties").getStringOr("mode", "?")));
    }

    private static Blueprint roundTrip(Blueprint bp) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LitematicWriter.write(bp, out);
        return LitematicReader.read(new ByteArrayInputStream(out.toByteArray()), "fallback");
    }

    @Test
    void randomBlueprintsRoundTrip() throws IOException {
        List<Block> pool = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) pool.add(b);
        Random rnd = new Random(99);
        int[] distinct = {1, 2, 3, 17, 65, 300};
        for (int d : distinct) {
            int sx = 7, sy = 5, sz = 9;
            List<BlockState> states = new ArrayList<>();
            for (int i = 0; i < d; i++) {
                Block b = pool.get(rnd.nextInt(pool.size()));
                List<BlockState> all = b.getStateDefinition().getPossibleStates();
                states.add(all.get(rnd.nextInt(all.size())));
            }
            List<PaletteEntry> palette = new ArrayList<>();
            for (BlockState s : states) {
                PaletteEntry e = PaletteEntry.of(s);
                if (palette.stream().noneMatch(p -> p.state() == s)) palette.add(e);
            }
            short[] blocks = new short[sx * sy * sz];
            for (int i = 0; i < blocks.length; i++) blocks[i] = (short) rnd.nextInt(palette.size());
            Region region = new Region("r", -3, 64, 12, sx, sy, sz, palette.toArray(new PaletteEntry[0]), blocks, new ArrayList<>());
            Region second = new Region("second", 20, 0, 0, 2, 2, 2, new PaletteEntry[]{PaletteEntry.AIR, PaletteEntry.of(Blocks.GOLD_BLOCK.defaultBlockState())}, new short[]{1, 0, 0, 1, 1, 0, 1, 1}, new ArrayList<>());
            Blueprint bp = new Blueprint(new Blueprint.Metadata("Round trip " + d, "Profet", "a note", 111L, 222L, 0), List.of(region, second));

            Blueprint back = roundTrip(bp);
            assertEquals("Round trip " + d, back.meta.name());
            assertEquals("Profet", back.meta.author());
            assertEquals("a note", back.meta.description());
            assertEquals(2, back.regions.size());
            assertEquals(bp.totalBlocks(), back.totalBlocks(), "block count, palette " + palette.size());
            assertEquals(bp.sizeX, back.sizeX);
            assertEquals(bp.sizeY, back.sizeY);
            assertEquals(bp.sizeZ, back.sizeZ);
            for (Region want : bp.regions) {
                Region got = back.regions.stream().filter(r -> r.name.equals(want.name)).findFirst().orElseThrow();
                assertEquals(want.x, got.x);
                assertEquals(want.y, got.y);
                assertEquals(want.z, got.z);
                for (int y = 0; y < want.sy; y++) {
                    for (int z = 0; z < want.sz; z++) {
                        for (int x = 0; x < want.sx; x++) {
                            assertTrue(want.at(x, y, z).state() == got.at(x, y, z).state(), "block at " + x + "," + y + "," + z + " of " + want.name + ", palette " + palette.size() + ": " + want.at(x, y, z).state() + " vs " + got.at(x, y, z).state());
                        }
                    }
                }
            }
        }
    }

    @Test
    void writerPutsAirFirst() {
        Region r = new Region("r", 0, 0, 0, 2, 1, 1, new PaletteEntry[]{PaletteEntry.of(Blocks.STONE.defaultBlockState()), PaletteEntry.AIR}, new short[]{0, 1}, new ArrayList<>());
        CompoundTag tag = LitematicWriter.toTag(new Blueprint(Blueprint.Metadata.of("a"), List.of(r)));
        ListTag palette = tag.getCompoundOrEmpty("Regions").getCompoundOrEmpty("r").getListOrEmpty("BlockStatePalette");
        CompoundTag first = palette.getCompoundOrEmpty(0);
        assertEquals("minecraft:air", first.getString("id").orElseGet(() -> first.getStringOr("Name", "")));
        assertEquals(2, palette.size());
    }

    @Test
    void damagedFilesGetAPlayerMessage() throws IOException {
        short[] blocks = new short[8];
        CompoundTag root = rawFile(0, 0, 0, 0, 2, 2, 2, List.of(legacyEntry("minecraft:air"), legacyEntry("minecraft:stone")), blocks, List.of());
        CompoundTag region = root.getCompoundOrEmpty("Regions").getCompoundOrEmpty("main");
        region.putLongArray("BlockStates", new long[0]);
        LitematicException e = assertThrows(LitematicException.class, () -> LitematicReader.fromTag(root, "x"));
        assertTrue(e.getMessage().contains("damaged"), e.getMessage());

        CompoundTag bad = rawFile(0, 0, 0, 0, 2, 1, 1, List.of(legacyEntry("minecraft:air"), legacyEntry("minecraft:stone")), new short[]{0, 1}, List.of());
        // palette of 2 entries is 2 bits wide, so an index of 3 fits the bits but not the palette
        bad.getCompoundOrEmpty("Regions").getCompoundOrEmpty("main").putLongArray("BlockStates", BitPacking.pack(new short[]{0, 3}, 2));
        assertTrue(assertThrows(LitematicException.class, () -> LitematicReader.fromTag(bad, "x")).getMessage().contains("palette"));

        assertThrows(LitematicException.class, () -> LitematicReader.read(new ByteArrayInputStream("not nbt at all".getBytes()), "x"));
        CompoundTag empty = new CompoundTag();
        assertThrows(LitematicException.class, () -> LitematicReader.fromTag(empty, "x"));
        CompoundTag noRegions = new CompoundTag();
        noRegions.putInt("Version", 6);
        assertNotNull(assertThrows(LitematicException.class, () -> LitematicReader.fromTag(noRegions, "x")).getMessage());
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        NbtIo.writeCompressed(empty, gz);
        assertThrows(LitematicException.class, () -> LitematicReader.read(new ByteArrayInputStream(gz.toByteArray()), "x"));
    }
}
