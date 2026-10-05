package io.github.profetgit.cyanotype.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FaceMesherTest {
    private static LongOpenHashSet box(int x0, int y0, int z0, int x1, int y1, int z1) {
        LongOpenHashSet s = new LongOpenHashSet();
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) s.add(BlockPos.asLong(x, y, z));
        return s;
    }

    private static final LongOpenHashSet NONE = new LongOpenHashSet();

    @Test
    void aSolidBoxIsSixRectangles() {
        FaceMesher.Quads q = FaceMesher.build(box(0, 0, 0, 9, 4, 6), NONE, NONE, 1000);
        assertEquals(6, q.count);
    }

    @Test
    void aSingleBlockIsSixSquaresJustOutsideItsFaces() {
        FaceMesher.Quads q = FaceMesher.build(box(2, 3, 4, 2, 3, 4), NONE, NONE, 1000);
        assertEquals(6, q.count);
        // every corner lies on a plane a hair outside the block, so it does not fight the real face
        for (int i = 0; i < q.count; i++) {
            int planes = 0;
            for (int c = 0; c < 4; c++) {
                for (int a = 0; a < 3; a++) {
                    float v = q.corners[i * 12 + c * 3 + a];
                    float lo = new float[]{2, 3, 4}[a];
                    if (v < lo - 0.001f || v > lo + 1.001f) planes++;
                }
            }
            assertEquals(4, planes, "the four corners of face " + i + " sit just off the block");
        }
    }

    @Test
    void anAdjacentFaceBetweenTwoBlocksIsNotDrawn() {
        FaceMesher.Quads q = FaceMesher.build(box(0, 0, 0, 1, 0, 0), NONE, NONE, 1000);
        // two blocks side by side: the long faces merge, the two ends stay: 6 rectangles
        assertEquals(6, q.count);
    }

    @Test
    void aHollowRoomHasInsideAndOutsideSkins() {
        LongOpenHashSet room = box(0, 0, 0, 6, 4, 6);
        for (int y = 1; y <= 3; y++) for (int z = 1; z <= 5; z++) for (int x = 1; x <= 5; x++) room.remove(BlockPos.asLong(x, y, z));
        FaceMesher.Quads q = FaceMesher.build(room, NONE, NONE, 1000);
        assertEquals(12, q.count);
    }

    @Test
    void theDoubtfulAndTheFaintAreTintedApartAndTheFaintGoFirstWhenThereAreTooMany() {
        LongOpenHashSet picked = box(0, 0, 0, 2, 2, 2), doubtful = new LongOpenHashSet();
        doubtful.add(BlockPos.asLong(1, 2, 1));
        LongOpenHashSet context = box(10, 0, 0, 12, 2, 2);
        FaceMesher.Quads q = FaceMesher.build(picked, doubtful, context, 1000);
        int[] kinds = new int[3];
        for (int i = 0; i < q.count; i++) kinds[q.kind[i]]++;
        assertTrue(kinds[FaceMesher.PICKED] > 0 && kinds[FaceMesher.DOUBTFUL] > 0 && kinds[FaceMesher.CONTEXT] == 6, kinds[0] + " " + kinds[1] + " " + kinds[2]);
        FaceMesher.Quads few = FaceMesher.build(picked, doubtful, context, kinds[0] + kinds[1]);
        assertEquals(kinds[0] + kinds[1], few.count);
        for (int i = 0; i < few.count; i++) assertTrue(few.kind[i] != FaceMesher.CONTEXT);
    }

    @Test
    void negativeCoordinatesWork() {
        FaceMesher.Quads q = FaceMesher.build(box(-5, -3, -9, -2, 1, -4), NONE, NONE, 1000);
        assertEquals(6, q.count);
    }
}
