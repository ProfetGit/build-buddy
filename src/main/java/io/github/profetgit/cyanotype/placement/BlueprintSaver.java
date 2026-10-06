package io.github.profetgit.cyanotype.placement;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.LitematicWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Putting a captured blueprint on disk: a safe file name from what the player typed (never replacing a file that is
 * already there) and the .litematic itself; the tags go in the {@link LibraryIndex} (PRD 7.1a: our extras never go inside the
 * schematic, and since 0.0.48 not beside it either).
 */
public final class BlueprintSaver {
    public static final int MAX_TAGS = 12, MAX_TAG_LENGTH = 24, MAX_STEM = 60;

    private BlueprintSaver() {
    }

    /** A file name (without extension) for a typed name: letters, digits, spaces and - _ . ( ) stay, the rest become underscores. */
    public static String stem(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.trim().toCharArray()) {
            boolean ok = Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.' || c == '(' || c == ')';
            sb.append(ok ? c : '_');
        }
        String s = sb.toString().replaceAll("\\s+", " ").replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        if (s.length() > MAX_STEM) s = s.substring(0, MAX_STEM).trim();
        if (s.isEmpty()) return "blueprint";
        // Windows cannot create a file named like a device (CON, NUL, COM1...), with or without an extension
        java.util.regex.Matcher dev = RESERVED.matcher(s);
        if (dev.matches()) s = dev.group(1) + "_" + (dev.group(2) == null ? "" : dev.group(2));
        return s;
    }

    private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");

    /** The first of {@code stem}, {@code stem 2}, {@code stem 3}... that has no file yet in the folder. */
    public static Path freePath(Path dir, String stem) {
        return freePath(dir, stem, false);
    }

    /** As {@link #freePath(Path, String)}; with {@code sidecarToo} a name whose tags file is still lying there is not free either. */
    public static Path freePath(Path dir, String stem, boolean sidecarToo) {
        Path p = dir.resolve(stem + ".litematic");
        int n = 2;
        while (exists(p) || sidecarToo && exists(dir.resolve(p.getFileName().toString().replaceFirst("(?i)\\.litematic$", "") + ".cyanotype.json"))) {
            p = dir.resolve(stem + " " + n++ + ".litematic");
        }
        return p;
    }

    private static boolean exists(Path p) {
        // a case-insensitive file system would otherwise let "House" replace "house"
        if (!Files.isDirectory(p.getParent())) return false;
        try (var s = Files.list(p.getParent())) {
            String want = p.getFileName().toString().toLowerCase(Locale.ROOT);
            return s.anyMatch(q -> q.getFileName().toString().toLowerCase(Locale.ROOT).equals(want));
        } catch (IOException e) {
            return Files.exists(p);
        }
    }

    /** Tags from a typed line: split at commas, trimmed, lower case, no repeats, no long ones. */
    public static List<String> parseTags(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : text.split(",")) {
            String tag = t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (tag.isEmpty()) continue;
            if (tag.length() > MAX_TAG_LENGTH) tag = tag.substring(0, MAX_TAG_LENGTH).trim();
            out.add(tag);
            if (out.size() >= MAX_TAGS) break;
        }
        return new ArrayList<>(out);
    }

    /**
     * Writes the blueprint into a folder and its tags into the index. The file is written to a temporary name and moved into place,
     * so a crash never leaves half a schematic in the library.
     *
     * @return the file written
     */
    public static Path save(Blueprint bp, Path dir, String stem, List<String> tags) throws IOException {
        Files.createDirectories(dir);
        Path file = freePath(dir, stem);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try {
            LitematicWriter.write(bp, tmp);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        // tags live in the library index, not in a file beside the schematic; a stale tags file of an older version under this name goes
        Files.deleteIfExists(dir.resolve(file.getFileName().toString().replaceFirst("(?i)\\.litematic$", "") + ".cyanotype.json"));
        LibraryIndex.put(file, tags, null);
        return file;
    }
}
