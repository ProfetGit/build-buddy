package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.placement.BlueprintLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the Library screen shows: every .litematic in the blueprint folders, what is known about each (read on a worker
 * thread, a few at a time), and the search and sort over them. No drawing here.
 */
public final class LibraryModel {
    public enum Sort {
        RECENT, NAME, SIZE
    }

    /** What reading a file taught us. */
    public record Info(String name, String author, String description, int sx, int sy, int sz, long blocks, int regions, int unknown) {
        public static Info of(Blueprint bp) {
            int unknown = 0;
            for (Region r : bp.regions) for (PaletteEntry e : r.palette) if (e.unknown()) unknown++;
            return new Info(bp.meta.name(), bp.meta.author(), bp.meta.description(), bp.sizeX, bp.sizeY, bp.sizeZ, bp.totalBlocks(), bp.regions.size(), unknown);
        }
    }

    public static final class Entry {
        public final Path file;
        public final String fileName;
        public final long modified, bytes;
        /** Tags from the library index, if any. */
        public final List<String> tags;
        public volatile Info info;
        public volatile String error;
        /** The rotating preview, one picture per turn; null until made. */
        public volatile int[][] thumbs;
        /** The size of each picture in {@link #thumbs} in pixels, and the GUI scale it was made for (it is made again when that changes). */
        public volatile int thumbW = THUMB_W, thumbH = THUMB_H, thumbRes;
        volatile boolean requested;

        Entry(Path file, long modified, long bytes, List<String> tags) {
            this.file = file;
            String n = file.getFileName().toString();
            this.fileName = n;
            this.modified = modified;
            this.bytes = bytes;
            this.tags = tags;
        }

        /** The name to show: the blueprint's own when it has one, else the file's. */
        public String title() {
            Info i = info;
            if (i != null && i.name() != null && !i.name().isBlank()) return i.name();
            return fileName.replaceFirst("(?i)\\.(litematic|schematic|schem)$", "");
        }

        /** How a saved placement names this file (needs the game folders, so it is worked out when asked). */
        public String ref() {
            return BlueprintLibrary.refOf(file);
        }

        public boolean loaded() {
            return info != null;
        }
    }

    /** The preview is made at twice the size it is shown at (GUI units), and sampled smoothly. */
    public static final int THUMB_W = 128, THUMB_H = 96, THUMB_SHOW_W = 64, THUMB_SHOW_H = 48, THUMB_FRAMES = 8;

    /** The GUI scale the pictures are made for: one pixel of the picture for each pixel of the screen. */
    static int res() {
        return (int) Math.max(1, Math.round(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
    }

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> all() {
        return entries;
    }

    /** How many entries have been read (or failed): changes as the worker threads finish, so a sorted view can be redone. */
    public int known() {
        int n = 0;
        for (Entry e : entries) if (e.info != null || e.error != null) n++;
        return n;
    }

    /** Looks at the folders again; entries of files that are still there keep what was learned about them. */
    public void rescan() {
        // tags files left beside schematics by older versions move into the index; entries of files that are gone leave it
        for (Path dir : List.of(BlueprintLibrary.ownDir(), BlueprintLibrary.litematicaDir())) io.github.profetgit.cyanotype.placement.LibraryIndex.importSidecars(dir);
        io.github.profetgit.cyanotype.placement.LibraryIndex.prune();
        List<Entry> old = new ArrayList<>(entries);
        entries.clear();
        List<Path> listed = new ArrayList<>(BlueprintLibrary.files());
        listed.addAll(BlueprintLibrary.foreignFiles());
        lastSignature = signature(listed);
        for (Path p : listed) {
            long modified = 0, bytes = 0;
            try {
                modified = Files.getLastModifiedTime(p).toMillis();
                bytes = Files.size(p);
            } catch (IOException ignored) {
                // listed anyway; reading it will say what is wrong
            }
            Entry reuse = null;
            for (Entry e : old) if (e.file.equals(p) && e.modified == modified && e.bytes == bytes) reuse = e;
            entries.add(reuse != null ? reuse : new Entry(p, modified, bytes, readTags(p)));
        }
    }

    private String lastSignature = "";

    /** What the folders hold, as one string: every file with its size and time, so a change of any kind shows. */
    private static String signature(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        for (Path p : files) {
            try {
                sb.append(p).append('|').append(Files.size(p)).append('|').append(Files.getLastModifiedTime(p).toMillis()).append('\n');
            } catch (IOException e) {
                sb.append(p).append("|?\n");
            }
        }
        return sb.toString();
    }

    /** Whether a file was added, removed or changed in the folders since the last look. Cheap: a listing and a stat per file. */
    public boolean changedOnDisk() {
        List<Path> listed = new ArrayList<>(BlueprintLibrary.files());
        listed.addAll(BlueprintLibrary.foreignFiles());
        return !signature(listed).equals(lastSignature);
    }

    static List<String> readTags(Path file) {
        return io.github.profetgit.cyanotype.placement.LibraryIndex.tags(file);
    }

    /** Whether an entry matches what was typed in the search box: every word must be in its name, file name, author or tags. */
    public static boolean matches(Entry e, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        Info i = e.info;
        String hay = (e.fileName + " " + e.title() + " " + (i == null ? "" : i.author() + " " + i.description()) + " " + String.join(" ", e.tags)).toLowerCase(Locale.ROOT);
        for (String word : q.split("\\s+")) if (!hay.contains(word)) return false;
        return true;
    }

    public List<Entry> view(String query, Sort sort) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (matches(e, query)) out.add(e);
        Comparator<Entry> by = switch (sort) {
            case RECENT -> Comparator.comparingLong((Entry e) -> e.modified).reversed();
            case NAME -> Comparator.comparing((Entry e) -> e.title().toLowerCase(Locale.ROOT));
            // largest first; files not read yet go last
            case SIZE -> Comparator.comparingLong((Entry e) -> e.info == null ? -1 : e.info.blocks()).reversed();
        };
        out.sort(by.thenComparing(e -> e.fileName.toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Makes the entry's pictures again when the GUI scale is no longer the one they were made for (the old ones show meanwhile). */
    public void refreshScale(Entry e) {
        if (e.thumbs != null && e.thumbRes != res() && e.info != null) {
            e.requested = false;
            request(e);
        }
    }

    /** Starts reading an entry (once): its info and its preview, on a worker thread; the results land in the entry. */
    public void request(Entry e) {
        if (e.requested) return;
        e.requested = true;
        int res = res();
        String lower = e.fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".litematic")) {
            e.error = "This version opens .litematic files only; " + (lower.endsWith(".schematic") ? ".schematic is the old MCEdit format" : ".schem is the Sponge/WorldEdit format") + ". It is not supported yet";
            return;
        }
        net.minecraft.util.Util.backgroundExecutor().execute(() -> {
            try {
                Blueprint bp = io.github.profetgit.cyanotype.blueprint.LitematicReader.read(e.file);
                e.info = Info.of(bp);
                int w = THUMB_SHOW_W * res, h = THUMB_SHOW_H * res;
                int[][] frames = Thumbnail.texturedFrames(bp, THUMB_FRAMES, w, h, res >= 3 ? 2 : 3);
                e.thumbW = w;
                e.thumbH = h;
                e.thumbRes = res;
                e.thumbs = frames;
            } catch (IOException | RuntimeException ex) {
                e.error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
        });
    }
}
