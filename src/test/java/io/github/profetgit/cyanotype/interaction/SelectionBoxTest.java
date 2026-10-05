package io.github.profetgit.cyanotype.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SelectionBoxTest {
    @Test
    void twoCornersMakeABoxInAnyOrder() {
        SelectionBox a = SelectionBox.of(5, 2, 9, 1, 7, 3);
        assertEquals(new SelectionBox(1, 2, 3, 5, 7, 9), a);
        assertEquals(5, a.sizeX());
        assertEquals(6, a.sizeY());
        assertEquals(7, a.sizeZ());
        assertEquals(5L * 6 * 7, a.volume());
        assertEquals("5 x 6 x 7", a.sizeText());
    }

    @Test
    void oneCellIsABoxOfOne() {
        SelectionBox a = SelectionBox.of(4, 4, 4, 4, 4, 4);
        assertEquals(1, a.volume());
    }

    @Test
    void aFaceMovesOutwardOrInward() {
        SelectionBox b = SelectionBox.of(0, 0, 0, 3, 3, 3);
        assertEquals(new SelectionBox(0, 0, 0, 5, 3, 3), b.moved(Direction.EAST, 2));
        assertEquals(new SelectionBox(-2, 0, 0, 3, 3, 3), b.moved(Direction.WEST, 2));
        assertEquals(new SelectionBox(0, 0, 0, 3, 3, 1), b.moved(Direction.SOUTH, -2));
        assertEquals(new SelectionBox(0, 2, 0, 3, 3, 3), b.moved(Direction.DOWN, -2));
        assertEquals(new SelectionBox(0, 0, 0, 3, 6, 3), b.moved(Direction.UP, 3));
        assertEquals(new SelectionBox(0, 0, -1, 3, 3, 3), b.moved(Direction.NORTH, 1));
    }

    @Test
    void pullingAFacePastTheOppositeOneStopsAtOneBlock() {
        SelectionBox b = SelectionBox.of(0, 0, 0, 3, 3, 3);
        assertEquals(new SelectionBox(0, 0, 0, 0, 3, 3), b.moved(Direction.EAST, -10));
        assertEquals(new SelectionBox(3, 0, 0, 3, 3, 3), b.moved(Direction.WEST, -10));
        assertEquals(1, b.moved(Direction.UP, -10).sizeY());
        assertEquals(1, b.moved(Direction.DOWN, -10).sizeY());
    }

    @Test
    void movingAFaceDoesNotChangeTheOthers() {
        SelectionBox b = SelectionBox.of(2, 2, 2, 5, 5, 5);
        SelectionBox m = b.moved(Direction.EAST, 4);
        assertEquals(b.x0(), m.x0());
        assertEquals(b.y0(), m.y0());
        assertEquals(b.z0(), m.z0());
        assertEquals(b.y1(), m.y1());
        assertEquals(b.z1(), m.z1());
    }

    @Test
    void chunkColumnsCountEveryChunkTheBoxTouches() {
        assertEquals(1, SelectionBox.of(1, 0, 1, 14, 5, 14).chunkColumns());
        assertEquals(4, SelectionBox.of(10, 0, 10, 20, 5, 20).chunkColumns());
        assertEquals(2, SelectionBox.of(-3, 0, 0, 3, 5, 0).chunkColumns());
    }
}
