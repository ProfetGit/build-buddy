package io.github.profetgit.cyanotype.community;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WordsTest {
    @Test
    void sizesOfFilesReadNaturally() {
        assertEquals("0 B", Words.bytes(0));
        assertEquals("657 B", Words.bytes(657));
        assertEquals("1.0 KB", Words.bytes(1000));
        assertEquals("5.7 KB", Words.bytes(5655));
        assertEquals("29 KB", Words.bytes(28881));
        assertEquals("766 KB", Words.bytes(765813));
        assertEquals("1.0 MB", Words.bytes(1_000_000));
        assertEquals("?", Words.bytes(-1));
    }

    @Test
    void countsAndNounsAgree() {
        assertEquals("1 download", Words.downloads(1));
        assertEquals("0 downloads", Words.downloads(0));
        assertEquals("3 downloads", Words.downloads(3));
        assertEquals("12.4k downloads", Words.downloads(12_400));
        assertEquals("1 build", Words.builds(1));
        assertEquals("17 builds", Words.builds(17));
    }

    @Test
    void pagesAndProgress() {
        assertEquals("Page 2 of 9", Words.page(2, 9));
        assertEquals("Page 1 of 1", Words.page(1, 0));
        assertEquals("12 KB of 29 KB", Words.progress(12_000, 28_881));
        assertEquals("12 KB", Words.progress(12_000, -1));
    }
}
