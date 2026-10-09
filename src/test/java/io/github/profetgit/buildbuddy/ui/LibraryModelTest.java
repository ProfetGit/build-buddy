package io.github.profetgit.buildbuddy.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.buildbuddy.TestBootstrap;
import io.github.profetgit.buildbuddy.blueprint.Blueprint;
import io.github.profetgit.buildbuddy.blueprint.PaletteEntry;
import io.github.profetgit.buildbuddy.blueprint.Region;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LibraryModelTest {
    @BeforeAll
    static void boot() {
        TestBootstrap.init();
    }

    private static Blueprint block(int sx, int sy, int sz, boolean[] solid) {
        PaletteEntry stone = PaletteEntry.of(Blocks.STONE.defaultBlockState());
        short[] blocks = new short[sx * sy * sz];
        for (int i = 0; i < blocks.length; i++) blocks[i] = (short) (solid == null || solid[i] ? 1 : 0);
        Region r = new Region("main", 0, 0, 0, sx, sy, sz, new PaletteEntry[]{PaletteEntry.AIR, stone}, blocks, new ArrayList<>());
        return new Blueprint(Blueprint.Metadata.of("t"), List.of(r));
    }

    private static int count(int[] px) {
        int n = 0;
        for (int p : px) if (p != 0) n++;
        return n;
    }

    @Test
    void aSolidCubeFillsAHexagonInEveryTurn() {
        int[][] f = Thumbnail.frames(block(6, 6, 6, null), 8, 48, 40);
        assertEquals(8, f.length);
        for (int i = 0; i < 8; i++) {
            int n = count(f[i]);
            assertTrue(n > 48 * 40 * 0.25 && n < 48 * 40 * 0.95, "frame " + i + " covers " + n + " of " + 48 * 40);
        }
    }

    @Test
    void anAsymmetricBuildLooksDifferentFromDifferentSides() {
        // an L: a tall bar along x and a short one along z
        boolean[] solid = new boolean[8 * 4 * 8];
        for (int y = 0; y < 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) solid[(y * 8 + z) * 8 + x] = z == 0 || x == 0 && y < 2;
        int[][] f = Thumbnail.frames(block(8, 4, 8, solid), 8, 48, 40);
        int differing = 0;
        for (int i = 1; i < 8; i++) if (!Arrays.equals(f[0], f[i])) differing++;
        assertTrue(differing >= 6, "most turns should differ, " + differing + " did");
        // a full turn comes back to the start (frame 0 again) by construction; opposite sides differ for an L
        assertFalse(Arrays.equals(f[0], f[4]));
    }

    @Test
    void emptyAndHugeBlueprintsGiveBlankPicturesNotCrashes() {
        boolean[] none = new boolean[27];
        assertEquals(0, count(Thumbnail.frames(block(3, 3, 3, none), 2, 32, 24)[0]));
        Blueprint zero = new Blueprint(Blueprint.Metadata.of("t"), List.of());
        assertEquals(0, count(Thumbnail.frames(zero, 1, 32, 24)[0]));
    }

    @Test
    void searchNeedsEveryWordAndIgnoresCase() {
        LibraryModel m = new LibraryModel();
        LibraryModel.Entry e = new LibraryModel.Entry(java.nio.file.Path.of("config", "Medieval Tower.litematic"), 5, 100, List.of("castle", "stone"));
        assertTrue(LibraryModel.matches(e, ""));
        assertTrue(LibraryModel.matches(e, "tower"));
        assertTrue(LibraryModel.matches(e, "MEDIEVAL castle"));
        assertTrue(LibraryModel.matches(e, "stone tower"));
        assertFalse(LibraryModel.matches(e, "tower wooden"));
        assertEquals("Medieval Tower", e.title());
    }

    @Test
    void sortsByRecentNameAndSize() {
        LibraryModel m = new LibraryModel();
        LibraryModel.Entry a = new LibraryModel.Entry(java.nio.file.Path.of("a.litematic"), 100, 1, List.of());
        LibraryModel.Entry b = new LibraryModel.Entry(java.nio.file.Path.of("b.litematic"), 300, 1, List.of());
        LibraryModel.Entry c = new LibraryModel.Entry(java.nio.file.Path.of("c.litematic"), 200, 1, List.of());
        a.info = new LibraryModel.Info("Zebra", "", "", 1, 1, 1, 10, 1, 0);
        b.info = new LibraryModel.Info("Apple", "", "", 1, 1, 1, 5000, 1, 0);
        // c has not been read yet
        m.all().addAll(List.of(a, b, c));
        assertEquals(List.of(b, c, a), m.view("", LibraryModel.Sort.RECENT));
        assertEquals(List.of(b, c, a), m.view("", LibraryModel.Sort.NAME).stream().map(e -> e).toList(), "Apple, c, Zebra");
        assertEquals(List.of(b, a, c), m.view("", LibraryModel.Sort.SIZE), "largest first, unread last");
        assertEquals(List.of(b), m.view("apple", LibraryModel.Sort.NAME));
    }
}
