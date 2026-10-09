package io.github.profetgit.buildbuddy.community;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;

/**
 * The only code that opens a connection to the community site. Plain GETs with a descriptive User-Agent, short timeouts,
 * no redirects, a cap on how much is read, a small cache that obeys the site's max-age and ETag (so asking again within
 * 30 s costs nothing and later costs a 304), and a guard that keeps us under the site's rate limits (120 API requests and
 * 30 downloads a minute). It never sends anything but the path asked for and the User-Agent.
 */
public final class Net {
    public interface Clock {
        long millis();
    }

    /** What a download left on disk. */
    public record Fetched(long bytes, String sha256, String headerSha256) {
    }

    private record Cached(String etag, byte[] body, long fetchedAt, long maxAgeMs) {
    }

    public static final int TEXT_MAX = 1 << 20, IMAGE_MAX = 1 << 20;
    public static final long DOWNLOAD_MAX = 32L << 20;
    private static final long CACHE_BYTES = 24L << 20;
    private static final int CACHE_ENTRIES = 200, API_PER_MINUTE = 100, DOWNLOADS_PER_MINUTE = 25;

    private final String base, userAgent;
    private final Clock clock;
    private final int connectMs, readMs;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long cachedBytes;
    private final Map<String, ArrayDeque<Long>> hits = new LinkedHashMap<>();
    /** How many requests really went out (not answered from the cache): the first-run notice's promise can be checked against it. */
    public final AtomicInteger requests = new AtomicInteger();

    public Net(String base, String userAgent) {
        this(base, userAgent, System::currentTimeMillis, 5000, 10000);
    }

    public Net(String base, String userAgent, Clock clock, int connectMs, int readMs) {
        this.base = base;
        this.userAgent = userAgent;
        this.clock = clock;
        this.connectMs = connectMs;
        this.readMs = readMs;
    }

    public String base() {
        return base;
    }

    private volatile boolean closed;

    /** After this no request leaves, whatever is still queued (the player switched the tab off or changed the address). */
    public void close() {
        closed = true;
    }

    public boolean closed() {
        return closed;
    }

    private void ensureOpen() throws ApiException {
        if (closed) throw new ApiException(ApiException.Kind.CANCELLED, "switched off");
    }

    /**
     * The path (and query) of a URL the site gave us, if it points at the site we are using; relative paths are kept; a URL
     * to any other host gives null, so the site cannot send us to fetch from somewhere else.
     */
    public String localPath(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(url.trim());
            if (u.getScheme() == null && u.getHost() == null) return u.getRawPath() != null && u.getRawPath().startsWith("/") ? withQuery(u) : null;
            URI b = new URI(base);
            boolean same = u.getScheme() != null && u.getScheme().equalsIgnoreCase(b.getScheme()) && u.getHost() != null && u.getHost().equalsIgnoreCase(b.getHost())
                && port(u) == port(b);
            if (!same || u.getRawUserInfo() != null) return null;
            String path = withQuery(u);
            String prefix = b.getRawPath() == null ? "" : b.getRawPath();
            return path.startsWith(prefix) ? path.substring(prefix.length()) : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String withQuery(URI u) {
        return u.getRawPath() + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
    }

    private static int port(URI u) {
        return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    // ---- reading

    /** A text answer (the API's JSON), at most 1 MiB. */
    public String getText(String path) throws ApiException {
        return new String(get(path, TEXT_MAX, "application/json"), StandardCharsets.UTF_8);
    }

    public byte[] getBytes(String path, int max, String accept) throws ApiException {
        return get(path, max, accept);
    }

    private byte[] get(String path, int max, String accept) throws ApiException {
        ensureOpen();
        long now = clock.millis();
        Cached have;
        synchronized (cache) {
            have = cache.get(path);
        }
        if (have != null && now - have.fetchedAt < have.maxAgeMs) return have.body;
        guard(path);
        ensureOpen();
        HttpURLConnection c = open(path, accept);
        boolean ok = false;
        try {
            if (have != null && have.etag != null) c.setRequestProperty("If-None-Match", have.etag);
            requests.incrementAndGet();
            int code = c.getResponseCode();
            if (code == 304 && have != null) {
                remember(path, new Cached(have.etag, have.body, clock.millis(), have.maxAgeMs));
                ok = true;
                return have.body;
            }
            failOnStatus(c, code);
            long declared = c.getContentLengthLong();
            if (declared > max) throw new ApiException(ApiException.Kind.TOO_BIG, "declared " + declared + " bytes");
            byte[] body;
            try (InputStream in = c.getInputStream()) {
                body = readCapped(in, max);
            }
            long age = maxAge(c.getHeaderField("Cache-Control"));
            if (age > 0) remember(path, new Cached(c.getHeaderField("ETag"), body, clock.millis(), age));
            ok = true;
            return body;
        } catch (IOException e) {
            throw classify(e);
        } finally {
            // a connection read to its end stays open for the next picture; one that was cut short is dropped
            if (!ok) c.disconnect();
        }
    }

    /**
     * Streams a file to disk, hashing as it goes. Stops at {@code max} bytes. The caller moves it into place once it has
     * checked the hash.
     */
    public Fetched downloadTo(String path, Path target, long max, ProgressSink progress, BooleanSupplier cancelled) throws ApiException {
        ensureOpen();
        guard(path);
        HttpURLConnection c = open(path, "application/octet-stream");
        c.setReadTimeout(Math.max(readMs, 20000));
        boolean ok = false;
        try {
            requests.incrementAndGet();
            int code = c.getResponseCode();
            failOnStatus(c, code);
            long declared = c.getContentLengthLong();
            if (declared > max) throw new ApiException(ApiException.Kind.TOO_BIG, "declared " + declared + " bytes");
            MessageDigest md = sha256();
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.getAsBoolean()) throw new ApiException(ApiException.Kind.CANCELLED, "cancelled");
                    total += n;
                    if (total > max) throw new ApiException(ApiException.Kind.TOO_BIG, "more than " + max + " bytes");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    progress.update(total, declared);
                }
            }
            String header = c.getHeaderField("X-File-Sha256");
            ok = true;
            return new Fetched(total, HexFormat.of().formatHex(md.digest()), header == null ? "" : header.trim().toLowerCase(Locale.ROOT));
        } catch (IOException e) {
            throw classify(e);
        } finally {
            if (!ok) c.disconnect();
        }
    }

    public interface ProgressSink {
        void update(long received, long total);
    }

    // ---- the connection

    private HttpURLConnection open(String path, String accept) throws ApiException {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", accept);
            return c;
        } catch (IOException | IllegalArgumentException e) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, 0, 0, "bad address " + base + path, e);
        }
    }

    private void failOnStatus(HttpURLConnection c, int code) throws IOException, ApiException {
        if (code >= 200 && code < 300) return;
        String message = null;
        try (InputStream err = c.getErrorStream()) {
            if (err != null) message = Api.errorMessage(new String(readCapped(err, 8192), StandardCharsets.UTF_8));
        } catch (ApiException ignored) {
            // an oversized error page is not worth reading
        }
        String why = message == null ? "HTTP " + code : message;
        if (code >= 300 && code < 400) throw new ApiException(ApiException.Kind.REDIRECT, code, 0, why, null);
        if (code == 404) throw new ApiException(ApiException.Kind.NOT_FOUND, code, 0, why, null);
        if (code == 429) throw new ApiException(ApiException.Kind.RATE_LIMITED, code, retryAfter(c.getHeaderField("Retry-After")), why, null);
        if (code >= 500) throw new ApiException(ApiException.Kind.SERVER, code, 0, why, null);
        throw new ApiException(ApiException.Kind.BAD_REQUEST, code, 0, why, null);
    }

    private static int retryAfter(String header) {
        try {
            return Math.max(1, Math.min(3600, Integer.parseInt(header.trim())));
        } catch (RuntimeException e) {
            return 60;
        }
    }

    static ApiException classify(IOException e) {
        if (e instanceof SocketTimeoutException) {
            boolean connecting = e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("connect");
            return new ApiException(connecting ? ApiException.Kind.OFFLINE : ApiException.Kind.TIMEOUT, 0, 0, e.getMessage(), e);
        }
        if (e instanceof UnknownHostException || e instanceof ConnectException || e instanceof NoRouteToHostException) return new ApiException(ApiException.Kind.OFFLINE, 0, 0, e.toString(), e);
        if (e instanceof SSLException) return new ApiException(ApiException.Kind.BAD_RESPONSE, 0, 0, "secure connection failed: " + e.getMessage(), e);
        return new ApiException(ApiException.Kind.OFFLINE, 0, 0, e.toString(), e);
    }

    private static byte[] readCapped(InputStream in, int max) throws IOException, ApiException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(max, 64 * 1024));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (out.size() + n > max) throw new ApiException(ApiException.Kind.TOO_BIG, "more than " + max + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- being a good guest

    /** Seconds from a Cache-Control header's max-age, as milliseconds, capped at an hour; 0 when there is none (no-store, no max-age). */
    static long maxAge(String header) {
        if (header == null) return 0;
        String h = header.toLowerCase(Locale.ROOT);
        if (h.contains("no-store") || h.contains("no-cache")) return 0;
        int i = h.indexOf("max-age=");
        if (i < 0) return 0;
        int j = i + 8, k = j;
        while (k < h.length() && Character.isDigit(h.charAt(k))) k++;
        if (k == j) return 0;
        try {
            return Math.min(3600, Long.parseLong(h.substring(j, k))) * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void remember(String path, Cached c) {
        synchronized (cache) {
            Cached old = cache.put(path, c);
            if (old != null) cachedBytes -= old.body.length;
            cachedBytes += c.body.length;
            Iterator<Map.Entry<String, Cached>> it = cache.entrySet().iterator();
            while ((cachedBytes > CACHE_BYTES || cache.size() > CACHE_ENTRIES) && it.hasNext()) {
                Map.Entry<String, Cached> e = it.next();
                if (e.getKey().equals(path)) continue;
                cachedBytes -= e.getValue().body.length;
                it.remove();
            }
        }
    }

    /** Keeps API calls under 100 a minute and downloads under 25 (the site allows 120 and 30); pictures are not limited by the site. */
    private void guard(String path) throws ApiException {
        String bucket;
        int max;
        if (path.startsWith("/api/") && path.contains("/download")) {
            bucket = "dl";
            max = DOWNLOADS_PER_MINUTE;
        } else if (path.startsWith("/api/")) {
            bucket = "api";
            max = API_PER_MINUTE;
        } else {
            return;
        }
        long now = clock.millis();
        synchronized (hits) {
            ArrayDeque<Long> q = hits.computeIfAbsent(bucket, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000) q.pollFirst();
            if (q.size() >= max) {
                int wait = (int) Math.max(1, (60_000 - (now - q.peekFirst()) + 999) / 1000);
                throw new ApiException(ApiException.Kind.RATE_LIMITED, 0, wait, "slowing down to stay under the site's limit", null);
            }
            q.addLast(now);
        }
    }
}
