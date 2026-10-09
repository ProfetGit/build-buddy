package io.github.profetgit.buildbuddy.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GroupsTest {
    private static final int N = 12;

    private static int at(int x, int y, int z) {
        return (y * N + z) * N + x;
    }

    private static byte[] world() {
        return new byte[N * N * N];
    }

    private static Groups of(byte[] k) {
        return Groups.of(k, N, N, N);
    }

    @Test
    void aTrunkAndItsLeavesAreOneTree() {
        byte[] k = world();
        for (int y = 1; y <= 4; y++) k[at(5, y, 5)] = Groups.LOG;
        for (int y = 4; y <= 6; y++) for (int z = 4; z <= 6; z++) for (int x = 4; x <= 6; x++) if (k[at(x, y, z)] == 0) k[at(x, y, z)] = Groups.LEAF;
        Groups g = of(k);
        assertEquals(Groups.Type.TREE, g.typeOf(at(5, 2, 5)));
        assertEquals(Groups.Type.TREE, g.typeOf(at(6, 6, 6)));
        assertEquals(g.labelOf(at(5, 1, 5)), g.labelOf(at(4, 6, 4)));
        assertEquals(4 + 3 * 3 * 3 - 1, g.sizeOf(at(5, 3, 5)));
        assertEquals(g.sizeOf(at(5, 3, 5)), g.cellsOf(at(5, 3, 5)).length);
    }

    @Test
    void logBeamsOfAHouseWithNoLeavesNearStandAlone() {
        byte[] k = world();
        for (int y = 1; y <= 4; y++) k[at(2, y, 2)] = Groups.LOG;
        Groups g = of(k);
        assertEquals(Groups.Type.BLOCK, g.typeOf(at(2, 2, 2)));
        assertEquals(1, g.sizeOf(at(2, 2, 2)));
        assertArrayEquals(new int[]{at(2, 2, 2)}, g.cellsOf(at(2, 2, 2)));
    }

    @Test
    void aWallOfBuiltBlocksIsNeverGrouped() {
        byte[] k = world();
        for (int x = 0; x < N; x++) k[at(x, 1, 1)] = Groups.BUILT;
        Groups g = of(k);
        assertEquals(1, g.sizeOf(at(3, 1, 1)));
        assertEquals(0, g.labelOf(at(3, 1, 1)));
    }

    @Test
    void theGroundIsOneThingAndAHouseOnItIsNot() {
        byte[] k = world();
        for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) k[at(x, 0, z)] = Groups.TERRAIN;
        for (int x = 3; x < 6; x++) k[at(x, 1, 3)] = Groups.BUILT;
        // a stone far off, not touching the ground, is its own lump
        k[at(10, 5, 10)] = Groups.TERRAIN;
        Groups g = of(k);
        assertEquals(Groups.Type.GROUND, g.typeOf(at(0, 0, 0)));
        assertEquals(N * N, g.sizeOf(at(7, 0, 7)));
        assertEquals(1, g.sizeOf(at(4, 1, 3)));
        assertEquals(Groups.Type.GROUND, g.typeOf(at(10, 5, 10)));
        assertNotEquals(g.labelOf(at(10, 5, 10)), g.labelOf(at(0, 0, 0)));
    }

    @Test
    void plantsOneGapApartArePartOfTheSamePatchButFarOnesAreNot() {
        byte[] k = world();
        k[at(1, 1, 1)] = Groups.PLANT;
        k[at(3, 1, 1)] = Groups.PLANT;
        k[at(10, 1, 10)] = Groups.PLANT;
        Groups g = of(k);
        assertEquals(g.labelOf(at(1, 1, 1)), g.labelOf(at(3, 1, 1)));
        assertEquals(2, g.sizeOf(at(1, 1, 1)));
        assertEquals(1, g.sizeOf(at(10, 1, 10)));
        assertEquals(Groups.Type.PLANTS, g.typeOf(at(10, 1, 10)));
    }

    @Test
    void waterIsOneBodyAndGroundNextToItIsAnother() {
        byte[] k = world();
        for (int x = 0; x < 4; x++) k[at(x, 1, 0)] = Groups.FLUID;
        for (int x = 0; x < 4; x++) k[at(x, 0, 0)] = Groups.TERRAIN;
        Groups g = of(k);
        assertEquals(Groups.Type.WATER, g.typeOf(at(2, 1, 0)));
        assertEquals(4, g.sizeOf(at(2, 1, 0)));
        assertEquals(Groups.Type.GROUND, g.typeOf(at(2, 0, 0)));
    }

    @Test
    void whatIsNotThereAndWhatIsOutsideAreNothing() {
        Groups g = of(world());
        assertEquals(0, g.labelOf(-1));
        assertEquals(0, g.labelOf(N * N * N + 5));
        assertTrue(Groups.none().cellsOf(-1).length == 0);
    }

    @Test
    void hoveringATreeLightsUpWholeTreeNotJustTheBlock() {
        byte[] k = world();
        int[] cells = new int[N * N * N];
        BlockLook.Look[] looks = {BlockLook.Look.solid(0xFF508040)};
        for (int y = 1; y <= 5; y++) {
            cells[at(5, y, 5)] = 1;
            k[at(5, y, 5)] = Groups.LEAF;
        }
        PreviewRaster.Scene s = PreviewRaster.scene(cells, looks, N, N, N, k);
        int hover = at(5, 5, 5);
        PreviewRaster.View v = PreviewRaster.View.HOME;
        int[] one = PreviewRaster.render(s, v, 200, 200, 1, hover, false).px();
        int[] all = PreviewRaster.render(s, v, 200, 200, 1, hover, true).px();
        int plain = PreviewRaster.render(s, v, 200, 200, 1, -1, false).px()[0];
        int redOne = 0, redAll = 0;
        for (int i = 0; i < one.length; i++) {
            if (one[i] != 0 && (one[i] >> 16 & 255) > (one[i] >> 8 & 255) + 40) redOne++;
            if (all[i] != 0 && (all[i] >> 16 & 255) > (all[i] >> 8 & 255) + 40) redAll++;
        }
        assertEquals(0, plain);
        assertTrue(redAll > 2 * redOne && redOne > 0, "one block " + redOne + " red pixels, whole " + redAll);
    }
}
