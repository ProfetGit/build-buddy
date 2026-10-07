package io.github.profetgit.cyanotype.ponder;

import java.util.Arrays;

/**
 * One value over time: keyframes of a vector (a scalar is a vector of one) with the ease of each arriving segment. Before the
 * first key the first value holds, after the last key the last. Pure and allocation-free to read.
 */
public final class Track {
    final double[] times;
    final double[][] values;
    final Ease[] eases;

    public Track(double[] times, double[][] values, Ease[] eases) {
        if (times.length == 0 || times.length != values.length || times.length != eases.length) throw new IllegalArgumentException("a track needs the same number of times, values and eases, and at least one");
        int n = values[0].length;
        for (int i = 0; i < times.length; i++) {
            if (values[i].length != n) throw new IllegalArgumentException("every key of a track has as many numbers as the first");
            if (i > 0 && times[i] < times[i - 1]) throw new IllegalArgumentException("keys must be in time order");
        }
        this.times = times;
        this.values = values;
        this.eases = eases;
    }

    public static Track constant(double... v) {
        return new Track(new double[]{0}, new double[][]{v}, new Ease[]{Ease.LINEAR});
    }

    public int size() {
        return values[0].length;
    }

    public int keys() {
        return times.length;
    }

    public double firstTime() {
        return times[0];
    }

    public double lastTime() {
        return times[times.length - 1];
    }

    /** The value at a time, written into {@code out} (as many numbers as the track has). */
    public void at(double t, double[] out) {
        int n = size();
        int last = times.length - 1;
        if (t <= times[0]) {
            System.arraycopy(values[0], 0, out, 0, n);
            return;
        }
        if (t >= times[last]) {
            System.arraycopy(values[last], 0, out, 0, n);
            return;
        }
        // the segment [i - 1, i] that holds t
        int i = Arrays.binarySearch(times, t);
        if (i >= 0) {
            // exactly on a key; with several keys at one time the last one wins
            while (i + 1 < times.length && times[i + 1] == t) i++;
            System.arraycopy(values[i], 0, out, 0, n);
            return;
        }
        i = -i - 1;
        double span = times[i] - times[i - 1];
        double k = eases[i].apply((t - times[i - 1]) / span);
        double[] a = values[i - 1], b = values[i];
        for (int c = 0; c < n; c++) out[c] = a[c] + (b[c] - a[c]) * k;
    }

    /** The first number of the value at a time. */
    public double scalar(double t) {
        double[] tmp = new double[size()];
        at(t, tmp);
        return tmp[0];
    }

    public double[] at(double t) {
        double[] out = new double[size()];
        at(t, out);
        return out;
    }
}
