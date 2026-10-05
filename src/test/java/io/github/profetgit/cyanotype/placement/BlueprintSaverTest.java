package io.github.profetgit.cyanotype.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.profetgit.cyanotype.TestBootstrap;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.LitematicReader;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintSaverTest {
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
    void saveWritesAReadableFileAndTheTagsSidecar(@TempDir Path dir) throws IOException {
        Path file = BlueprintSaver.save(tiny("shed"), dir, "shed", List.of("small", "wood"));
        assertEquals("shed.litematic", file.getFileName().toString());
        Blueprint back = LitematicReader.read(file);
        assertEquals(1, back.totalBlocks());
        Path side = dir.resolve("shed.cyanotype.json");
        assertTrue(Files.isRegularFile(side));
        var tags = JsonParser.parseString(Files.readString(side)).getAsJsonObject().getAsJsonArray("tags");
        assertEquals("small", tags.get(0).getAsString());
        assertEquals("wood", tags.get(1).getAsString());
        assertFalse(Files.exists(dir.resolve("shed.litematic.tmp")));
    }

    @Test
    void noTagsMeansNoSidecarAndAStaleOneGoes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("shed.cyanotype.json"), "{\"tags\":[\"old\"]}");
        // "shed" has no schematic yet, so it is free: the old sidecar belongs to nothing
        Path file = BlueprintSaver.save(tiny("shed"), dir, "shed", List.of());
        assertEquals("shed.litematic", file.getFileName().toString());
        assertFalse(Files.exists(dir.resolve("shed.cyanotype.json")));
    }

    @Test
    void aSecondSaveWithTheSameNameKeepsBoth(@TempDir Path dir) throws IOException {
        Path a = BlueprintSaver.save(tiny("hut"), dir, "hut", List.of("a"));
        Path b = BlueprintSaver.save(tiny("hut"), dir, "hut", List.of("b"));
        assertEquals("hut.litematic", a.getFileName().toString());
        assertEquals("hut 2.litematic", b.getFileName().toString());
        assertTrue(Files.exists(dir.resolve("hut.cyanotype.json")));
        assertTrue(Files.exists(dir.resolve("hut 2.cyanotype.json")));
    }
}
