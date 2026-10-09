package io.github.profetgit.buildbuddy.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.profetgit.buildbuddy.TestBootstrap;
import io.github.profetgit.buildbuddy.blueprint.Blueprint;
import io.github.profetgit.buildbuddy.blueprint.LitematicReader;
import io.github.profetgit.buildbuddy.blueprint.PaletteEntry;
import io.github.profetgit.buildbuddy.blueprint.Region;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintSaverTest {
    @org.junit.jupiter.api.BeforeEach
    void libraryIndex(@org.junit.jupiter.api.io.TempDir Path indexDir) {
        io.github.profetgit.buildbuddy.placement.LibraryIndex.useFile(indexDir.resolve("library.json"));
    }

    @org.junit.jupiter.api.AfterEach
    void libraryIndexOff() {
        io.github.profetgit.buildbuddy.placement.LibraryIndex.useFile(null);
    }

    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static Blueprint tiny(String name) {
        PaletteEntry[] palette = {PaletteEntry.AIR, PaletteEntry.of(Blocks.STONE.defaultBlockState())};
        Region r = new Region(name, 0, 0, 0, 2, 1, 1, palette, new short[]{1, 0}, List.of());
        return new Blueprint(Blueprint.Metadata.of(name), List.of(r));
    }

    @Test
    void namesBecomeSafeFileNames() {
        assertEquals("My house", BlueprintSaver.stem("  My   house  "));
        assertEquals("a_b_c", BlueprintSaver.stem("a/b\\c"));
        assertEquals("blueprint", BlueprintSaver.stem("   "));
        assertEquals("blueprint", BlueprintSaver.stem("..."));
        assertEquals("Kotitalo (2)", BlueprintSaver.stem("Kotitalo (2)"));
        assertEquals("a_b", BlueprintSaver.stem("a:b"));
        assertEquals(BlueprintSaver.MAX_STEM, BlueprintSaver.stem("x".repeat(200)).length());
    }

    @Test
    void namesWindowsCannotCreateGetAnUnderscore() {
        assertEquals("CON_", BlueprintSaver.stem("CON"));
        assertEquals("nul_", BlueprintSaver.stem("nul"));
        assertEquals("com1_", BlueprintSaver.stem("com1"));
        assertEquals("Lpt9_", BlueprintSaver.stem("Lpt9"));
        assertEquals("aux_.txt", BlueprintSaver.stem("aux.txt"));
        assertEquals("console", BlueprintSaver.stem("console"));
        assertEquals("com10", BlueprintSaver.stem("com10"));
    }

    @Test
    void aTagsFileLeftBehindKeepsAFreshNameFromReusingIt(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("house.buildbuddy.json"), "{}");
        assertEquals("house.litematic", BlueprintSaver.freePath(dir, "house").getFileName().toString(), "the plain rule looks at the schematic only");
        assertEquals("house 2.litematic", BlueprintSaver.freePath(dir, "house", true).getFileName().toString());
        assertEquals("fresh.litematic", BlueprintSaver.freePath(dir, "fresh", true).getFileName().toString());
        assertEquals("fresh.litematic", BlueprintSaver.freePath(dir.resolve("not-yet"), "fresh", true).getFileName().toString());
    }

    @Test
    void anExistingFileIsNeverReplaced(@TempDir Path dir) throws IOException {
        assertEquals("house.litematic", BlueprintSaver.freePath(dir, "house").getFileName().toString());
        Files.writeString(dir.resolve("house.litematic"), "x");
        assertEquals("house 2.litematic", BlueprintSaver.freePath(dir, "house").getFileName().toString());
        Files.writeString(dir.resolve("house 2.litematic"), "x");
        assertEquals("house 3.litematic", BlueprintSaver.freePath(dir, "house").getFileName().toString());
        // a file system that ignores case would treat these as the same
        assertEquals("House 3.litematic", BlueprintSaver.freePath(dir, "House").getFileName().toString());
    }

    @Test
    void tagsAreTrimmedLoweredAndNotRepeated() {
        assertEquals(List.of("house", "medieval", "big roof"), BlueprintSaver.parseTags(" House, medieval ,, HOUSE,  Big   Roof "));
        assertTrue(BlueprintSaver.parseTags("  , ,").isEmpty());
        assertEquals(BlueprintSaver.MAX_TAGS, BlueprintSaver.parseTags("a,b,c,d,e,f,g,h,i,j,k,l,m,n,o").size());
        assertEquals(BlueprintSaver.MAX_TAG_LENGTH, BlueprintSaver.parseTags("x".repeat(80)).get(0).length());
    }

    @Test
    void saveWritesAReadableFileAndPutsTheTagsInTheIndex(@TempDir Path dir) throws IOException {
        Path file = BlueprintSaver.save(tiny("shed"), dir, "shed", List.of("small", "wood"));
        assertEquals("shed.litematic", file.getFileName().toString());
        Blueprint back = LitematicReader.read(file);
        assertEquals(1, back.totalBlocks());
        assertFalse(Files.exists(dir.resolve("shed.buildbuddy.json")), "the tags are not written beside the schematic");
        assertEquals(List.of("small", "wood"), LibraryIndex.tags(file));
        assertFalse(Files.exists(dir.resolve("shed.litematic.tmp")));
    }

    @Test
    void noTagsMeansNoSidecarAndAStaleOneGoes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("shed.buildbuddy.json"), "{\"tags\":[\"old\"]}");
        // "shed" has no schematic yet, so it is free: the old sidecar belongs to nothing
        Path file = BlueprintSaver.save(tiny("shed"), dir, "shed", List.of());
        assertEquals("shed.litematic", file.getFileName().toString());
        assertFalse(Files.exists(dir.resolve("shed.buildbuddy.json")));
    }

    @Test
    void aSecondSaveWithTheSameNameKeepsBoth(@TempDir Path dir) throws IOException {
        Path a = BlueprintSaver.save(tiny("hut"), dir, "hut", List.of("a"));
        Path b = BlueprintSaver.save(tiny("hut"), dir, "hut", List.of("b"));
        assertEquals("hut.litematic", a.getFileName().toString());
        assertEquals("hut 2.litematic", b.getFileName().toString());
        assertEquals(List.of("a"), LibraryIndex.tags(a));
        assertEquals(List.of("b"), LibraryIndex.tags(b));
        assertFalse(Files.exists(dir.resolve("hut.buildbuddy.json")) || Files.exists(dir.resolve("hut 2.buildbuddy.json")));
    }
}
