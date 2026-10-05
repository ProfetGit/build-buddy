package io.github.profetgit.cyanotype.community;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.profetgit.cyanotype.TestBootstrap;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.LitematicReader;
import io.github.profetgit.cyanotype.blueprint.LitematicWriter;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadsTest {
    private static final Downloads.Validator ACCEPT = f -> {
    };

    static byte[] gz(String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(bytes)) {
            z.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** The detail answer for a build whose file is {@code file}. */
    static Api.Detail detailFor(String id, String title, String ext, byte[] file, String sha) throws ApiException {
        String item = FakeSite.item(id, title, ext).replaceFirst("\"bytes\":657", "\"bytes\":" + file.length).replaceFirst("}$", "")
            + ",\"description\":\"d\",\"fileSha256\":\"" + sha + "\",\"materials\":[]}";
        return Api.detail("{\"apiVersion\":1," + item.substring(1));
    }

    private static void serve(FakeSite site, byte[] file) {
        site.on(ex -> FakeSite.send(ex, 200, file, "application/octet-stream", h -> {
        }));
    }

    private static Downloads.Result save(FakeSite site, Api.Detail d, Path dir, Downloads.Validator v) throws ApiException {
        return Downloads.save(site.net(), d, dir, v, (a, b) -> {
        }, () -> false);
    }

    private static Set<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    @Test
    void aBuildLandsInTheFolderAsAnOrdinaryFile(@TempDir Path dir) throws Exception {
        byte[] file = gz("a schematic");
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            Downloads.Result r = save(site, detailFor("abcd1234", "Cozy Cottage", "litematic", file, sha(file)), dir, ACCEPT);
            assertEquals("Cozy Cottage.litematic", r.file().getFileName().toString());
            assertEquals(dir, r.file().getParent());
            assertArrayEquals(file, Files.readAllBytes(r.file()));
            assertEquals(file.length, r.bytes());
            assertEquals(Set.of("Cozy Cottage.litematic", "Cozy Cottage.cyanotype.json"), names(dir), "no temporary file is left");
            assertEquals("/api/v1/builds/abcd1234/download", site.log.get(0), "the address is built from the id, not taken from the answer");
        }
    }

    @Test
    void theTagsFileSaysWhereItCameFrom(@TempDir Path dir) throws Exception {
        byte[] file = gz("x");
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            Downloads.Result r = save(site, detailFor("abcd1234", "Cozy Cottage", "litematic", file, sha(file)), dir, ACCEPT);
            JsonObject side = JsonParser.parseString(Files.readString(dir.resolve("Cozy Cottage.cyanotype.json"))).getAsJsonObject();
            assertEquals(List.of("community", "houses"), side.getAsJsonArray("tags").asList().stream().map(e -> e.getAsString()).toList());
            assertEquals("abcd1234", side.getAsJsonObject("source").get("id").getAsString());
            assertEquals(site.base(), side.getAsJsonObject("source").get("site").getAsString());
            assertEquals(r.file(), Downloads.existingCopy(dir, site.base(), "abcd1234"));
            assertNull(Downloads.existingCopy(dir, site.base(), "other123"));
            assertNull(Downloads.existingCopy(dir, "https://another.example", "abcd1234"), "another site's build with the same id is not this one");
            assertNull(Downloads.existingCopy(dir.resolve("missing"), site.base(), "abcd1234"));
        }
    }

    @Test
    void aFileThatIsAlreadyThereIsNeverReplaced(@TempDir Path dir) throws Exception {
        byte[] mine = "my own work, not even gzip".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("Cozy Cottage.litematic"), mine);
        byte[] file = gz("downloaded");
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            Api.Detail d = detailFor("abcd1234", "Cozy Cottage", "litematic", file, sha(file));
            Path second = save(site, d, dir, ACCEPT).file();
            assertEquals("Cozy Cottage 2.litematic", second.getFileName().toString());
            Path third = save(site, d, dir, ACCEPT).file();
            assertEquals("Cozy Cottage 3.litematic", third.getFileName().toString());
            assertArrayEquals(mine, Files.readAllBytes(dir.resolve("Cozy Cottage.litematic")), "the first file is byte for byte what it was");
            assertArrayEquals(file, Files.readAllBytes(second));
            assertEquals(Set.of("Cozy Cottage.litematic", "Cozy Cottage 2.litematic", "Cozy Cottage 2.cyanotype.json", "Cozy Cottage 3.litematic", "Cozy Cottage 3.cyanotype.json"), names(dir));
        }
    }

    @Test
    void aNameThatDiffersOnlyInCaseCountsAsTaken(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("cozy cottage.litematic"), "mine");
        byte[] file = gz("x");
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            Path p = save(site, detailFor("abcd1234", "Cozy Cottage", "litematic", file, sha(file)), dir, ACCEPT).file();
            assertEquals("Cozy Cottage 2.litematic", p.getFileName().toString());
            assertEquals("mine", Files.readString(dir.resolve("cozy cottage.litematic")));
        }
    }

    @Test
    void aLeftOverTagsFileDoesNotMakeTheNameFree(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Cozy Cottage.cyanotype.json"), "{\"tags\":[\"mine\"]}");
        byte[] file = gz("x");
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            Path p = save(site, detailFor("abcd1234", "Cozy Cottage", "litematic", file, sha(file)), dir, ACCEPT).file();
            assertEquals("Cozy Cottage 2.litematic", p.getFileName().toString());
            assertEquals("{\"tags\":[\"mine\"]}", Files.readString(dir.resolve("Cozy Cottage.cyanotype.json")), "someone's tags are not overwritten either");
        }
    }

    @org.junit.jupiter.api.RepeatedTest(25)
    void twoDownloadsAtOnceBothSurvive(@TempDir Path dir) throws Exception {
        byte[] a = gz("first"), b = gz("second one");
        try (FakeSite site = new FakeSite(); ExecutorService pool = Executors.newFixedThreadPool(2)) {
            site.on(ex -> FakeSite.send(ex, 200, ex.getRequestURI().getPath().contains("aaaa1111") ? a : b, "application/octet-stream", h -> {
            }));
            Api.Detail da = detailFor("aaaa1111", "Same name", "litematic", a, sha(a)), db = detailFor("bbbb2222", "Same name", "litematic", b, sha(b));
            CountDownLatch go = new CountDownLatch(1);
            Future<Path> fa = pool.submit(() -> {
                go.await();
                return save(site, da, dir, ACCEPT).file();
            });
            Future<Path> fb = pool.submit(() -> {
                go.await();
                return save(site, db, dir, ACCEPT).file();
            });
            go.countDown();
            Path pa = fa.get(), pb = fb.get();
            assertNotEquals(pa, pb);
            assertArrayEquals(a, Files.readAllBytes(pa));
            assertArrayEquals(b, Files.readAllBytes(pb));
        }
    }

    @Test
    void hostileTitlesStayInsideTheFolder(@TempDir Path root) throws Exception {
        Path dir = root.resolve("blueprints");
        byte[] file = gz("x");
        String[] titles = {"../../etc/passwd", "..\\..\\evil", "/absolute/path", "C:\\Windows\\x", "CON", "nul.txt", "a<b>c:d|e?f*g", "....", "   ", "\u202Eexe.litematic", "x".repeat(300),
            "name\u0000with\nnull"};
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            for (String t : titles) {
                Path p = save(site, detailFor("abcd1234", t, "litematic", file, sha(file)), dir, ACCEPT).file();
                assertEquals(dir.toAbsolutePath(), p.toAbsolutePath().getParent(), "title " + t);
                String n = p.getFileName().toString();
                assertTrue(n.endsWith(".litematic"), n);
                assertFalse(n.contains("/") || n.contains("\\") || n.contains(":") || n.contains("\u0000") || n.contains("\n"), n);
                assertFalse(n.startsWith("."), n);
                assertTrue(n.length() <= BlueprintSaverLimits.MAX_NAME, n);
                assertFalse(n.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"), "a Windows device name: " + n);
            }
            try (Stream<Path> s = Files.walk(root)) {
                assertTrue(s.filter(Files::isRegularFile).allMatch(p -> p.startsWith(dir)), "nothing was written outside the folder");
            }
        }
    }

    /** The longest file name the stem rules can make: stem + " 99" + ".litematic". */
    private static final class BlueprintSaverLimits {
        static final int MAX_NAME = io.github.profetgit.cyanotype.placement.BlueprintSaver.MAX_STEM + 4 + ".litematic".length();
    }

    @Test
    void aWrongChecksumLeavesNothingBehind(@TempDir Path dir) throws Exception {
        byte[] file = gz("real");
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            ApiException e = assertThrows(ApiException.class, () -> save(site, detailFor("abcd1234", "House", "litematic", file, "0".repeat(64)), dir, ACCEPT));
            assertEquals(ApiException.Kind.CHECKSUM, e.kind);
            assertEquals(Set.of(), names(dir));
        }
    }

    @Test
    void aFileOfTheWrongSizeLeavesNothingBehind(@TempDir Path dir) throws Exception {
        byte[] file = gz("real"), other = gz("a longer file than announced");
        try (FakeSite site = new FakeSite()) {
            serve(site, other);
            ApiException e = assertThrows(ApiException.class, () -> save(site, detailFor("abcd1234", "House", "litematic", file, ""), dir, ACCEPT));
            assertEquals(ApiException.Kind.CHECKSUM, e.kind);
            assertEquals(Set.of(), names(dir));
        }
    }

    @Test
    void theHeaderChecksumIsCheckedToo(@TempDir Path dir) throws Exception {
        byte[] file = gz("real");
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 200, file, "application/octet-stream", h -> h.set("X-File-Sha256", "f".repeat(64))));
            ApiException e = assertThrows(ApiException.class, () -> save(site, detailFor("abcd1234", "House", "litematic", file, ""), dir, ACCEPT));
            assertEquals(ApiException.Kind.CHECKSUM, e.kind);
            assertEquals(Set.of(), names(dir));
        }
    }

    @Test
    void somethingThatIsNotGzipIsRefused(@TempDir Path dir) throws Exception {
        byte[] file = "<html>login required</html>".getBytes(StandardCharsets.UTF_8);
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            ApiException e = assertThrows(ApiException.class, () -> save(site, detailFor("abcd1234", "House", "litematic", file, sha(file)), dir, ACCEPT));
            assertEquals(ApiException.Kind.UNREADABLE, e.kind);
            assertEquals(Set.of(), names(dir));
        }
    }

    @Test
    void aFileTheReaderRejectsIsNotKept(@TempDir Path dir) throws Exception {
        byte[] file = gz("gzip but not a litematic");
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            ApiException e = assertThrows(ApiException.class,
                () -> save(site, detailFor("abcd1234", "House", "litematic", file, sha(file)), dir, f -> {
                    throw new IOException("not a litematic: no Regions");
                }));
            assertEquals(ApiException.Kind.UNREADABLE, e.kind);
            assertTrue(e.getMessage().contains("no Regions"));
            assertEquals(Set.of(), names(dir));
        }
    }

    @Test
    void onlyLitematicIsDownloadedAndNothingIsAskedForTheRest(@TempDir Path dir) throws Exception {
        byte[] file = gz("x");
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            for (String ext : new String[]{"schem", "nbt", "", "litematic.exe"}) {
                ApiException e = assertThrows(ApiException.class, () -> save(site, detailFor("abcd1234", "House", ext, file, sha(file)), dir, ACCEPT), ext);
                assertEquals(ApiException.Kind.UNREADABLE, e.kind);
            }
            assertEquals(0, site.log.size(), "no request for a file that cannot be opened");
            assertEquals(Set.of(), names(dir));
        }
    }

    @Test
    void aFailedRequestLeavesNothingBehind(@TempDir Path dir) throws Exception {
        byte[] file = gz("x");
        try (FakeSite site = new FakeSite()) {
            site.on(ex -> FakeSite.send(ex, 404, "{\"apiVersion\":1,\"error\":{\"code\":\"not_found\",\"message\":\"gone\"}}"));
            ApiException e = assertThrows(ApiException.class, () -> save(site, detailFor("abcd1234", "House", "litematic", file, sha(file)), dir, ACCEPT));
            assertEquals(ApiException.Kind.NOT_FOUND, e.kind);
            assertEquals(Set.of(), names(dir));
        }
    }

    @Test
    void aCancelledDownloadLeavesNothingBehind(@TempDir Path dir) throws Exception {
        byte[] file = new byte[400_000];
        file[0] = 0x1f;
        file[1] = (byte) 0x8b;
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            ApiException e = assertThrows(ApiException.class,
                () -> Downloads.save(site.net(), detailFor("abcd1234", "House", "litematic", file, ""), dir, ACCEPT, (a, b) -> {
                }, () -> true));
            assertEquals(ApiException.Kind.CANCELLED, e.kind);
            assertEquals(Set.of(), names(dir));
        }
    }

    @Test
    void aRealBlueprintFromOurWriterGoesThroughTheRealReader(@TempDir Path dir, @TempDir Path source) throws Exception {
        TestBootstrap.init();
        PaletteEntry[] palette = {PaletteEntry.AIR, PaletteEntry.of(Blocks.STONE.defaultBlockState())};
        Region r = new Region("r", 0, 0, 0, 2, 1, 1, palette, new short[]{1, 0}, List.of());
        Path written = source.resolve("w.litematic");
        LitematicWriter.write(new Blueprint(Blueprint.Metadata.of("Tiny"), List.of(r)), written);
        byte[] file = Files.readAllBytes(written);
        Downloads.Validator real = f -> {
            try (InputStream in = Files.newInputStream(f)) {
                LitematicReader.read(in, f.getFileName().toString(), false);
            }
        };
        try (FakeSite site = new FakeSite()) {
            serve(site, file);
            Path saved = save(site, detailFor("abcd1234", "Tiny", "litematic", file, sha(file)), dir, real).file();
            assertEquals(1, LitematicReader.read(saved).regions.size());
        }
    }
}
