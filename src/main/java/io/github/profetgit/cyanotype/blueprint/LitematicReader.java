package io.github.profetgit.cyanotype.blueprint;

import io.github.profetgit.cyanotype.Cyanotype;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;

/**
 * Reads Litematica's .litematic (gzip NBT, versions 1 to 7). Palette entries and block entities of an older game
 * version go through the game's own data fixer, so renamed blocks update instead of failing; blocks the game does not
 * know become placeholders (see {@link PaletteEntry}). A file that cannot be read throws {@link LitematicException}
 * with a message meant for the player.
 */
public final class LitematicReader {
    public static final int NEWEST_KNOWN_VERSION = 7;

    private LitematicReader() {
    }

    public static Blueprint read(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.toLowerCase().endsWith(".litematic")) name = name.substring(0, name.length() - ".litematic".length());
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return read(in, name);
        }
    }

    public static Blueprint read(InputStream in, String fallbackName) throws IOException {
        return read(in, fallbackName, true);
    }

    /** @param fixShapes whether blocks that read their neighbours get the shape they have in place (see {@link ShapeFixer}); false keeps the file's states exactly */
    public static Blueprint read(InputStream in, String fallbackName, boolean fixShapes) throws IOException {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new LitematicException("This is not a .litematic file (" + e.getMessage() + ")", e);
        }
        return fromTag(root, fallbackName, fixShapes);
    }

    public static Blueprint fromTag(CompoundTag root, String fallbackName) {
        return fromTag(root, fallbackName, true);
    }

    public static Blueprint fromTag(CompoundTag root, String fallbackName, boolean fixShapes) {
        int version = root.getIntOr("Version", 0);
        if (version < 1) throw new LitematicException("This file has no schematic version, so it is not a .litematic.");
        if (version > NEWEST_KNOWN_VERSION) {
            Cyanotype.LOG.warn("Litematic version {} is newer than the newest this mod knows ({}); reading it anyway", version, NEWEST_KNOWN_VERSION);
        }
        int fileData = root.getIntOr("MinecraftDataVersion", 0);
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (fileData > current) {
            Cyanotype.LOG.warn("Litematic was saved by a newer game (data version {} > {}); blocks added since may show as unknown", fileData, current);
        }
        CompoundTag metaTag = root.getCompoundOrEmpty("Metadata");
        Blueprint.Metadata meta = new Blueprint.Metadata(
            metaTag.getStringOr("Name", fallbackName),
            metaTag.getStringOr("Author", ""),
            metaTag.getStringOr("Description", ""),
            metaTag.getLongOr("TimeCreated", 0L),
            metaTag.getLongOr("TimeModified", 0L),
            fileData);

        CompoundTag regionsTag = root.getCompoundOrEmpty("Regions");
        if (regionsTag.isEmpty()) throw new LitematicException("This .litematic has no regions, so there is nothing to build.");
        List<Region> regions = new ArrayList<>();
        for (String regionName : regionsTag.keySet()) {
            regions.add(readRegion(regionName, regionsTag.getCompoundOrEmpty(regionName), fileData, current));
        }
        // blocks that read their neighbours (panes, fences, walls, stairs) get the shape they have where the blueprint puts them
        Blueprint bp = new Blueprint(meta, regions);
        return fixShapes ? ShapeFixer.fix(bp) : bp;
    }

    private static Region readRegion(String name, CompoundTag tag, int fileData, int current) {
        CompoundTag pos = tag.getCompoundOrEmpty("Position");
        CompoundTag size = tag.getCompoundOrEmpty("Size");
        int px = pos.getIntOr("x", 0), py = pos.getIntOr("y", 0), pz = pos.getIntOr("z", 0);
        int rx = size.getIntOr("x", 0), ry = size.getIntOr("y", 0), rz = size.getIntOr("z", 0);
        // a negative size runs the other way from Position; blocks are stored from the min corner either way
        int sx = Math.abs(rx), sy = Math.abs(ry), sz = Math.abs(rz);
        int x = rx < 0 ? px + rx + 1 : px;
        int y = ry < 0 ? py + ry + 1 : py;
        int z = rz < 0 ? pz + rz + 1 : pz;
        long volume = (long) sx * sy * sz;
        if (volume > Integer.MAX_VALUE - 8) throw new LitematicException("Region \"" + name + "\" is too big to load (" + sx + " x " + sy + " x " + sz + ").");

        ListTag paletteTag = tag.getListOrEmpty("BlockStatePalette");
        if (paletteTag.isEmpty()) throw new LitematicException("Region \"" + name + "\" has no block palette.");
        if (paletteTag.size() > 65536) throw new LitematicException("Region \"" + name + "\" has " + paletteTag.size() + " different blocks; the most this mod reads is 65536.");
        long[] packed = tag.getLongArray("BlockStates").orElseThrow(() -> new LitematicException("Region \"" + name + "\" has no block data."));
        int bits = BitPacking.bitsFor(paletteTag.size());
        short[] blocks;
        try {
            blocks = BitPacking.unpack(packed, bits, (int) volume);
        } catch (IllegalArgumentException e) {
            throw new LitematicException("Region \"" + name + "\" is damaged: " + e.getMessage(), e);
        }
        for (short b : blocks) {
            if ((b & 0xFFFF) >= paletteTag.size()) throw new LitematicException("Region \"" + name + "\" is damaged: a block points past the end of its palette.");
        }

        ListTag teTag = tag.getListOrEmpty("TileEntities");
        ListTag fixedPalette = paletteTag;
        List<CompoundTag> blockEntities = new ArrayList<>(teTag.size());
        for (int i = 0; i < teTag.size(); i++) blockEntities.add(teTag.getCompoundOrEmpty(i).copy());

        if (fileData > 0 && fileData < current) {
            CompoundTag structure = new CompoundTag();
            ListTag sizeTag = new ListTag();
            sizeTag.add(IntTag.valueOf(sx));
            sizeTag.add(IntTag.valueOf(sy));
            sizeTag.add(IntTag.valueOf(sz));
            structure.put("size", sizeTag);
            structure.put("palette", paletteTag.copy());
            structure.put("entities", new ListTag());
            ListTag blockList = new ListTag();
            for (CompoundTag te : blockEntities) {
                int lx = te.getIntOr("x", -1), ly = te.getIntOr("y", -1), lz = te.getIntOr("z", -1);
                if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) continue;
                CompoundTag entry = new CompoundTag();
                ListTag p = new ListTag();
                p.add(IntTag.valueOf(lx));
                p.add(IntTag.valueOf(ly));
                p.add(IntTag.valueOf(lz));
                entry.put("pos", p);
                entry.putInt("state", blocks[(ly * sz + lz) * sx + lx] & 0xFFFF);
                CompoundTag nbt = te.copy();
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                entry.put("nbt", nbt);
                blockList.add(entry);
            }
            structure.put("blocks", blockList);
            CompoundTag fixed = DataFixTypes.STRUCTURE.update(DataFixers.getDataFixer(), structure, fileData, current);
            fixedPalette = fixed.getListOrEmpty("palette");
            if (fixedPalette.size() != paletteTag.size()) {
                throw new LitematicException("Region \"" + name + "\": the data fixer changed the palette size (" + paletteTag.size() + " to " + fixedPalette.size() + ").");
            }
            blockEntities = new ArrayList<>();
            ListTag fixedBlocks = fixed.getListOrEmpty("blocks");
            for (int i = 0; i < fixedBlocks.size(); i++) {
                CompoundTag b = fixedBlocks.getCompoundOrEmpty(i);
                ListTag p = b.getListOrEmpty("pos");
                CompoundTag nbt = b.getCompoundOrEmpty("nbt").copy();
                nbt.putInt("x", p.getIntOr(0, 0));
                nbt.putInt("y", p.getIntOr(1, 0));
                nbt.putInt("z", p.getIntOr(2, 0));
                blockEntities.add(nbt);
            }
        }

        PaletteEntry[] palette = new PaletteEntry[fixedPalette.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = PaletteEntry.read(fixedPalette.getCompoundOrEmpty(i));
        return new Region(name, x, y, z, sx, sy, sz, palette, blocks, blockEntities);
    }
}
