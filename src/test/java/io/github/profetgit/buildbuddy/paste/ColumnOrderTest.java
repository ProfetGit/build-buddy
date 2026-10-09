package io.github.profetgit.buildbuddy.paste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColumnOrderTest {
    /** Every cell of the footprint is in exactly one column, and a column never crosses a chunk border. */
    private static void checkPartition(int sx, int sz, int wx, int wz) {
        ColumnOrder o = new ColumnOrder(sx, sz, wx, wz);
        int[][] seen = new int[sx][sz];
        for (int i = 0; i < o.count(); i++) {
            int[] c = o.column(i);
            assertTrue(c[0] < c[1] && c[2] < c[3], "a column has cells");
            assertEquals((wx + c[0]) >> 4, (wx + c[1] - 1) >> 4, "one chunk column in x");
            assertEquals((wz + c[2]) >> 4, (wz + c[3] - 1) >> 4, "one chunk column in z");
            assertEquals((wx + c[0]) >> 4, c[4]);
            assertEquals((wz + c[2]) >> 4, c[5]);
            for (int x = c[0]; x < c[1]; x++) for (int z = c[2]; z < c[3]; z++) seen[x][z]++;
        }
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) assertEquals(1, seen[x][z], "cell " + x + "," + z);
    }

    @Test
    void theColumnsCoverTheFootprintOnceWhereverItStands() {
        checkPartition(1, 1, 0, 0);
        checkPartition(16, 16, 0, 0);
        checkPartition(16, 16, 5, 9);
        checkPartition(100, 37, -50, 10);
        checkPartition(33, 200, -17, -1000);
        checkPartition(5, 5, -3, -3);
        checkPartition(1000, 1000, 123456, -654321);
    }

    @Test
    void aSmallBuildInsideOneChunkIsOneColumn() {
        assertEquals(1, new ColumnOrder(10, 10, 3, 3).count());
        assertEquals(4, new ColumnOrder(10, 10, 12, 12).count());
    }

    @Test
    void neighbouringColumnsFollowEachOtherInASnake() {
        ColumnOrder o = new ColumnOrder(48, 48, 0, 0);
        for (int i = 1; i < o.count(); i++) {
            int[] a = o.column(i - 1), b = o.column(i);
            assertEquals(1, Math.abs(a[4] - b[4]) + Math.abs(a[5] - b[5]), "column " + i + " touches the one before");
        }
    }
}
