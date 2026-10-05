package io.github.profetgit.cyanotype.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerKeyTest {
    @Test
    void spellingsOfOneAddressAreOneKey() {
        assertEquals("play.example.com", ServerKey.normalize("Play.Example.com"));
        assertEquals("play.example.com", ServerKey.normalize("  play.example.com  "));
        assertEquals("play.example.com", ServerKey.normalize("play.example.com."));
        assertEquals("play.example.com", ServerKey.normalize("play.example.com:25565"));
        assertEquals("play.example.com", ServerKey.normalize("PLAY.example.com.:25565"));
    }

    @Test
    void anotherPortIsAnotherServer() {
        assertEquals("play.example.com:25566", ServerKey.normalize("play.example.com:25566"));
        assertEquals("192.168.1.5:30000", ServerKey.normalize("192.168.1.5:30000"));
        assertEquals("192.168.1.5", ServerKey.normalize("192.168.1.5"));
    }

    @Test
    void ipv6KeepsItsColons() {
        assertEquals("[::1]", ServerKey.normalize("[::1]"));
        assertEquals("[::1]", ServerKey.normalize("[::1]:25565"));
        assertEquals("[2001:db8::1]:30000", ServerKey.normalize("[2001:DB8::1]:30000"));
    }

    @Test
    void nothingIsNothing() {
        assertEquals("", ServerKey.normalize(null));
        assertEquals("", ServerKey.normalize("   "));
        assertEquals("", ServerKey.normalize(""));
    }
}
