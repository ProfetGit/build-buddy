package io.github.profetgit.cyanotype.blueprint;

import io.github.profetgit.cyanotype.Cyanotype;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.fixes.BlockEntityIdFix;
import net.minecraft.util.datafix.fixes.BlockStateData;

/**
 * Reads a schematic file of any kind this mod opens, by its name and then by what is inside: Litematica's {@code .litematic}
 * ({@link LitematicReader}), the Sponge schematic that WorldEdit, FAWE and most websites make ({@code .schem}, versions 1 to 3)
 * and the old MCEdit {@code .schematic} (numeric block ids, from before 1.13). Blocks of an older game go through the game's own
 * data fixer, so renamed blocks update; blocks the game does not know become placeholders. All of them become the same
 * {@link Blueprint}, so nothing after this cares where it came from. A file that cannot be read throws
 * {@link LitematicException} with a message meant for the player.
 */
public final class SchematicReader {
    /** The data version a pre-flattening schematic is treated as: the block names {@link BlockStateData} gives are those of Minecraft 1.13. */
    static final int LEGACY_AS_VERSION = 1519;
    /** The data version assumed for a Sponge file that does not say. */
    private static final int SPONGE_DEFAULT_VERSION = 1519;

    private SchematicReader() {
    }

    /** Whether a file name is one this reader opens. */
    public static boolean opens(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".schematic");
    }

    public static Blueprint read(Path file) throws IOException {
        return read(file, true);
    }

    /** @param fixShapes whether blocks that read their neighbours get the shape they have in place; false is enough to judge a file */
    public static Blueprint read(Path file, boolean fixShapes) throws IOException {
        String name = file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                return LitematicReader.read(in, name.substring(0, name.length() - ".litematic".length()), fixShapes);
            }
        }
        String stem = name.replaceFirst("(?i)\\.(schem|schematic)$", "");
        CompoundTag root;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new LitematicException("This is not a schematic file (" + e.getMessage() + ")", e);
        }
        return fromTag(root, stem, fixShapes);
    }

    /** Reads an already parsed schematic of the Sponge or MCEdit kind (the root may hold everything, or one "Schematic" tag that does). */
    public static Blueprint fromTag(CompoundTag root, String fallbackName, boolean fixShapes) {
        CompoundTag s = root.getCompound("Schematic").orElse(root);
        Blueprint bp;
        if (s.getCompound("Blocks").isPresent()) bp = sponge3(s, fallbackName);
        else if (s.getByteArray("BlockData").isPresent() && s.getCompound("Palette").isPresent()) bp = sponge12(s, fallbackName);
        else if (s.getByteArray("Blocks").isPresent()) bp = mcedit(s, fallbackName);
        else throw new LitematicException("This file is not a schematic this mod knows (it is not Sponge .schem, MCEdit .schematic or .litematic).");
        return fixShapes ? ShapeFixer.fix(bp) : bp;
    }

    // ---- Sponge .schem

    private static Blueprint sponge3(CompoundTag s, String fallbackName) {
        CompoundTag blocks = s.getCompoundOrEmpty("Blocks");
        List<CompoundTag> entities = new ArrayList<>();
        ListTag be = blocks.getListOrEmpty("BlockEntities");
        for (int i = 0; i < be.size(); i++) entities.add(spongeEntity(be.getCompoundOrEmpty(i), true));
        return sponge(s, fallbackName, blocks.getCompoundOrEmpty("Palette"), blocks.getByteArray("Data").orElseThrow(() -> new LitematicException("This .schem has no block data.")), entities);
    }

    private static Blueprint sponge12(CompoundTag s, String fallbackName) {
        List<CompoundTag> entities = new ArrayList<>();
        ListTag be = s.contains("BlockEntities") ? s.getListOrEmpty("BlockEntities") : s.getListOrEmpty("TileEntities");
        for (int i = 0; i < be.size(); i++) entities.add(spongeEntity(be.getCompoundOrEmpty(i), false));
        return sponge(s, fallbackName, s.getCompoundOrEmpty("Palette"), s.getByteArray("BlockData").orElseThrow(), entities);
    }

    /** One block entity as the litematic spelling the rest of the mod uses: the data, plus id and local x, y, z. */
    private static CompoundTag spongeEntity(CompoundTag e, boolean nested) {
        CompoundTag out = nested && e.getCompound("Data").isPresent() ? e.getCompoundOrEmpty("Data").copy() : e.copy();
        out.remove("Pos");
        out.remove("Id");
        out.remove("Data");
        int[] pos = e.getIntArray("Pos").orElse(new int[]{-1, -1, -1});
        out.putString("id", e.getStringOr("Id", ""));
        out.putInt("x", pos.length > 0 ? pos[0] : -1);
        out.putInt("y", pos.length > 1 ? pos[1] : -1);
        out.putInt("z", pos.length > 2 ? pos[2] : -1);
        return out;
    }

    private static Blueprint sponge(CompoundTag s, String fallbackName, CompoundTag paletteTag, byte[] data, List<CompoundTag> entities) {
        int version = s.getIntOr("Version", 0);
        if (version > 3) Cyanotype.LOG.warn("Sponge schematic version {} is newer than the newest this mod knows (3); reading it anyway", version);
        int sx = s.getIntOr("Width", 0) & 0xFFFF, sy = s.getIntOr("Height", 0) & 0xFFFF, sz = s.getIntOr("Length", 0) & 0xFFFF;
        long volume = (long) sx * sy * sz;
        if (volume <= 0) throw new LitematicException("This .schem is empty (its size is " + sx + " x " + sy + " x " + sz + ").");
        if (volume > Integer.MAX_VALUE - 8) throw new LitematicException("This .schem is too big to load (" + sx + " x " + sy + " x " + sz + ").");
        // the palette maps a block state string to its number; the numbers need not be in order
        int max = -1;
        for (String key : paletteTag.keySet()) max = Math.max(max, paletteTag.getIntOr(key, -1));
        if (max < 0) throw new LitematicException("This .schem has no block palette.");
        if (max >= 65536) throw new LitematicException("This .schem has " + (max + 1) + " different blocks; the most this mod reads is 65536.");
        CompoundTag[] slots = new CompoundTag[max + 1];
        for (String key : paletteTag.keySet()) {
            int i = paletteTag.getIntOr(key, -1);
            if (i >= 0) slots[i] = stateTag(key);
        }
        ListTag palette = new ListTag();
        for (CompoundTag t : slots) palette.add(t != null ? t : stateTag("minecraft:air"));
        short[] blocks = new short[(int) volume];
        int at = 0, pos = 0;
        while (pos < data.length) {
            int value = 0, shift = 0;
            byte b;
            do {
                if (pos >= data.length) throw new LitematicException("This .schem is damaged: its block data ends in the middle of a number.");
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new LitematicException("This .schem is damaged: a block number is too long.");
            } while ((b & 0x80) != 0);
            if (at >= blocks.length) throw new LitematicException("This .schem is damaged: it has more blocks than its size says.");
            if (value > max) throw new LitematicException("This .schem is damaged: a block points past the end of its palette.");
            blocks[at++] = (short) value;
        }
        if (at != blocks.length) throw new LitematicException("This .schem is damaged: it has " + at + " blocks but its size says " + blocks.length + ".");
        int fileData = s.getIntOr("DataVersion", SPONGE_DEFAULT_VERSION);
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (fileData > current) Cyanotype.LOG.warn("Schematic was saved by a newer game (data version {} > {}); blocks added since may show as unknown", fileData, current);
        CompoundTag meta = s.getCompoundOrEmpty("Metadata");
        long date = meta.getLongOr("Date", 0L);
        Blueprint.Metadata metadata = new Blueprint.Metadata(meta.getStringOr("Name", fallbackName), meta.getStringOr("Author", ""), "", date, date, fileData);
        Region region = LitematicReader.finishRegion("Main", 0, 0, 0, sx, sy, sz, palette, blocks, entities, fileData, current);
        return new Blueprint(metadata, List.of(region));
    }

    /** "minecraft:oak_stairs[facing=east,half=bottom]" as a palette entry {Name, Properties}. */
    static CompoundTag stateTag(String state) {
        CompoundTag tag = new CompoundTag();
        int bracket = state.indexOf('[');
        String name = (bracket < 0 ? state : state.substring(0, bracket)).trim();
        tag.putString("Name", name);
        if (bracket >= 0) {
            int end = state.lastIndexOf(']');
            String props = state.substring(bracket + 1, end > bracket ? end : state.length());
            CompoundTag p = new CompoundTag();
            for (String pair : props.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) p.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
            if (!p.isEmpty()) tag.put("Properties", p);
        }
        return tag;
    }

    // ---- MCEdit .schematic

    private static Blueprint mcedit(CompoundTag s, String fallbackName) {
        int sx = s.getIntOr("Width", 0) & 0xFFFF, sy = s.getIntOr("Height", 0) & 0xFFFF, sz = s.getIntOr("Length", 0) & 0xFFFF;
        long volume = (long) sx * sy * sz;
        if (volume <= 0) throw new LitematicException("This .schematic is empty (its size is " + sx + " x " + sy + " x " + sz + ").");
        if (volume > Integer.MAX_VALUE - 8) throw new LitematicException("This .schematic is too big to load (" + sx + " x " + sy + " x " + sz + ").");
        byte[] ids = s.getByteArray("Blocks").orElseThrow(), meta = s.getByteArray("Data").orElse(new byte[ids.length]);
        byte[] add = s.getByteArray("AddBlocks").orElse(null);
        if (ids.length < volume || meta.length < volume) throw new LitematicException("This .schematic is damaged: it has " + Math.min(ids.length, meta.length) + " blocks but its size says " + volume + ".");
        short[] blocks = new short[(int) volume];
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap slotOf = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        slotOf.defaultReturnValue(-1);
        ListTag palette = new ListTag();
        for (int i = 0; i < blocks.length; i++) {
            int id = ids[i] & 0xFF;
            // ids above 255 keep their high four bits in a second array, two blocks to a byte
            if (add != null && (i >> 1) < add.length) id |= ((i & 1) == 0 ? add[i >> 1] & 0x0F : (add[i >> 1] & 0xF0) >> 4) << 8;
            int key = id << 4 | (meta[i] & 0x0F);
            int slot = slotOf.get(key);
            if (slot < 0) {
                slot = palette.size();
                slotOf.put(key, slot);
                palette.add(legacyState(key));
            }
            blocks[i] = (short) slot;
        }
        List<CompoundTag> entities = new ArrayList<>();
        ListTag te = s.getListOrEmpty("TileEntities");
        for (int i = 0; i < te.size(); i++) {
            CompoundTag e = te.getCompoundOrEmpty(i).copy();
            String old = e.getStringOr("id", "");
            e.putString("id", BlockEntityIdFix.ID_MAP.getOrDefault(old, old));
            entities.add(e);
        }
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        long now = 0;
        Blueprint.Metadata metadata = new Blueprint.Metadata(fallbackName, "", "", now, now, LEGACY_AS_VERSION);
        Region region = LitematicReader.finishRegion("Main", 0, 0, 0, sx, sy, sz, palette, blocks, entities, LEGACY_AS_VERSION, current);
        return new Blueprint(metadata, List.of(region));
    }

    /** The block a pre-1.13 id and data value stand for, with the names and properties of 1.13 (the data fixer then brings them up to date). */
    private static CompoundTag legacyState(int idAndData) {
        Tag tag = BlockStateData.getTag(idAndData).convert(NbtOps.INSTANCE).getValue();
        return tag instanceof CompoundTag c ? c.copy() : stateTag("minecraft:air");
    }
}
