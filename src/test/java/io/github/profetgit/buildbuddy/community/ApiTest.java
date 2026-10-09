package io.github.profetgit.buildbuddy.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApiTest {
    // a page of the real API, trimmed (copied from /api/v1/builds on the dev site, 2026-10-05)
    private static final String REAL_LISTING = "{\"apiVersion\":1,\"page\":1,\"perPage\":2,\"total\":17,\"pages\":9,\"items\":["
        + "{\"id\":\"tgpuvc5u\",\"title\":\"Spruce cabin structure\",\"author\":\"polish-tester\",\"category\":\"houses\",\"categoryLabel\":\"Houses & buildings\",\"summary\":\"\","
        + "\"size\":{\"x\":9,\"y\":6,\"z\":7},\"blockCount\":155,\"sizeClass\":\"tiny\",\"sizeLabel\":\"Tiny\",\"minecraftVersion\":\"1.21.1\",\"dataVersion\":3955,\"downloads\":0,"
        + "\"favorites\":0,\"remixOf\":null,\"createdAt\":\"2026-10-05T10:22:01.247Z\",\"updatedAt\":\"2026-10-05T10:22:01.247Z\","
        + "\"file\":{\"name\":\"spruce-cabin-structure.nbt\",\"extension\":\"nbt\",\"bytes\":657},\"thumbnailUrl\":\"http://localhost:3150/media/tgpuvc5u/thumb.png\","
        + "\"pageUrl\":\"http://localhost:3150/builds/tgpuvc5u\",\"downloadUrl\":\"http://localhost:3150/api/v1/builds/tgpuvc5u/download\"},"
        + "{\"id\":\"4t59fxtc\",\"title\":\"Small Sailboat\",\"author\":\"Build Buddy-Samples\",\"category\":\"ships\",\"categoryLabel\":\"Ships & vehicles\",\"summary\":\"A sailboat\","
        + "\"size\":{\"x\":10,\"y\":12,\"z\":5},\"blockCount\":603,\"sizeClass\":\"small\",\"sizeLabel\":\"Small\",\"minecraftVersion\":\"26.3\",\"dataVersion\":5023,\"downloads\":1,"
        + "\"favorites\":2,\"remixOf\":\"abcd1234\",\"createdAt\":\"2026-10-05T10:00:00.000Z\",\"updatedAt\":\"2026-10-05T10:00:00.000Z\","
        + "\"file\":{\"name\":\"boat.litematic\",\"extension\":\"litematic\",\"bytes\":637},\"thumbnailUrl\":\"http://localhost:3150/media/4t59fxtc/thumb.png\","
        + "\"pageUrl\":\"http://localhost:3150/builds/4t59fxtc\",\"downloadUrl\":\"http://localhost:3150/api/v1/builds/4t59fxtc/download\",\"somethingNew\":{\"a\":1}}]}";

    @Test
    void aRealListingIsRead() throws ApiException {
        Api.Listing l = Api.listing(REAL_LISTING);
        assertEquals(1, l.page());
        assertEquals(9, l.pages());
        assertEquals(17, l.total());
        assertEquals(2, l.items().size());
        Api.Build a = l.items().get(0), b = l.items().get(1);
        assertEquals("tgpuvc5u", a.id());
        assertEquals("Spruce cabin structure", a.title());
        assertEquals("9x6x7", a.sizeText());
        assertEquals("Tiny", a.sizeLabel());
        assertEquals(155, a.blockCount());
        assertFalse(a.canOpen(), "an .nbt build cannot be opened by this version");
        assertNull(a.remixOf());
        assertTrue(b.canOpen());
        assertEquals("abcd1234", b.remixOf());
        assertEquals(2, b.favorites());
        assertEquals(637, b.fileBytes());
        assertEquals("Houses & buildings", a.categoryLabel());
    }

    @Test
    void fieldsMayBeAddedAndOptionalOnesMayBeMissing() throws ApiException {
        Api.Listing l = Api.listing("{\"apiVersion\":1,\"items\":[{\"id\":\"abcd1234\",\"title\":\"Bare\"}],\"extra\":true}");
        assertEquals(1, l.items().size());
        Api.Build b = l.items().get(0);
        assertEquals("Bare", b.title());
        assertEquals(0, b.downloads());
        assertEquals(-1, b.fileBytes(), "an unknown size is -1, never trusted");
        assertEquals(1, l.pages());
        assertEquals(1, l.total());
    }

    @Test
    void anEmptyPageIsFine() throws ApiException {
        Api.Listing l = Api.listing(FakeSite.listing(1, 1, 0));
        assertTrue(l.items().isEmpty());
        assertEquals(0, l.total());
    }

    @Test
    void answersThatAreNotTheApiAreRefused() {
        assertEquals(ApiException.Kind.BAD_RESPONSE, assertThrows(ApiException.class, () -> Api.listing("<html>oops</html>")).kind);
        assertEquals(ApiException.Kind.BAD_RESPONSE, assertThrows(ApiException.class, () -> Api.listing("[1,2]")).kind);
        assertEquals(ApiException.Kind.BAD_RESPONSE, assertThrows(ApiException.class, () -> Api.listing("{\"items\":[]}")).kind);
        assertEquals(ApiException.Kind.BAD_RESPONSE, assertThrows(ApiException.class, () -> Api.listing("{\"apiVersion\":1}")).kind);
        assertEquals(ApiException.Kind.BAD_RESPONSE, assertThrows(ApiException.class, () -> Api.listing("{\"apiVersion\":1,\"items\":[{\"title\":\"no id\"}]}")).kind);
        assertEquals(ApiException.Kind.BAD_RESPONSE, assertThrows(ApiException.class, () -> Api.listing("{\"apiVersion\":1,\"items\":[{\"id\":\"../../etc\",\"title\":\"x\"}]}")).kind);
    }

    @Test
    void aNewerApiVersionSaysTheModIsOld() {
        ApiException e = assertThrows(ApiException.class, () -> Api.listing("{\"apiVersion\":2,\"items\":[]}"));
        assertEquals(ApiException.Kind.TOO_NEW, e.kind);
        assertTrue(e.forPlayer().contains("Update"));
    }

    @Test
    void aDetailCarriesDescriptionChecksumAndMaterials() throws ApiException {
        String json = FakeSite.item("abcd1234", "House", "litematic").replaceFirst("}$", "") + ",\"description\":\"Line one\\nLine two\",\"fileSha256\":\"" + "ab".repeat(32)
            + "\",\"materials\":[{\"id\":\"minecraft:spruce_planks\",\"name\":\"Spruce Planks\",\"count\":82},{\"id\":\"minecraft:glass_pane\",\"name\":\"Glass Pane\",\"count\":1},"
            + "{\"id\":\"x\",\"count\":4},\"junk\"]}";
        Api.Detail d = Api.detail("{\"apiVersion\":1," + json.substring(1));
        assertEquals("Line one\nLine two", d.description());
        assertEquals("ab".repeat(32), d.sha256());
        assertEquals(3, d.materials().size());
        assertEquals("Spruce Planks", d.materials().get(0).name());
        assertEquals(82, d.materials().get(0).count());
        assertEquals("x", d.materials().get(2).name(), "a material with no name falls back to its id");
    }

    @Test
    void aBadChecksumIsDroppedNotTrusted() throws ApiException {
        String json = "{\"apiVersion\":1," + FakeSite.item("abcd1234", "House", "litematic").substring(1).replaceFirst("}$", "") + ",\"fileSha256\":\"nothex\"}";
        assertEquals("", Api.detail(json).sha256());
    }

    @Test
    void textFromTheSiteHasNoControlCharacters() throws ApiException {
        Api.Listing l = Api.listing("{\"apiVersion\":1,\"items\":[{\"id\":\"abcd1234\",\"title\":\"Tab\\there\\u0007 and\\nnewline\",\"author\":\"  spaced   out  \"}]}");
        assertEquals("Tab here and newline", l.items().get(0).title());
        assertEquals("spaced out", l.items().get(0).author());
    }

    @Test
    void categoriesGiveChipNamesAndKnownSorts() throws ApiException {
        Api.Categories c = Api.categories("{\"apiVersion\":1,\"categories\":[{\"id\":\"houses\",\"label\":\"Houses & buildings\",\"blurb\":\"Homes\",\"builds\":4},"
            + "{\"id\":\"other\",\"label\":\"Something else\",\"blurb\":\"\",\"builds\":0},{\"id\":\"BAD ID\",\"label\":\"x\"},{\"id\":\"pixel\",\"label\":\"Pixel art\",\"builds\":1}],"
            + "\"sorts\":[{\"id\":\"new\",\"label\":\"Newest\"},{\"id\":\"popular\",\"label\":\"Most downloaded\"},{\"id\":\"weird\",\"label\":\"?\"}]}");
        assertEquals(3, c.categories().size(), "an id that is not a slug is skipped");
        assertEquals("Houses", c.categories().get(0).chip());
        assertEquals("Other", c.categories().get(1).chip());
        assertEquals("Pixel art", c.categories().get(2).chip());
        assertEquals(4, c.categories().get(0).builds());
        assertEquals(2, c.sorts().size(), "only the sorts the API documents");
    }

    @Test
    void errorAnswersGiveTheirMessage() {
        assertEquals("No such build.", Api.errorMessage("{\"apiVersion\":1,\"error\":{\"code\":\"not_found\",\"message\":\"No such build.\"}}"));
        assertNull(Api.errorMessage("<html>"));
        assertNull(Api.errorMessage("{}"));
    }

    // ---- what is asked

    @Test
    void aQueryIsEncodedAndBroughtIntoRange() {
        assertEquals("sort=new&page=1&perPage=12", Api.Query.first(12).queryString());
        Api.Query q = Api.Query.first(12).withText("  big   castle & tower?  ").withCategory("castles").withSort("popular").withPage(3);
        assertEquals("q=big%20castle%20%26%20tower%3F&category=castles&sort=popular&page=3&perPage=12", q.queryString());
        assertEquals("q=k%C3%A4yt%C3%A4%20talo&sort=new&page=1&perPage=12", Api.Query.first(12).withText("käytä talo").queryString());
    }

    @Test
    void outOfRangeValuesNeverReachTheSite() {
        Api.Query q = new Api.Query("x".repeat(200), "../etc", "bogus", 0, 500);
        assertEquals(Api.MAX_QUERY, q.q().length());
        assertEquals("", q.category());
        assertEquals(Api.Query.NEW, q.sort());
        assertEquals(1, q.page());
        assertEquals(Api.MAX_PER_PAGE, q.perPage());
        assertEquals(Api.MAX_PAGE, new Api.Query("", "", "new", 99999, 12).page());
        assertEquals(1, new Api.Query("", "", "new", 1, 0).perPage());
    }

    @Test
    void changingTheSearchStartsOnPageOne() {
        Api.Query q = Api.Query.first(12).withPage(4);
        assertEquals(1, q.withText("castle").page());
        assertEquals(1, q.withCategory("farms").page());
        assertEquals(1, q.withSort("popular").page());
        assertEquals(4, q.page());
    }

    @Test
    void buildIdsAreChecked() {
        assertTrue(Api.validId("tgpuvc5u"));
        assertFalse(Api.validId("../x"));
        assertFalse(Api.validId("UPPER"));
        assertFalse(Api.validId(""));
        assertFalse(Api.validId(null));
        assertFalse(Api.validId("a/b/c/d/e"));
    }
}
