package io.github.profetgit.cyanotype.placement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.profetgit.cyanotype.Cyanotype;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * What the Library knows about a file besides what is inside it: its tags and, for a build fetched from the community site,
 * where it came from. Kept in one file in the config folder ({@code config/cyanotype/library.json}), so the schematics folder
 * holds only schematics and a build file is never touched. An entry belongs to a file by its reference
 * ({@code schematics:house.litematic}); entries of files that are gone are dropped when the Library looks at its folders.
 * Older versions wrote a {@code name.cyanotype.json} beside each file: {@link #importSidecars} moves those in and deletes them.
 */
public final class LibraryIndex {
    /** What is known about one file. */
    public record Extras(List<String> tags, @Nullable JsonObject source) {
    }

    private static final Object LOCK = new Object();
    private static @Nullable Path override;
    private static Map<String, Extras> cache;
    private static long cachedModified = Long.MIN_VALUE;

    private LibraryIndex() {
    }

    /** Tests: keeps the index in this file and keys by absolute path (no game folders there); null goes back to the config folder. */
    public static void useFile(@Nullable Path file) {
        synchronized (LOCK) {
            override = file;
            cache = null;
            cachedModified = Long.MIN_VALUE;
        }
    }

    private static Path file() {
        return override != null ? override : FabricLoader.getInstance().getConfigDir().resolve("cyanotype").resolve("library.json");
    }

    private static String key(Path f) {
        return override != null ? f.toAbsolutePath().normalize().toString() : BlueprintLibrary.refOf(f);
    }

    private static Path pathOf(String key) {
        return override != null ? Path.of(key) : BlueprintLibrary.resolve(key);
    }

    private static Map<String, Extras> load() {
        Path p = file();
        long modified = 0;
        try {
            modified = Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : -1;
        } catch (IOException ignored) {
            // read below, and fail there
        }
        if (cache != null && modified == cachedModified) return cache;
        Map<String, Extras> out = new LinkedHashMap<>();
        if (modified >= 0) {
            try {
                JsonElement root = JsonParser.parseString(Files.readString(p));
                if (root.isJsonObject() && root.getAsJsonObject().has("files")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().getAsJsonObject("files").entrySet()) {
                        if (!e.getValue().isJsonObject()) continue;
                        JsonObject o = e.getValue().getAsJsonObject();
                        List<String> tags = new ArrayList<>();
                        if (o.has("tags") && o.get("tags").isJsonArray()) for (JsonElement t : o.getAsJsonArray("tags")) tags.add(t.getAsString());
                        out.put(e.getKey(), new Extras(tags, o.has("source") && o.get("source").isJsonObject() ? o.getAsJsonObject("source") : null));
                    }
                }
            } catch (IOException | RuntimeException e) {
                // a damaged index is kept aside, and the Library starts with none: tags are a convenience, never worth a crash
                Cyanotype.LOG.warn("The library index could not be read; starting a new one", e);
                try {
                    Files.move(p, p.resolveSibling(p.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // left where it is
                }
                modified = -1;
            }
        }
        cache = out;
        cachedModified = modified;
        return out;
    }

    private static void store(Map<String, Extras> map) {
        Path p = file();
        JsonObject files = new JsonObject();
        for (Map.Entry<String, Extras> e : map.entrySet()) {
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String t : e.getValue().tags()) arr.add(t);
            if (arr.size() > 0) o.add("tags", arr);
            if (e.getValue().source() != null) o.add("source", e.getValue().source());
            files.add(e.getKey(), o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("files", files);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(tmp, root.toString());
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            cachedModified = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            Cyanotype.LOG.warn("The library index could not be written", e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // a stray .tmp is harmless
            }
        }
    }

    public static List<String> tags(Path file) {
        synchronized (LOCK) {
            Extras e = load().get(key(file));
            return e == null ? List.of() : e.tags();
        }
    }

    public static @Nullable JsonObject source(Path file) {
        synchronized (LOCK) {
            Extras e = load().get(key(file));
            return e == null ? null : e.source();
        }
    }

    /** Sets what is known about a file; with no tags and no source the file has no entry. */
    public static void put(Path file, List<String> tags, @Nullable JsonObject source) {
        synchronized (LOCK) {
            Map<String, Extras> map = new LinkedHashMap<>(load());
            if (tags.isEmpty() && source == null) map.remove(key(file));
            else map.put(key(file), new Extras(List.copyOf(tags), source));
            store(map);
            cache = map;
        }
    }

    /** The file that came from this build of this site, if it is still there. */
    public static @Nullable Path findBySource(String site, String id) {
        synchronized (LOCK) {
            for (Map.Entry<String, Extras> e : load().entrySet()) {
                JsonObject src = e.getValue().source();
                if (src == null) continue;
                if (!id.equals(src.has("id") ? src.get("id").getAsString() : null) || !site.equals(src.has("site") ? src.get("site").getAsString() : null)) continue;
                Path file = pathOf(e.getKey());
                if (Files.isRegularFile(file)) return file;
            }
        }
        return null;
    }

    /** Drops the entries of files that are gone. @return how many */
    public static int prune() {
        synchronized (LOCK) {
            Map<String, Extras> map = new LinkedHashMap<>(load());
            int before = map.size();
            map.keySet().removeIf(k -> !Files.exists(pathOf(k)));
            if (map.size() != before) {
                store(map);
                cache = map;
            }
            return before - map.size();
        }
    }

    /**
     * Takes the {@code name.cyanotype.json} files that older versions left beside schematics into the index and deletes them.
     * A sidecar whose schematic is not there is left alone (the file may still be arriving).
     *
     * @return how many were moved
     */
    public static int importSidecars(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        int moved = 0;
        List<Path> sidecars = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".cyanotype.json")).sorted().forEach(sidecars::add);
        } catch (IOException e) {
            return 0;
        }
        for (Path side : sidecars) {
            Path file = side.resolveSibling(side.getFileName().toString().replaceFirst("\\.cyanotype\\.json$", ".litematic"));
            if (!Files.isRegularFile(file)) continue;
            try {
                JsonElement root = JsonParser.parseString(Files.readString(side));
                List<String> tags = new ArrayList<>();
                JsonObject source = null;
                if (root.isJsonObject()) {
                    JsonObject o = root.getAsJsonObject();
                    if (o.has("tags") && o.get("tags").isJsonArray()) for (JsonElement t : o.getAsJsonArray("tags")) tags.add(t.getAsString());
                    if (o.has("source") && o.get("source").isJsonObject()) source = o.getAsJsonObject("source");
                }
                synchronized (LOCK) {
                    // what the index already says wins: the sidecar is the older record
                    if (!load().containsKey(key(file))) put(file, tags, source);
                }
                Files.delete(side);
                moved++;
            } catch (IOException | RuntimeException e) {
                Cyanotype.LOG.warn("Could not move {} into the library index", side, e);
            }
        }
        return moved;
    }
}
