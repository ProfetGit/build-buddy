package io.github.profetgit.cyanotype.blueprint;

/**
 * The bit-packed block array of a .litematic region: palette indices of {@code bits} bits each, laid end to end across
 * a long array (an entry may straddle two longs; nothing is padded, unlike vanilla's paletted containers).
 */
public final class BitPacking {
    private BitPacking() {
    }

    /** Litematica's width: enough bits for the palette, never fewer than 2. */
    public static int bitsFor(int paletteSize) {
        if (paletteSize < 1) throw new IllegalArgumentException("empty palette");
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    public static int longsFor(long count, int bits) {
        long n = (count * bits + 63) >>> 6;
        if (n > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("region too large: " + count + " blocks");
        return (int) n;
    }

    public static long[] pack(short[] values, int bits) {
        if (bits < 1 || bits > 16) throw new IllegalArgumentException("bits " + bits);
        long[] data = new long[longsFor(values.length, bits)];
        long max = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            long v = values[i] & 0xFFFFL;
            if (v > max) throw new IllegalArgumentException("value " + v + " does not fit in " + bits + " bits");
            long bit = (long) i * bits;
            int word = (int) (bit >>> 6);
            int off = (int) (bit & 63);
            data[word] |= v << off;
            if (off + bits > 64) data[word + 1] |= v >>> (64 - off);
        }
        return data;
    }

    public static short[] unpack(long[] data, int bits, int count) {
        if (bits < 1 || bits > 16) throw new IllegalArgumentException("bits " + bits);
        if (data.length < longsFor(count, bits)) {
            throw new IllegalArgumentException("block data holds " + data.length + " longs, " + longsFor(count, bits) + " needed for " + count + " blocks at " + bits + " bits");
        }
        short[] out = new short[count];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < count; i++) {
            long bit = (long) i * bits;
            int word = (int) (bit >>> 6);
            int off = (int) (bit & 63);
            long v = data[word] >>> off;
            if (off + bits > 64) v |= data[word + 1] << (64 - off);
            out[i] = (short) (v & mask);
        }
        return out;
    }
}
