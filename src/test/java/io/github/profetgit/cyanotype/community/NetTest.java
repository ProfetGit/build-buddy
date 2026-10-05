package io.github.profetgit.cyanotype.community;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetTest {
    private static ApiException fails(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ApiException.class, e);
    }

    @Test
    void aGetSendsTheUserAgentAndNothingElseOfOurs() throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 200, FakeSite.listing(1, 1, 0)));
            site.net().getText("/api/v1/builds?sort=new");
            assertEquals("Cyanotype/test", site.userAgents.get(0));
            assertEquals("/api/v1/builds?sort=new", site.log.get(0));
        }
    }

    @Test
    void anAnswerIsKeptForItsMaxAgeAndThenAskedAboutWithItsEtag() throws Exception {
        try (FakeSite site = new FakeSite()) {
            AtomicLong now = new AtomicLong(1_000_000);
            Net net = new Net(site.base(), "Cyanotype/test", now::get, 5000, 5000);
            site.on(ex -> {
                if ("\"v1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                    FakeSite.send(ex, 304, new byte[0], "application/json", h -> h.set("ETag", "\"v1\""));
                } else {
                    FakeSite.send(ex, 200, FakeSite.listing(1, 1, 1, FakeSite.item("abcd1234", "House", "litematic")).getBytes(StandardCharsets.UTF_8), "application/json",
                        h -> {
                            h.set("ETag", "\"v1\"");
                            h.set("Cache-Control", "public, max-age=30, stale-while-revalidate=120");
                        });
                }
            });
            String first = net.getText("/api/v1/builds?page=1");
            assertEquals(1, net.requests.get());
            now.addAndGet(10_000);
            assertEquals(first, net.getText("/api/v1/builds?page=1"));
            assertEquals(1, net.requests.get(), "within max-age nothing is sent");
            now.addAndGet(25_000);
            assertEquals(first, net.getText("/api/v1/builds?page=1"));
            assertEquals(2, net.requests.get(), "after max-age one conditional request");
            assertEquals(1, site.conditional.size());
            assertTrue(site.conditional.get(0).endsWith("\"v1\""));
            now.addAndGet(10_000);
            net.getText("/api/v1/builds?page=1");
            assertEquals(2, net.requests.get(), "a 304 starts a new max-age");
            net.getText("/api/v1/builds?page=2");
            assertEquals(3, net.requests.get(), "another page is another question");
        }
    }

    @Test
    void noStoreAnswersAreNeverKept() throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 200, "{\"apiVersion\":1}".getBytes(StandardCharsets.UTF_8), "application/json", h -> h.set("Cache-Control", "no-store")));
            Net net = site.net();
            net.getText("/api/v1");
            net.getText("/api/v1");
            assertEquals(2, net.requests.get());
        }
    }

    @Test
    void statusCodesBecomeKindsWithWordsForThePlayer() throws Exception {
        try (FakeSite site = new FakeSite()) {
            Net net = site.net();
            site.on(ex -> FakeSite.send(ex, 404, "{\"apiVersion\":1,\"error\":{\"code\":\"not_found\",\"message\":\"No such build.\"}}"));
            ApiException nf = fails(() -> net.getText("/api/v1/builds/zzzz"));
            assertEquals(ApiException.Kind.NOT_FOUND, nf.kind);
            assertEquals("No such build.", nf.getMessage());
            assertFalse(nf.retryable());

            site.on(ex -> FakeSite.send(ex, 429, new byte[0], "application/json", h -> h.set("Retry-After", "42")));
            ApiException rl = fails(() -> net.getText("/api/v1/builds"));
            assertEquals(ApiException.Kind.RATE_LIMITED, rl.kind);
            assertEquals(42, rl.retryAfterSeconds);
            assertTrue(rl.forPlayer().contains("42"));
            assertTrue(rl.retryable());

            site.on(ex -> FakeSite.send(ex, 429, new byte[0], "application/json", h -> {
            }));
            assertEquals(60, fails(() -> net.getText("/api/v1/builds")).retryAfterSeconds, "no Retry-After means a minute");

            site.on(ex -> FakeSite.send(ex, 500, "<html>boom</html>"));
            ApiException sv = fails(() -> net.getText("/api/v1/builds"));
            assertEquals(ApiException.Kind.SERVER, sv.kind);
            assertEquals(500, sv.status);
            assertTrue(sv.retryable());

            site.on(ex -> FakeSite.send(ex, 400, "{\"apiVersion\":1,\"error\":{\"code\":\"bad_request\",\"message\":\"sort is new or popular.\"}}"));
            assertEquals(ApiException.Kind.BAD_REQUEST, fails(() -> net.getText("/api/v1/builds")).kind);
        }
    }

    @Test
    void aRedirectIsNotFollowed() throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 302, new byte[0], "text/plain", h -> h.set("Location", "http://example.invalid/elsewhere")));
            ApiException e = fails(() -> site.net().getText("/api/v1"));
            assertEquals(ApiException.Kind.REDIRECT, e.kind);
            assertEquals(1, site.log.size(), "nothing was fetched from the other address");
        }
    }

    @Test
    void anAnswerLargerThanTheCapIsDropped() throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 200, new byte[Net.TEXT_MAX + 10], "application/json", h -> {
            }));
            assertEquals(ApiException.Kind.TOO_BIG, fails(() -> site.net().getText("/api/v1/builds")).kind);
            // and one that does not say how long it is (chunked) is counted as it arrives
            site.on(ex -> {
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream out = ex.getResponseBody()) {
                    byte[] chunk = new byte[64 * 1024];
                    for (int i = 0; i < 20; i++) out.write(chunk);
                } catch (IOException gone) {
                    // the client hung up: that is the point
                }
            });
            assertEquals(ApiException.Kind.TOO_BIG, fails(() -> site.net().getText("/api/v1/builds")).kind);
        }
    }

    @Test
    void aSiteThatDoesNotAnswerTimesOut() throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    // test over
                }
            });
            Net net = new Net(site.base(), "Cyanotype/test", System::currentTimeMillis, 1000, 300);
            ApiException e = fails(() -> net.getText("/api/v1"));
            assertEquals(ApiException.Kind.TIMEOUT, e.kind);
            assertTrue(e.retryable());
        }
    }

    @Test
    void nobodyListeningMeansOffline() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ApiException e = fails(() -> new Net("http://localhost:" + port, "Cyanotype/test").getText("/api/v1"));
        assertEquals(ApiException.Kind.OFFLINE, e.kind);
        assertTrue(e.forPlayer().toLowerCase().contains("internet"));
    }

    @Test
    void anAddressThatDoesNotResolveIsOffline() {
        ApiException e = fails(() -> new Net("https://no-such-host.invalid", "Cyanotype/test").getText("/api/v1"));
        assertEquals(ApiException.Kind.OFFLINE, e.kind);
    }

    @Test
    void weStayUnderTheSitesRateLimitAndTellWhenToTryAgain() throws Exception {
        try (FakeSite site = new FakeSite()) {
            AtomicLong now = new AtomicLong(5_000_000);
            Net net = new Net(site.base(), "Cyanotype/test", now::get, 5000, 5000);
            site.on(ex -> FakeSite.send(ex, 200, "{\"apiVersion\":1}"));
            for (int i = 0; i < 100; i++) net.getText("/api/v1/builds?page=" + (i + 1));
            assertEquals(100, site.log.size());
            now.addAndGet(20_000);
            ApiException e = fails(() -> net.getText("/api/v1/builds?page=101"));
            assertEquals(ApiException.Kind.RATE_LIMITED, e.kind);
            assertEquals(40, e.retryAfterSeconds);
            assertEquals(100, site.log.size(), "the 101st call never left");
            now.addAndGet(41_000);
            net.getText("/api/v1/builds?page=101");
            assertEquals(101, site.log.size());
        }
    }

    @Test
    void aClosedNetSendsNothingMore(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 200, "{\"apiVersion\":1}"));
            Net net = site.net();
            net.getText("/api/v1");
            assertEquals(1, site.log.size());
            net.close();
            assertTrue(net.closed());
            assertEquals(ApiException.Kind.CANCELLED, fails(() -> net.getText("/api/v1")).kind, "not even what it has in its cache");
            assertEquals(ApiException.Kind.CANCELLED, fails(() -> net.getText("/api/v1/builds")).kind);
            assertEquals(ApiException.Kind.CANCELLED, fails(() -> net.getBytes("/media/abcd1234/thumb.png", Net.IMAGE_MAX, "image/png")).kind);
            assertEquals(ApiException.Kind.CANCELLED, fails(() -> net.downloadTo("/api/v1/builds/abcd1234/download", dir.resolve("x"), 100, (a, b) -> {
            }, () -> false)).kind);
            assertEquals(1, site.log.size(), "nothing left after close");
        }
    }

    @Test
    void picturesAreNotCountedAgainstTheApiLimit() throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 200, new byte[]{1, 2, 3}, "image/png", h -> {
            }));
            Net net = site.net();
            for (int i = 0; i < 130; i++) net.getBytes("/media/abcd" + i + "/thumb.png", Net.IMAGE_MAX, "image/png");
            assertEquals(130, site.log.size());
        }
    }

    @Test
    void onlyAddressesOnTheSiteWeUseAreFollowed() throws Exception {
        Net net = new Net("http://localhost:3150", "Cyanotype/test");
        assertEquals("/media/abc/thumb.png", net.localPath("http://localhost:3150/media/abc/thumb.png"));
        assertEquals("/media/abc/thumb.png?v=5", net.localPath("http://LOCALHOST:3150/media/abc/thumb.png?v=5"));
        assertEquals("/media/abc/thumb.png", net.localPath("/media/abc/thumb.png"));
        assertNull(net.localPath("http://evil.example/media/abc/thumb.png"));
        assertNull(net.localPath("http://localhost:9999/media/abc/thumb.png"));
        assertNull(net.localPath("https://localhost:3150/media/abc/thumb.png"));
        assertNull(net.localPath("http://user@localhost:3150/media/abc/thumb.png"));
        assertNull(net.localPath("media/abc/thumb.png"));
        assertNull(net.localPath(""));
        assertNull(net.localPath(null));
        Net sub = new Net("https://example.org/cyanotype", "Cyanotype/test");
        assertEquals("/media/a/thumb.png", sub.localPath("https://example.org/cyanotype/media/a/thumb.png"));
        assertNull(sub.localPath("https://example.org/other/media/a/thumb.png"));
        assertEquals("/media/a/thumb.png", sub.localPath("https://example.org:443/cyanotype/media/a/thumb.png"), "the default port is the same port");
    }

    @Test
    void aDownloadIsHashedWhileItArrivesAndReportsProgress(@TempDir Path dir) throws Exception {
        byte[] file = new byte[200_000];
        new java.util.Random(7).nextBytes(file);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 200, file, "application/octet-stream", h -> h.set("X-File-Sha256", sha)));
            long[] last = {0, 0};
            Net.Fetched f = site.net().downloadTo("/api/v1/builds/abcd1234/download", dir.resolve("x.part"), Net.DOWNLOAD_MAX, (got, all) -> {
                last[0] = got;
                last[1] = all;
            }, () -> false);
            assertEquals(file.length, f.bytes());
            assertEquals(sha, f.sha256());
            assertEquals(sha, f.headerSha256());
            assertArrayEquals(file, Files.readAllBytes(dir.resolve("x.part")));
            assertEquals(file.length, last[0]);
            assertEquals(file.length, last[1]);
        }
    }

    @Test
    void aDownloadStopsWhenAskedAndWhenTooBig(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 200, new byte[300_000], "application/octet-stream", h -> {
            }));
            AtomicBoolean stop = new AtomicBoolean();
            ApiException c = fails(() -> site.net().downloadTo("/api/v1/builds/abcd1234/download", dir.resolve("a.part"), Net.DOWNLOAD_MAX, (got, all) -> stop.set(true), stop::get));
            assertEquals(ApiException.Kind.CANCELLED, c.kind);
            ApiException big = fails(() -> site.net().downloadTo("/api/v1/builds/abcd1234/download", dir.resolve("b.part"), 100_000, (got, all) -> {
            }, () -> false));
            assertEquals(ApiException.Kind.TOO_BIG, big.kind);
        }
    }

    @Test
    void downloadsHaveTheirOwnSmallerLimit() throws Exception {
        try (FakeSite site = new FakeSite()) {
            AtomicLong now = new AtomicLong(9_000_000);
            Net net = new Net(site.base(), "Cyanotype/test", now::get, 5000, 5000);
            site.on(ex -> FakeSite.send(ex, 200, new byte[]{1}, "application/octet-stream", h -> {
            }));
            Path tmp = Files.createTempFile("dl", ".part");
            try {
                for (int i = 0; i < 25; i++) net.downloadTo("/api/v1/builds/abcd1234/download", tmp, 1000, (a, b) -> {
                }, () -> false);
                ApiException e = fails(() -> net.downloadTo("/api/v1/builds/abcd1234/download", tmp, 1000, (a, b) -> {
                }, () -> false));
                assertEquals(ApiException.Kind.RATE_LIMITED, e.kind);
                // the API bucket is separate: listing still works
                net.getText("/api/v1/builds");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
