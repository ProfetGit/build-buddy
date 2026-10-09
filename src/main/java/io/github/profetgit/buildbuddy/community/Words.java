package io.github.profetgit.buildbuddy.community;

import java.util.Locale;

/** The short phrases the Community tab shows for numbers (pure, so the wording can be tested). */
public final class Words {
    private Words() {
    }

    /** "657 B", "28 KB", "1.2 MB". */
    public static String bytes(long n) {
        if (n < 0) return "?";
        if (n < 1000) return n + " B";
        if (n < 10_000) return String.format(Locale.ROOT, "%.1f KB", n / 1000.0);
        if (n < 1_000_000) return Math.round(n / 1000.0) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", n / 1_000_000.0);
    }

    /** "603", "12.4k". */
    public static String count(long n) {
        return n >= 10_000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
    }

    public static String downloads(long n) {
        return count(n) + (n == 1 ? " download" : " downloads");
    }

    public static String builds(long n) {
        return count(n) + (n == 1 ? " build" : " builds");
    }

    public static String page(int page, int pages) {
        return "Page " + page + " of " + Math.max(1, pages);
    }

    /** "9 x 6 x 7". */
    public static String size(Api.Build b) {
        return b.sx() + " x " + b.sy() + " x " + b.sz();
    }

    /** Where a download is: "12 KB of 28 KB", or just the part so far when the total is not known. */
    public static String progress(long got, long total) {
        return total > 0 ? bytes(got) + " of " + bytes(total) : bytes(got);
    }
}
