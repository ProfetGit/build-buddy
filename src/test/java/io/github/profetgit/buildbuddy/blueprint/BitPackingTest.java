package io.github.profetgit.buildbuddy.blueprint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BitPackingTest {
    /** Independent reference: the array as a flat bit string, value i occupying bits [i*bits, (i+1)*bits), low bit first. */
    private static long[] reference(short[] values, int bits) {
        boolean[] flat = new boolean[values.length * bits];
        for (int i = 0; i < values.length; i++) {
            for (int b = 0; b < bits; b++) flat[i * bits + b] = ((values[i] >> b) & 1) != 0;
        }
        long[] out = new long[(flat.length + 63) / 64];
        for (int i = 0; i < flat.length; i++) {
            if (flat[i]) out[i / 64] |= 1L << (i % 64);
        }
        return out;
    }

    @Test
    void widthFollowsPaletteSizeWithAFloorOfTwo() {
        assertEquals(2, BitPacking.bitsFor(1));
        assertEquals(2, BitPacking.bitsFor(2));
        assertEquals(2, BitPacking.bitsFor(4));
        assertEquals(3, BitPacking.bitsFor(5));
        assertEquals(4, BitPacking.bitsFor(16));
        assertEquals(5, BitPacking.bitsFor(17));
        assertEquals(6, BitPacking.bitsFor(64));
        assertEquals(7, BitPacking.bitsFor(65));
        assertEquals(16, BitPacking.bitsFor(65536));
    }

    @Test
    void knownVector() {
        long expected = 1L | (2L << 5) | (3L << 10);
        assertArrayEquals(new long[]{expected}, BitPacking.pack(new short[]{1, 2, 3}, 5));
        assertArrayEquals(new short[]{1, 2, 3}, BitPacking.unpack(new long[]{expected}, 5, 3));
    }

    @Test
    void entryStraddlingTwoLongs() {
        // 13 bits: the 5th value starts at bit 52 and ends at bit 64, the 6th starts at 65; the 5th..6th cross words
        short[] v = new short[10];
        for (int i = 0; i < v.length; i++) v[i] = (short) (0x1FFF - i * 37);
        long[] packed = BitPacking.pack(v, 13);
        assertArrayEquals(reference(v, 13), packed);
        assertArrayEquals(v, BitPacking.unpack(packed, 13, v.length));
    }

    @Test
    void everyPaletteWidthAgreesWithTheReference() {
        Random r = new Random(7);
        int[] sizes = {1, 2, 3, 4, 5, 8, 9, 16, 17, 31, 32, 33, 64, 65, 100, 255, 256, 257, 1000, 4096, 4097, 65536};
        int[] counts = {0, 1, 2, 31, 32, 63, 64, 65, 100, 1000, 4097};
        for (int size : sizes) {
            int bits = BitPacking.bitsFor(size);
            for (int count : counts) {
                short[] v = new short[count];
                for (int i = 0; i < count; i++) v[i] = (short) r.nextInt(size);
                long[] packed = BitPacking.pack(v, bits);
                assertArrayEquals(reference(v, bits), packed, "pack, palette " + size + ", " + count + " blocks");
                assertArrayEquals(v, BitPacking.unpack(packed, bits, count), "unpack, palette " + size + ", " + count + " blocks");
            }
        }
    }

    @Test
    void rejectsShortAndOversizedData() {
        assertThrows(IllegalArgumentException.class, () -> BitPacking.unpack(new long[1], 4, 100));
        assertThrows(IllegalArgumentException.class, () -> BitPacking.pack(new short[]{5}, 2));
    }
}
