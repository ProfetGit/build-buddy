package io.github.profetgit.buildbuddy.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryIndexTest {
    @TempDir
    Path dir;

    @BeforeEach
    void on() {
        LibraryIndex.useFile(dir.resolve("config").resolve("library.json"));
    }

    @AfterEach
    void off() {
        LibraryIndex.useFile(null);
    }

    private Path file(String name) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, "x");
        return f;
    }

    @Test
    void tagsAndSourceAreKeptPerFileAndSurviveAReload() throws IOException {
        Path a = file("a.litematic"), b = file("b.litematic");
        JsonObject src = new JsonObject();
        src.addProperty("site", "https://s.example");
        src.addProperty("id", "x1");
        LibraryIndex.put(a, List.of("house", "oak"), null);
        LibraryIndex.put(b, List.of("community"), src);
        // a fresh start reads what is on disk
        LibraryIndex.useFile(dir.resolve("config").resolve("library.json"));
        assertEquals(List.of("house", "oak"), LibraryIndex.tags(a));
        assertEquals("x1", LibraryIndex.source(b).get("id").getAsString());
        assertNull(LibraryIndex.source(a));
        assertEquals(b, LibraryIndex.findBySource("https://s.example", "x1"));
        assertNull(LibraryIndex.findBySource("https://other.example", "x1"));
        assertTrue(LibraryIndex.tags(file("c.litematic")).isEmpty());
    }

    @Test
    void noTagsAndNoSourceMeansNoEntry() throws IOException {
        Path a = file("a.litematic");
        LibraryIndex.put(a, List.of("x"), null);
        LibraryIndex.put(a, List.of(), null);
        assertTrue(LibraryIndex.tags(a).isEmpty());
        assertFalse(Files.readString(dir.resolve("config").resolve("library.json")).contains("a.litematic"));
    }

    @Test
    void entriesOfFilesThatAreGoneAreDropped() throws IOException {
        Path a = file("a.litematic"), b = file("b.litematic");
        LibraryIndex.put(a, List.of("x"), null);
        LibraryIndex.put(b, List.of("y"), null);
        Files.delete(a);
        assertEquals(1, LibraryIndex.prune());
        assertEquals(List.of("y"), LibraryIndex.tags(b));
        assertEquals(0, LibraryIndex.prune());
    }

    @Test
    void sidecarsOfOlderVersionsMoveInAndAreDeleted() throws IOException {
        Path a = file("My House.litematic");
        Files.writeString(dir.resolve("My House.buildbuddy.json"), "{\"tags\":[\"house\",\"medieval\"]}");
        Files.writeString(dir.resolve("Orphan.buildbuddy.json"), "{\"tags\":[\"lost\"]}");
        assertEquals(1, LibraryIndex.importSidecars(dir));
        assertEquals(List.of("house", "medieval"), LibraryIndex.tags(a));
        assertFalse(Files.exists(dir.resolve("My House.buildbuddy.json")));
        assertTrue(Files.exists(dir.resolve("Orphan.buildbuddy.json")), "a tags file with no schematic is left alone, the file may still be arriving");
        // what the index already says wins over an old sidecar
        Files.writeString(dir.resolve("My House.buildbuddy.json"), "{\"tags\":[\"stale\"]}");
        LibraryIndex.importSidecars(dir);
        assertEquals(List.of("house", "medieval"), LibraryIndex.tags(a));
        assertFalse(Files.exists(dir.resolve("My House.buildbuddy.json")));
    }

    @Test
    void aDamagedIndexIsKeptAsideAndTheLibraryStartsEmpty() throws IOException {
        Path idx = dir.resolve("config").resolve("library.json");
        Files.createDirectories(idx.getParent());
        Files.writeString(idx, "{ this is not json");
        Path a = file("a.litematic");
        assertTrue(LibraryIndex.tags(a).isEmpty());
        assertTrue(Files.exists(idx.resolveSibling("library.json.bad")));
        LibraryIndex.put(a, List.of("fresh"), null);
        assertEquals(List.of("fresh"), LibraryIndex.tags(a));
    }
}
