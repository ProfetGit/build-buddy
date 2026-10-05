package io.github.profetgit.cyanotype.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerRulesTest {
    @Test
    void aServerNobodyDecidedAboutAsks(@TempDir Path dir) {
        ServerRules r = new ServerRules(dir.resolve("servers.json"));
        assertEquals(ServerRules.Decision.ASK, r.decision("play.example.com"));
        assertEquals(ServerRules.Decision.ASK, r.decision(""));
        assertEquals(ServerRules.Decision.ASK, r.decision(null));
    }

    @Test
    void choicesAreRememberedPerServerAndSurviveARestart(@TempDir Path dir) {
        Path f = dir.resolve("cyanotype").resolve("servers.json");
        ServerRules r = new ServerRules(f);
        r.allow("Play.Example.com:25565");
        r.decline("other.example.org");
        r.block("bad.example.net");
        assertEquals(ServerRules.Decision.ALLOWED, r.decision("play.example.com"));
        assertEquals(ServerRules.Decision.DECLINED, r.decision("other.example.org."));
        assertEquals(ServerRules.Decision.BLOCKED, r.decision("BAD.example.net"));
        assertEquals(ServerRules.Decision.ASK, r.decision("third.example.com"));
        ServerRules again = new ServerRules(f);
        assertEquals(ServerRules.Decision.ALLOWED, again.decision("play.example.com"));
        assertEquals(ServerRules.Decision.DECLINED, again.decision("other.example.org"));
        assertEquals(ServerRules.Decision.BLOCKED, again.decision("bad.example.net"));
        assertEquals(List.of("bad.example.net"), again.blocked());
    }

    @Test
    void aBlockBeatsAnAllowAndForgettingItsChoiceLeavesItBlocked(@TempDir Path dir) {
        ServerRules r = new ServerRules(dir.resolve("servers.json"));
        r.allow("play.example.com");
        r.block("play.example.com");
        assertEquals(ServerRules.Decision.BLOCKED, r.decision("play.example.com"));
        assertTrue(r.remembered().isEmpty(), "blocking forgets the allow");
        // allowing a blocked server does not lift the block
        r.allow("play.example.com");
        assertEquals(ServerRules.Decision.BLOCKED, r.decision("play.example.com"));
        r.forget("play.example.com");
        assertEquals(ServerRules.Decision.BLOCKED, r.decision("play.example.com"));
        r.unblock("play.example.com");
        r.forget("play.example.com");
        assertEquals(ServerRules.Decision.ASK, r.decision("play.example.com"));
    }

    @Test
    void forgetAsksAgain(@TempDir Path dir) {
        ServerRules r = new ServerRules(dir.resolve("servers.json"));
        r.decline("play.example.com");
        assertEquals(ServerRules.Decision.DECLINED, r.decision("play.example.com"));
        r.forget("play.example.com");
        assertEquals(ServerRules.Decision.ASK, r.decision("play.example.com"));
    }

    @Test
    void aHandEditedFileMayUseAnySpellingOfAnAddress(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("servers.json");
        Files.writeString(f, "{\"remembered\":{\"Play.Example.COM:25565\":\"allowed\",\"junk\":\"maybe\"},\"blocked\":[\" Bad.Example.net. \",\"\"]}");
        ServerRules r = new ServerRules(f);
        assertEquals(ServerRules.Decision.ALLOWED, r.decision("play.example.com"));
        assertEquals(ServerRules.Decision.BLOCKED, r.decision("bad.example.net"));
        assertEquals(ServerRules.Decision.ASK, r.decision("junk"));
        assertEquals(List.of("bad.example.net"), r.blocked());
    }

    @Test
    void aBrokenFileIsKeptAsideAndNothingIsAllowedByIt(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("servers.json");
        Files.writeString(f, "{ this is not json");
        ServerRules r = new ServerRules(f);
        assertEquals(ServerRules.Decision.ASK, r.decision("play.example.com"));
        assertTrue(Files.exists(dir.resolve("servers.json.bad")), "the broken file is not overwritten");
        r.block("bad.example.net");
        assertEquals("{ this is not json", Files.readString(dir.resolve("servers.json.bad")));
        assertEquals(ServerRules.Decision.BLOCKED, new ServerRules(f).decision("bad.example.net"));
    }

    @Test
    void emptyAddressesAreIgnored(@TempDir Path dir) {
        ServerRules r = new ServerRules(dir.resolve("servers.json"));
        r.allow("  ");
        r.block("");
        r.decline(null);
        assertTrue(r.blocked().isEmpty());
        assertTrue(r.remembered().isEmpty());
    }
}
