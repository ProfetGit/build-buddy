package io.github.profetgit.cyanotype.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommunityModelTest {
    private static final String CATEGORIES = "{\"apiVersion\":1,\"categories\":[{\"id\":\"houses\",\"label\":\"Houses & buildings\",\"blurb\":\"\",\"builds\":2},"
        + "{\"id\":\"castles\",\"label\":\"Castles & landmarks\",\"blurb\":\"\",\"builds\":1}],\"sorts\":[{\"id\":\"new\",\"label\":\"Newest\"},{\"id\":\"popular\",\"label\":\"Most downloaded\"}]}";

    /** A site that lists two builds whatever is asked, and tells what was asked through {@code log}. */
    private static void listing(FakeSite site) {
        site.on(ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/v1/categories")) FakeSite.send(ex, 200, CATEGORIES);
            else if (path.startsWith("/media/")) FakeSite.send(ex, 200, new byte[]{1, 2, 3}, "image/png", h -> {
            });
            else if (path.equals("/api/v1/builds")) FakeSite.send(ex, 200, FakeSite.listing(1, 3, 30, FakeSite.item("abcd1234", "House", "litematic"), FakeSite.item("efgh5678", "Cabin", "nbt")));
            else FakeSite.send(ex, 404, "{\"apiVersion\":1,\"error\":{\"code\":\"not_found\",\"message\":\"no\"}}");
        });
    }

    private static CommunityModel model(FakeSite site, Path dir, Executor exec, AtomicLong clock) {
        return new CommunityModel(new CommunityClient(site.net()), exec, png -> {
            // the "picture" is 800 x 600 and its bytes are ignored
            int[] px = new int[800 * 600];
            java.util.Arrays.fill(px, 0xFF336699);
            return new CommunityModel.Pixels(800, 600, px);
        }, dir, f -> {
        }, clock::get);
    }

    private static long calls(FakeSite site, String prefix) {
        return site.log.stream().filter(s -> s.startsWith(prefix)).count();
    }

    @Test
    void startAsksForTheCategoriesAndTheFirstPage(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            assertNull(m.listing());
            m.start();
            assertFalse(m.loading());
            assertNull(m.error());
            assertEquals(2, m.listing().items().size());
            assertEquals(30, m.listing().total());
            assertEquals(2, m.categories().categories().size());
            assertTrue(site.log.contains("/api/v1/builds?sort=new&page=1&perPage=" + CommunityModel.PER_PAGE));
            assertEquals("Cyanotype/test", site.userAgents.get(0));
        }
    }

    @Test
    void typingWaitsForAPauseAndThenAsksOnce(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            AtomicLong now = new AtomicLong(1000);
            CommunityModel m = model(site, dir, Runnable::run, now);
            m.start();
            long before = calls(site, "/api/v1/builds");
            m.typed("c");
            now.addAndGet(100);
            m.tick();
            m.typed("ca");
            now.addAndGet(100);
            m.typed("castle");
            now.addAndGet(300);
            m.tick();
            assertEquals(before, calls(site, "/api/v1/builds"), "350 ms have not passed since the last letter");
            assertTrue(m.searchPending());
            now.addAndGet(60);
            m.tick();
            assertEquals(before + 1, calls(site, "/api/v1/builds"));
            assertFalse(m.searchPending());
            assertTrue(site.log.get(site.log.size() - 1).startsWith("/api/v1/builds?q=castle&"));
            m.tick();
            assertEquals(before + 1, calls(site, "/api/v1/builds"), "nothing more is asked");
        }
    }

    @Test
    void enterDoesNotWaitAndTypingWhatIsAlreadyShownAsksNothing(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            AtomicLong now = new AtomicLong(1000);
            CommunityModel m = model(site, dir, Runnable::run, now);
            m.start();
            m.typed("tower");
            m.submitSearch();
            assertEquals("tower", m.query().q());
            assertTrue(site.log.get(site.log.size() - 1).startsWith("/api/v1/builds?q=tower&"));
            long n = calls(site, "/api/v1/builds");
            m.typed("  tower ");
            assertFalse(m.searchPending(), "the same words, tidied, are the same search");
            m.typed("tower");
            now.addAndGet(1000);
            m.tick();
            assertEquals(n, calls(site, "/api/v1/builds"));
        }
    }

    @Test
    void anAnswerToAnOlderQuestionNeverReplacesTheNewerOne(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> {
                String q = ex.getRequestURI().getRawQuery();
                String title = q.contains("category=castles") ? "Castle" : "Everything";
                FakeSite.send(ex, 200, FakeSite.listing(1, 1, 1, FakeSite.item("abcd1234", title, "litematic")));
            });
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            CommunityModel m = model(site, dir, queue::add, new AtomicLong());
            m.load();
            m.setCategory("castles");
            assertTrue(m.loading());
            assertEquals(2, queue.size());
            Runnable first = queue.poll(), second = queue.poll();
            second.run();
            assertEquals("Castle", m.listing().items().get(0).title());
            assertFalse(m.loading());
            first.run();
            assertEquals("Castle", m.listing().items().get(0).title(), "the slow first answer arrived late and was dropped");
            assertFalse(m.loading());
        }
    }

    @Test
    void aFailureIsKeptUntilRetryAndRetryWorks(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 500, "<html>boom</html>"));
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            m.load();
            assertEquals(ApiException.Kind.SERVER, m.error().kind);
            assertFalse(m.loading());
            assertNull(m.listing());
            listing(site);
            m.load();
            assertNull(m.error());
            assertEquals(2, m.listing().items().size());
        }
    }

    @Test
    void offlineIsAStateNotACrash(@TempDir Path dir) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        CommunityModel m = new CommunityModel(new CommunityClient(new Net("http://localhost:" + port, "Cyanotype/test")), Runnable::run, png -> null, dir, f -> {
        }, () -> 0);
        m.start();
        assertEquals(ApiException.Kind.OFFLINE, m.error().kind);
        assertFalse(m.loading());
        assertNull(m.categories());
    }

    @Test
    void aSlowDownFromTheSiteIsShownWithItsWait(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 429, new byte[0], "application/json", h -> h.set("Retry-After", "17")));
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            m.load();
            assertEquals(ApiException.Kind.RATE_LIMITED, m.error().kind);
            assertEquals(17, m.error().retryAfterSeconds);
        }
    }

    @Test
    void pagingStaysInsideWhatTheSiteHas(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            m.start();
            m.goToPage(2);
            assertEquals(2, m.query().page());
            assertTrue(site.log.get(site.log.size() - 1).contains("page=2"));
            m.goToPage(99);
            assertEquals(3, m.query().page(), "the listing says there are 3 pages");
            m.goToPage(0);
            assertEquals(1, m.query().page());
            m.goToPage(3);
            m.setCategory("castles");
            assertEquals(1, m.query().page(), "a new filter starts at the first page");
            m.setSort("popular");
            assertTrue(site.log.get(site.log.size() - 1).contains("sort=popular"));
            assertTrue(site.log.get(site.log.size() - 1).contains("category=castles"));
            long n = calls(site, "/api/v1/builds");
            m.setSort("popular");
            assertEquals(n, calls(site, "/api/v1/builds"), "choosing what is already chosen asks nothing");
        }
    }

    @Test
    void aPreviewIsFetchedOnceShrunkAndTheSitesOwnAddressIsIgnored(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            m.start();
            Api.Build b = m.listing().items().get(0);
            assertEquals("http://example.invalid/media/abcd1234/thumb.png", b.thumbnailUrl(), "the answer points at another host");
            CommunityModel.Thumb t = m.thumbFor(b);
            assertNotNull(t.pixels);
            assertEquals(192, t.pixels.w());
            assertEquals(144, t.pixels.h());
            assertEquals(192 * 144, t.pixels.argb().length);
            assertSame(t, m.thumbFor(b));
            assertEquals(1, calls(site, "/media/"), "one request however often it is asked for");
            assertEquals("/media/abcd1234/thumb.png", site.log.stream().filter(s -> s.startsWith("/media/")).findFirst().orElseThrow(), "fetched from the site we use, by the id");
        }
    }

    private static void assertSame(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertSame(a, b);
    }

    @Test
    void aPreviewThatCannotBeReadIsMarkedFailed(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            CommunityModel m = new CommunityModel(new CommunityClient(site.net()), Runnable::run, png -> {
                throw new java.io.IOException("not a PNG");
            }, dir, f -> {
            }, () -> 0);
            m.start();
            CommunityModel.Thumb t = m.thumbFor(m.listing().items().get(0));
            assertTrue(t.failed);
            assertNull(t.pixels);
        }
    }

    @Test
    void theDetailArrivesAndAnOldCopyIsNoticed(@TempDir Path dir) throws Exception {
        byte[] file = DownloadsTest.gz("blueprint");
        try (FakeSite site = new FakeSite()) {
            String detail = "{\"apiVersion\":1," + FakeSite.item("abcd1234", "House", "litematic").substring(1).replaceFirst("\"bytes\":657", "\"bytes\":" + file.length).replaceFirst("}$", "")
                + ",\"description\":\"A house.\",\"fileSha256\":\"" + DownloadsTest.sha(file) + "\",\"materials\":[{\"id\":\"minecraft:stone\",\"name\":\"Stone\",\"count\":9}]}";
            site.on(ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/download")) FakeSite.send(ex, 200, file, "application/octet-stream", h -> {
                });
                else if (path.equals("/api/v1/builds/abcd1234")) FakeSite.send(ex, 200, detail);
                else FakeSite.send(ex, 200, FakeSite.listing(1, 1, 1, FakeSite.item("abcd1234", "House", "litematic")));
            });
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            m.start();
            Api.Build b = m.listing().items().get(0);
            assertFalse(CommunityModel.canDownload(null));
            m.open(b);
            CommunityModel.DetailState d = m.detail();
            assertNotNull(d.data);
            assertEquals("A house.", d.data.description());
            assertEquals(1, d.data.materials().size());
            assertNull(d.existing, "nothing downloaded yet");
            assertNotNull(d.big.pixels);
            assertTrue(CommunityModel.canDownload(d));

            m.download();
            assertEquals(CommunityModel.DownloadPhase.DONE, d.download);
            assertEquals("House.litematic", d.saved.getFileName().toString());
            assertArrayEq(file, Files.readAllBytes(d.saved));
            assertEquals(file.length, d.received);

            // opened again later (or after a restart): the copy is found by the tags file
            m.open(b);
            assertEquals(d.saved, m.detail().existing);
            m.download();
            assertEquals("House 2.litematic", m.detail().saved.getFileName().toString(), "downloading again makes a second file");
            assertArrayEq(file, Files.readAllBytes(dir.resolve("House.litematic")));
        }
    }

    private static void assertArrayEq(byte[] a, byte[] b) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(a, b);
    }

    @Test
    void aBuildInAnotherFormatCannotBeDownloadedHere(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            site.on(ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.equals("/api/v1/builds/efgh5678")) {
                    FakeSite.send(ex, 200, "{\"apiVersion\":1," + FakeSite.item("efgh5678", "Cabin", "nbt").substring(1).replaceFirst("}$", "") + ",\"description\":\"\",\"fileSha256\":\"\",\"materials\":[]}");
                } else {
                    FakeSite.send(ex, 200, FakeSite.listing(1, 1, 1, FakeSite.item("efgh5678", "Cabin", "nbt")));
                }
            });
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            m.start();
            m.open(m.listing().items().get(0));
            assertNotNull(m.detail().data);
            assertFalse(CommunityModel.canDownload(m.detail()));
            m.download();
            assertEquals(CommunityModel.DownloadPhase.IDLE, m.detail().download);
            assertEquals(0, calls(site, "/api/v1/builds/efgh5678/download"));
        }
    }

    @Test
    void aMissingBuildShowsItsError(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> {
                if (ex.getRequestURI().getPath().startsWith("/api/v1/builds/")) FakeSite.send(ex, 404, "{\"apiVersion\":1,\"error\":{\"code\":\"not_found\",\"message\":\"No such build.\"}}");
                else FakeSite.send(ex, 200, FakeSite.listing(1, 1, 1, FakeSite.item("abcd1234", "House", "litematic")));
            });
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            m.start();
            m.open(m.listing().items().get(0));
            assertNull(m.detail().data);
            assertEquals(ApiException.Kind.NOT_FOUND, m.detail().error.kind);
            assertFalse(CommunityModel.canDownload(m.detail()));
            m.close();
            assertNull(m.detail());
        }
    }

    @Test
    void thePictureCacheForgetsTheOldest(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            CommunityModel m = model(site, dir, Runnable::run, new AtomicLong());
            m.start();
            Api.Build b = m.listing().items().get(0);
            CommunityModel.Thumb first = m.thumbFor(b);
            for (int i = 0; i < CommunityModel.KEEP_THUMBS + 5; i++) {
                String id = "zz" + String.format("%06d", i);
                m.thumbFor(new Api.Build(id, "t", "a", "", "", "", 1, 1, 1, 1, "", "", "", 0, 0, 0, null, "", "x.litematic", "litematic", 1, "", "", ""));
            }
            assertTrue(m.thumbFor(b) != first, "the first picture was dropped and is fetched again");
        }
    }

    @Test
    void aModelThatWasShutDownDoesNothingWithWhatWasQueued(@TempDir Path dir) throws Exception {
        try (FakeSite site = new FakeSite()) {
            listing(site);
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            CommunityModel m = model(site, dir, queue::add, new AtomicLong());
            m.start();
            Api.Build b = new Api.Build("abcd1234", "t", "a", "", "", "", 1, 1, 1, 1, "", "", "", 0, 0, 0, null, "", "x.litematic", "litematic", 1, "", "", "");
            m.thumbFor(b);
            m.open(b);
            assertTrue(queue.size() >= 4, "work is waiting: " + queue.size());
            m.shutdown();
            while (!queue.isEmpty()) queue.poll().run();
            assertEquals(0, site.log.size(), "the player switched it off: not one request");
            m.load();
            m.setCategory("castles");
            assertTrue(queue.isEmpty(), "and nothing new is queued");
        }
    }

    @Test
    void theModelNeverCallsTheNetworkOnItsOwnThread() {
        // every call goes through the executor it was given: with an executor that never runs anything, nothing is sent
        List<Runnable> held = new java.util.ArrayList<>();
        Net net = new Net("http://localhost:1", "x");
        CommunityModel m = new CommunityModel(new CommunityClient(net), held::add, png -> null, Path.of("."), f -> {
        }, () -> 0);
        m.start();
        m.typed("a");
        m.submitSearch();
        assertTrue(m.loading());
        assertEquals(0, net.requests.get());
        assertFalse(held.isEmpty());
    }
}
