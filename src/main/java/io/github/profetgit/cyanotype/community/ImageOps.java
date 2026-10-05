package io.github.profetgit.cyanotype.community;

/** Picture maths with no game classes in it, so it can be tested: shrinking a downloaded preview to the size it is shown at. */
public final class ImageOps {
    private ImageOps() {
    }

    /**
     * Shrinks (or keeps) a picture by averaging the source pixels under each target pixel. Linear sampling alone shimmers
     * when an 800 x 600 picture is drawn 200 pixels wide; this does the averaging once, on a worker thread.
     *
     * @param px four bytes a pixel (any order: each byte is averaged on its own)
     */
    public static int[] boxDownscale(int[] px, int w, int h, int tw, int th) {
        if (w <= 0 || h <= 0 || tw <= 0 || th <= 0 || px.length < (long) w * h) throw new IllegalArgumentException("bad picture size");
        if (tw >= w && th >= h) return px.clone();
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int y0 = (int) ((long) y * h / th), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * h / th));
            for (int x = 0; x < tw; x++) {
                int x0 = (int) ((long) x * w / tw), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * w / tw));
                long a = 0, b = 0, c = 0, d = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int p = px[sy * w + sx];
                        a += p >>> 24;
                        b += (p >> 16) & 255;
                        c += (p >> 8) & 255;
                        d += p & 255;
                        n++;
                    }
                }
                out[y * tw + x] = (int) ((a + n / 2) / n) << 24 | (int) ((b + n / 2) / n) << 16 | (int) ((c + n / 2) / n) << 8 | (int) ((d + n / 2) / n);
            }
        }
        return out;
    }
}
