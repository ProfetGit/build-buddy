package io.github.profetgit.cyanotype.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
        /** Tags from the sidecar file next to it (name.cyanotype.json), if any. */
        public final List<String> tags;
        public volatile Info info;
        public volatile String error;
        /** The rotating preview, one picture per turn; null until made. */
        public volatile int[][] thumbs;
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
            return fileName.replaceFirst("(?i)\\.litematic$", "");
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
        List<Entry> old = new ArrayList<>(entries);
        entries.clear();
        for (Path p : BlueprintLibrary.files()) {
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

    static List<String> readTags(Path file) {
        Path side = file.resolveSibling(file.getFileName().toString().replaceFirst("(?i)\\.litematic$", "") + ".cyanotype.json");
        if (!Files.isRegularFile(side)) return List.of();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(side));
            List<String> out = new ArrayList<>();
            if (root.isJsonObject() && root.getAsJsonObject().has("tags")) {
                for (JsonElement t : root.getAsJsonObject().getAsJsonArray("tags")) out.add(t.getAsString());
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
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

    /** Starts reading an entry (once): its info and its preview, on a worker thread; the results land in the entry. */
    public void request(Entry e) {
        if (e.requested) return;
        e.requested = true;
        net.minecraft.util.Util.backgroundExecutor().execute(() -> {
            try {
                Blueprint bp = io.github.profetgit.cyanotype.blueprint.LitematicReader.read(e.file);
                e.info = Info.of(bp);
                e.thumbs = Thumbnail.frames(bp, THUMB_FRAMES, THUMB_W, THUMB_H);
            } catch (IOException | RuntimeException ex) {
                e.error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
        });
    }
}
