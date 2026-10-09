package io.github.profetgit.buildbuddy.community;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The community site's read-only API v1 (see the site's CLAUDE.md, "API v1"): what a question looks like and what an answer
 * is read into. Parsing is forgiving about fields that may be added and strict about the few that cannot be missing.
 */
public final class Api {
    public static final int VERSION = 1;
    public static final int MAX_QUERY = 80, MAX_PER_PAGE = 50, MAX_PAGE = 500;
    private static final Pattern ID = Pattern.compile("[a-z0-9]{4,24}"), SLUG = Pattern.compile("[a-z0-9-]{1,32}");

    private Api() {
    }

    // ---- what is asked

    /** A search of the builds. Out-of-range values are brought into range, so a query is always one the site accepts. */
    public record Query(String q, String category, String sort, int page, int perPage) {
        public static final String NEW = "new", POPULAR = "popular";

        public Query {
            String t = q == null ? "" : q.trim().replaceAll("\\s+", " ");
            q = t.length() > MAX_QUERY ? t.substring(0, MAX_QUERY).trim() : t;
            category = category != null && SLUG.matcher(category).matches() ? category : "";
            sort = POPULAR.equals(sort) ? POPULAR : NEW;
            page = Math.max(1, Math.min(MAX_PAGE, page));
            perPage = Math.max(1, Math.min(MAX_PER_PAGE, perPage));
        }

        public static Query first(int perPage) {
            return new Query("", "", NEW, 1, perPage);
        }

        public Query withText(String text) {
            return new Query(text, category, sort, 1, perPage);
        }

        public Query withCategory(String id) {
            return new Query(q, id, sort, 1, perPage);
        }

        public Query withSort(String s) {
            return new Query(q, category, s, 1, perPage);
        }

        public Query withPage(int p) {
            return new Query(q, category, sort, p, perPage);
        }

        /** The part after the question mark. */
        public String queryString() {
            StringBuilder sb = new StringBuilder();
            if (!q.isEmpty()) sb.append("q=").append(encode(q)).append('&');
            if (!category.isEmpty()) sb.append("category=").append(category).append('&');
            sb.append("sort=").append(sort).append("&page=").append(page).append("&perPage=").append(perPage);
            return sb.toString();
        }
    }

    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    // ---- what comes back

    public record Build(String id, String title, String author, String category, String categoryLabel, String summary, int sx, int sy, int sz, long blockCount,
                        String sizeClass, String sizeLabel, String minecraftVersion, int dataVersion, long downloads, long favorites, String remixOf,
                        String createdAt, String fileName, String fileExtension, long fileBytes, String thumbnailUrl, String pageUrl, String downloadUrl) {
        /** Whether this version of the mod opens the file: .litematic, .schem (Sponge) and .schematic (old MCEdit). */
        public boolean canOpen() {
            return "litematic".equalsIgnoreCase(fileExtension) || "schem".equalsIgnoreCase(fileExtension) || "schematic".equalsIgnoreCase(fileExtension);
        }

        public String sizeText() {
            return sx + "x" + sy + "x" + sz;
        }
    }

    public record Material(String id, String name, long count) {
    }

    /** A build with what only the single-build answer carries. */
    public record Detail(Build build, String description, String sha256, List<Material> materials) {
    }

    public record Listing(int page, int perPage, long total, int pages, List<Build> items) {
    }

    public record Category(String id, String label, String blurb, long builds) {
        /** A short name for a chip: "Houses & buildings" is "Houses". */
        public String chip() {
            if (id.equals("other")) return "Other";
            int amp = label.indexOf(" & ");
            return amp > 0 ? label.substring(0, amp) : label;
        }
    }

    public record Choice(String id, String label) {
    }

    public record Categories(List<Category> categories, List<Choice> sorts) {
    }

    // ---- parsing

    private static JsonObject root(String body) throws ApiException {
        JsonElement e;
        try {
            e = JsonParser.parseString(body);
        } catch (RuntimeException ex) {
            throw new ApiException(ApiException.Kind.BAD_RESPONSE, 0, 0, "not JSON", ex);
        }
        if (!e.isJsonObject()) throw new ApiException(ApiException.Kind.BAD_RESPONSE, "not an object");
        JsonObject o = e.getAsJsonObject();
        if (!o.has("apiVersion") || !o.get("apiVersion").isJsonPrimitive() || !o.get("apiVersion").getAsJsonPrimitive().isNumber()) {
            throw new ApiException(ApiException.Kind.BAD_RESPONSE, "no apiVersion");
        }
        if (o.get("apiVersion").getAsInt() != VERSION) throw new ApiException(ApiException.Kind.TOO_NEW, "apiVersion " + o.get("apiVersion").getAsInt());
        return o;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static long num(JsonObject o, String key, long def) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return def;
        return e.getAsLong();
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    /** One line of text from the site without control characters (they draw as boxes in the game's font). */
    static String clean(String s, int max) {
        String t = s.replaceAll("\\p{Cntrl}", " ").replaceAll("[ \\t]+", " ").trim();
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    /** As {@link #clean} but keeps line breaks (a build's description). */
    static String cleanText(String s, int max) {
        String t = s.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("[ \\t]+", " ").replaceAll(" ?\n ?", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    static Build build(JsonObject o) throws ApiException {
        String id = str(o, "id", "");
        if (!validId(id)) throw new ApiException(ApiException.Kind.BAD_RESPONSE, "a build without a usable id");
        String title = clean(str(o, "title", ""), 120);
        if (title.isEmpty()) title = "Untitled";
        JsonObject size = obj(o, "size"), file = obj(o, "file");
        return new Build(id, title, clean(str(o, "author", ""), 60), str(o, "category", ""), clean(str(o, "categoryLabel", ""), 60), clean(str(o, "summary", ""), 400),
            (int) num(size, "x", 0), (int) num(size, "y", 0), (int) num(size, "z", 0), num(o, "blockCount", 0), str(o, "sizeClass", ""), clean(str(o, "sizeLabel", ""), 20),
            clean(str(o, "minecraftVersion", ""), 20), (int) num(o, "dataVersion", 0), Math.max(0, num(o, "downloads", 0)), Math.max(0, num(o, "favorites", 0)),
            o.get("remixOf") != null && o.get("remixOf").isJsonPrimitive() ? o.get("remixOf").getAsString() : null, str(o, "createdAt", ""),
            clean(str(file, "name", ""), 120), str(file, "extension", "").toLowerCase(java.util.Locale.ROOT), num(file, "bytes", -1),
            str(o, "thumbnailUrl", ""), str(o, "pageUrl", ""), str(o, "downloadUrl", ""));
    }

    public static Listing listing(String body) throws ApiException {
        JsonObject o = root(body);
        if (!o.has("items") || !o.get("items").isJsonArray()) throw new ApiException(ApiException.Kind.BAD_RESPONSE, "no items");
        List<Build> items = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("items")) {
            if (e.isJsonObject()) items.add(build(e.getAsJsonObject()));
        }
        long total = Math.max(0, num(o, "total", items.size()));
        int perPage = (int) Math.max(1, num(o, "perPage", Math.max(1, items.size())));
        int pages = (int) Math.max(1, num(o, "pages", Math.max(1, (total + perPage - 1) / perPage)));
        return new Listing((int) Math.max(1, num(o, "page", 1)), perPage, total, pages, items);
    }

    public static Detail detail(String body) throws ApiException {
        JsonObject o = root(body);
        Build b = build(o);
        List<Material> materials = new ArrayList<>();
        JsonElement m = o.get("materials");
        if (m != null && m.isJsonArray()) {
            JsonArray arr = m.getAsJsonArray();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject mo = e.getAsJsonObject();
                String name = clean(str(mo, "name", str(mo, "id", "")), 60);
                if (!name.isEmpty()) materials.add(new Material(str(mo, "id", ""), name, Math.max(0, num(mo, "count", 0))));
            }
        }
        String sha = str(o, "fileSha256", "").toLowerCase(java.util.Locale.ROOT);
        return new Detail(b, cleanText(str(o, "description", ""), 4000), sha.matches("[0-9a-f]{64}") ? sha : "", materials);
    }

    public static Categories categories(String body) throws ApiException {
        JsonObject o = root(body);
        List<Category> cats = new ArrayList<>();
        JsonElement c = o.get("categories");
        if (c != null && c.isJsonArray()) {
            for (JsonElement e : c.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject co = e.getAsJsonObject();
                String id = str(co, "id", "");
                if (!SLUG.matcher(id).matches()) continue;
                cats.add(new Category(id, clean(str(co, "label", id), 60), clean(str(co, "blurb", ""), 120), Math.max(0, num(co, "builds", 0))));
            }
        }
        List<Choice> sorts = new ArrayList<>();
        JsonElement s = o.get("sorts");
        if (s != null && s.isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject so = e.getAsJsonObject();
                String id = str(so, "id", "");
                if (id.equals(Query.NEW) || id.equals(Query.POPULAR)) sorts.add(new Choice(id, clean(str(so, "label", id), 30)));
            }
        }
        return new Categories(cats, sorts);
    }

    /** The message of an error answer ({apiVersion, error: {code, message}}), or null. */
    public static String errorMessage(String body) {
        try {
            JsonObject e = obj(JsonParser.parseString(body).getAsJsonObject(), "error");
            String m = str(e, "message", "");
            return m.isEmpty() ? null : clean(m, 200);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
