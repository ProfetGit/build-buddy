package io.github.profetgit.buildbuddy.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.buildbuddy.TestBootstrap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchematicReaderTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static int now() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static void varint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static byte[] varints(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) varint(out, v);
        return out.toByteArray();
    }

    private static CompoundTag palette(String... states) {
        CompoundTag p = new CompoundTag();
        for (int i = 0; i < states.length; i++) p.putInt(states[i], i);
        return p;
    }

    /** A 2 x 2 x 1 Sponge v3 schematic: planks, stairs, air, a chest. */
    private static CompoundTag v3() {
        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette("minecraft:air", "minecraft:oak_planks", "minecraft:oak_stairs[facing=east,half=top]", "minecraft:chest[facing=north]"));
        blocks.putByteArray("Data", varints(1, 2, 0, 3));
        ListTag be = new ListTag();
        CompoundTag chest = new CompoundTag();
        chest.putIntArray("Pos", new int[]{1, 0, 1});
        chest.putString("Id", "minecraft:chest");
        CompoundTag data = new CompoundTag();
        data.putString("CustomName", "Loot");
        chest.put("Data", data);
        be.add(chest);
        blocks.put("BlockEntities", be);
        CompoundTag schem = new CompoundTag();
        schem.putInt("Version", 3);
        schem.putInt("DataVersion", now());
        schem.putShort("Width", (short) 2);
        schem.putShort("Height", (short) 1);
        schem.putShort("Length", (short) 2);
        schem.put("Blocks", blocks);
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", "Little Shop");
        meta.putString("Author", "Someone");
        schem.put("Metadata", meta);
        CompoundTag root = new CompoundTag();
        root.put("Schematic", schem);
        return root;
    }

    @Test
    void aSpongeV3SchematicBecomesABlueprint() {
        Blueprint bp = SchematicReader.fromTag(v3(), "file name", false);
        assertEquals("Little Shop", bp.meta.name());
        assertEquals("Someone", bp.meta.author());
        assertEquals(2, bp.sizeX);
        assertEquals(1, bp.sizeY);
        assertEquals(2, bp.sizeZ);
        Region r = bp.regions.get(0);
        // x fastest, then z: (0,0,0) planks, (1,0,0) stairs, (0,0,1) air, (1,0,1) chest
        assertEquals(Blocks.OAK_PLANKS, r.at(0, 0, 0).state().getBlock());
        assertEquals(Blocks.OAK_STAIRS, r.at(1, 0, 0).state().getBlock());
        assertEquals("top", r.at(1, 0, 0).state().getValue(net.minecraft.world.level.block.StairBlock.HALF).getSerializedName());
        assertTrue(r.at(0, 0, 1).isAir());
        assertEquals(Blocks.CHEST, r.at(1, 0, 1).state().getBlock());
        assertEquals(3, bp.totalBlocks());
        assertEquals(1, r.blockEntities.size());
        CompoundTag e = r.blockEntities.get(0);
        assertEquals("minecraft:chest", e.getStringOr("id", ""));
        assertEquals(1, e.getIntOr("x", -1));
        assertEquals(1, e.getIntOr("z", -1));
        assertEquals("Loot", e.getStringOr("CustomName", ""));
    }

    @Test
    void aSpongeV2SchematicWithTheFieldsAtTheTopAndABigPaletteReadsToo() {
        // 200 different blocks, so block numbers past 127 take two bytes
        String[] states = new String[200];
        states[0] = "minecraft:air";
        for (int i = 1; i < 200; i++) states[i] = "minecraft:note_block[instrument=harp,note=" + (i % 25) + ",powered=" + (i >= 100) + "]";
        // note_block variants repeat when i % 25 and powered coincide: make them unique with another property
        for (int i = 1; i < 200; i++) states[i] = "minecraft:note_block[instrument=" + (i < 100 ? "harp" : "bass") + ",note=" + (i % 25) + ",powered=" + ((i / 25) % 2 == 1) + "]#" + i;
        CompoundTag p = new CompoundTag();
        for (int i = 0; i < states.length; i++) p.putInt(states[i], i);
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", now());
        root.putShort("Width", (short) 3);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        root.put("Palette", p);
        root.putByteArray("BlockData", varints(150, 0, 199));
        Blueprint bp = SchematicReader.fromTag(root, "plain", false);
        Region r = bp.regions.get(0);
        assertEquals("plain", bp.meta.name());
        assertTrue(r.at(1, 0, 0).isAir());
        assertEquals(150 & 0xFFFF, r.blocks[0] & 0xFFFF);
        assertEquals(199, r.blocks[2] & 0xFFFF);
        assertEquals(200, r.palette.length);
    }

    @Test
    void aDamagedSpongeFileSaysSo() {
        CompoundTag root = v3();
        CompoundTag blocks = root.getCompoundOrEmpty("Schematic").getCompoundOrEmpty("Blocks");
        blocks.putByteArray("Data", varints(1, 2, 0));
        assertTrue(assertThrows(LitematicException.class, () -> SchematicReader.fromTag(root, "x", false)).getMessage().contains("3 blocks but its size says 4"));
        blocks.putByteArray("Data", varints(1, 2, 0, 9));
        assertTrue(assertThrows(LitematicException.class, () -> SchematicReader.fromTag(root, "x", false)).getMessage().contains("past the end of its palette"));
        blocks.putByteArray("Data", new byte[]{1, 2, 0, (byte) 0x80});
        assertTrue(assertThrows(LitematicException.class, () -> SchematicReader.fromTag(root, "x", false)).getMessage().contains("damaged"));
        root.getCompoundOrEmpty("Schematic").putShort("Width", (short) 0);
        assertTrue(assertThrows(LitematicException.class, () -> SchematicReader.fromTag(root, "x", false)).getMessage().contains("empty"));
    }

    /** What the player's gold-block file holds: the old MCEdit layout, one block, id 41. */
    private static CompoundTag legacy(byte[] ids, byte[] data, int w, int h, int l) {
        CompoundTag s = new CompoundTag();
        s.putShort("Width", (short) w);
        s.putShort("Height", (short) h);
        s.putShort("Length", (short) l);
        s.putString("Materials", "Alpha");
        s.putByteArray("Blocks", ids);
        s.putByteArray("Data", data);
        s.put("Entities", new ListTag());
        s.put("TileEntities", new ListTag());
        CompoundTag root = new CompoundTag();
        root.put("Schematic", s);
        return root;
    }

    @Test
    void anOldMcEditSchematicMapsNumericIdsToTheBlocksOfToday() {
        // stone, gold block, air, red wool (id 35 data 14), grass block (id 2)
        Blueprint bp = SchematicReader.fromTag(legacy(new byte[]{1, 41, 0, 35, 2}, new byte[]{0, 0, 0, 14, 0}, 5, 1, 1), "old house", false);
        Region r = bp.regions.get(0);
        assertEquals("old house", bp.meta.name());
        assertEquals(Blocks.STONE, r.at(0, 0, 0).state().getBlock());
        assertEquals(Blocks.GOLD_BLOCK, r.at(1, 0, 0).state().getBlock());
        assertTrue(r.at(2, 0, 0).isAir());
        assertEquals(Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.RED), r.at(3, 0, 0).state().getBlock());
        assertEquals(Blocks.GRASS_BLOCK, r.at(4, 0, 0).state().getBlock());
        assertEquals(4, bp.totalBlocks());
    }

    @Test
    void theGoldBlockFileFromTheBugReport() {
        Blueprint bp = SchematicReader.fromTag(legacy(new byte[]{41}, new byte[]{0}, 1, 1, 1), "starter-house-1", false);
        assertEquals(1, bp.totalBlocks());
        assertEquals(Blocks.GOLD_BLOCK, bp.regions.get(0).at(0, 0, 0).state().getBlock());
    }

    @Test
    void idsAbove255UseTheAddBlocksNibbles() {
        // id 256 + 0 does not exist in vanilla, but the nibble must be read without failing; the block is unknown and kept as a placeholder
        CompoundTag root = legacy(new byte[]{1, 1}, new byte[]{0, 0}, 2, 1, 1);
        root.getCompoundOrEmpty("Schematic").putByteArray("AddBlocks", new byte[]{0x01});
        Blueprint bp = SchematicReader.fromTag(root, "x", false);
        Region r = bp.regions.get(0);
        // the first block is id 1 + (1 << 8) = 257 (unknown to the map: air), the second is plain stone
        assertEquals(Blocks.STONE, r.at(1, 0, 0).state().getBlock());
        assertTrue(r.at(0, 0, 0).state().getBlock() != Blocks.STONE);
    }

    @Test
    void oldBlockEntityNamesBecomeTheModernOnes() {
        CompoundTag root = legacy(new byte[]{54}, new byte[]{2}, 1, 1, 1);
        ListTag te = new ListTag();
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "Chest");
        chest.putInt("x", 0);
        chest.putInt("y", 0);
        chest.putInt("z", 0);
        te.add(chest);
        root.getCompoundOrEmpty("Schematic").put("TileEntities", te);
        Blueprint bp = SchematicReader.fromTag(root, "x", false);
        assertEquals(Blocks.CHEST, bp.regions.get(0).at(0, 0, 0).state().getBlock());
        assertEquals("minecraft:chest", bp.regions.get(0).blockEntities.get(0).getStringOr("id", ""));
    }

    @Test
    void aTruncatedOldSchematicSaysSo() {
        assertTrue(assertThrows(LitematicException.class, () -> SchematicReader.fromTag(legacy(new byte[]{1, 1}, new byte[]{0, 0}, 3, 1, 1), "x", false)).getMessage().contains("damaged"));
    }

    @Test
    void aFileIsReadByItsNameAndAnythingElseIsRefused(@TempDir Path dir) throws IOException {
        NbtIo.writeCompressed(v3(), dir.resolve("shop.schem"));
        NbtIo.writeCompressed(legacy(new byte[]{41}, new byte[]{0}, 1, 1, 1), dir.resolve("gold.schematic"));
        LitematicWriter.write(SchematicReader.fromTag(v3(), "x", false), dir.resolve("again.litematic"));
        assertEquals(3, SchematicReader.read(dir.resolve("shop.schem")).totalBlocks());
        assertEquals("gold", SchematicReader.read(dir.resolve("gold.schematic")).meta.name());
        assertEquals(3, SchematicReader.read(dir.resolve("again.litematic")).totalBlocks());
        java.nio.file.Files.writeString(dir.resolve("junk.schem"), "not nbt at all");
        assertThrows(LitematicException.class, () -> SchematicReader.read(dir.resolve("junk.schem")));
        CompoundTag unrelated = new CompoundTag();
        unrelated.putString("hello", "world");
        NbtIo.writeCompressed(unrelated, dir.resolve("other.schem"));
        assertTrue(assertThrows(LitematicException.class, () -> SchematicReader.read(dir.resolve("other.schem"))).getMessage().contains("not a schematic this mod knows"));
        assertTrue(SchematicReader.opens("A.SCHEM") && SchematicReader.opens("a.schematic") && SchematicReader.opens("a.litematic") && !SchematicReader.opens("a.nbt"));
    }
}
