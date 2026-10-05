package io.github.profetgit.cyanotype.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommunityConfigTest {
    @Test
    void anAddressIsTidiedUp() {
        assertEquals("https://example.org", CommunityConfig.normalize("  https://Example.org/  "));
        assertEquals("https://example.org/cyanotype", CommunityConfig.normalize("https://example.org/cyanotype///"));
        assertEquals("https://example.org:8443", CommunityConfig.normalize("https://example.org:8443"));
    }

    @Test
    void plainHttpIsForThisComputerOnly() {
        assertEquals("http://localhost:3150", CommunityConfig.normalize("http://localhost:3150"));
        assertEquals("http://127.0.0.1:3150", CommunityConfig.normalize("http://127.0.0.1:3150/"));
        assertEquals("http://dev.localhost:3000", CommunityConfig.normalize("http://dev.localhost:3000"));
        assertNull(CommunityConfig.normalize("http://example.org"), "a download over plain http could be swapped on the way");
        assertNull(CommunityConfig.normalize("http://192.168.1.20:3150"));
        assertNull(CommunityConfig.normalize("http://127.0.0.1.example.org"));
    }

    @Test
    void thingsThatAreNotAnAddressAreRefused() {
        assertNull(CommunityConfig.normalize(null));
        assertNull(CommunityConfig.normalize("   "));
        assertNull(CommunityConfig.normalize("example.org"));
        assertNull(CommunityConfig.normalize("ftp://example.org"));
        assertNull(CommunityConfig.normalize("file:///etc/passwd"));
        assertNull(CommunityConfig.normalize("https://user:pass@example.org"));
        assertNull(CommunityConfig.normalize("https://example.org/?x=1"));
        assertNull(CommunityConfig.normalize("https://example.org/#frag"));
        assertNull(CommunityConfig.normalize("https://example.org/a/../b"));
        assertNull(CommunityConfig.normalize("https://exa mple.org"));
        assertNull(CommunityConfig.normalize("https://"));
    }

    @Test
    void theDefaultIsEmptyUntilTheSiteHasAHost() {
        // when this fails the site has an address: put it in DEFAULT_BASE_URL, then update this test
        assertEquals("", CommunityConfig.DEFAULT_BASE_URL);
        assertNull(CommunityConfig.effective(""), "no address anywhere means the tab says so and sends nothing");
        assertNull(CommunityConfig.effective(null));
        assertEquals("http://localhost:3150", CommunityConfig.effective("http://localhost:3150"));
        assertNull(CommunityConfig.effective("not an address"));
    }

    @Test
    void theUserAgentNamesTheModAndItsVersionOnly() {
        assertEquals("Cyanotype/0.0.14", CommunityConfig.userAgentFor("0.0.14+26.3-fabric"));
        assertEquals("Cyanotype/1.2.3-beta.1", CommunityConfig.userAgentFor("1.2.3-beta.1"));
        assertEquals("Cyanotype/dev", CommunityConfig.userAgentFor(""));
        assertEquals("Cyanotype/dev", CommunityConfig.userAgentFor(null));
        assertEquals("Cyanotype/1.0---x", CommunityConfig.userAgentFor("1.0 \r\nx"), "nothing that could break a header line");
    }

    @Test
    void theHostIsWhatAPlayerRecognises() {
        assertEquals("localhost:3150", CommunityConfig.hostOf("http://localhost:3150"));
        assertEquals("example.org", CommunityConfig.hostOf("https://example.org/cyanotype"));
        assertTrue(CommunityConfig.isLoopback("LOCALHOST"));
        assertFalse(CommunityConfig.isLoopback("localhost.example.org"));
    }
}
