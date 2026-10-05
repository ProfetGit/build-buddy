package io.github.profetgit.cyanotype.community;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** A community site on a local port, answering what a test tells it to, and remembering what it was asked. */
final class FakeSite implements AutoCloseable {
    /** What to do with one request. */
    interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    final HttpServer server;
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final List<String> userAgents = Collections.synchronizedList(new ArrayList<>());
    final List<String> conditional = Collections.synchronizedList(new ArrayList<>());
    volatile Handler handler = ex -> send(ex, 404, "{\"apiVersion\":1,\"error\":{\"code\":\"not_found\",\"message\":\"nothing here\"}}");

    FakeSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String what = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() != null ? "?" + ex.getRequestURI().getRawQuery() : "");
            log.add(what);
            userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if (inm != null) conditional.add(what + " " + inm);
            try {
                handler.handle(ex);
            } finally {
                ex.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    Net net() {
        return new Net(base(), "Cyanotype/test");
    }

    void on(Handler h) {
        handler = h;
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", h -> {
        });
    }

    static void send(HttpExchange ex, int code, byte[] body, String type, Consumer<com.sun.net.httpserver.Headers> extra) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        extra.accept(ex.getResponseHeaders());
        if (code == 304) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- canned answers shaped like the real API

    static String item(String id, String title, String ext) {
        return "{\"id\":\"" + id + "\",\"title\":" + new com.google.gson.JsonPrimitive(title) + ",\"author\":\"Profet\",\"category\":\"houses\",\"categoryLabel\":\"Houses & buildings\",\"summary\":\"A test build\","
            + "\"size\":{\"x\":9,\"y\":6,\"z\":7},\"blockCount\":155,\"sizeClass\":\"tiny\",\"sizeLabel\":\"Tiny\",\"minecraftVersion\":\"26.3\",\"dataVersion\":5023,\"downloads\":3,"
            + "\"favorites\":1,\"remixOf\":null,\"createdAt\":\"2026-10-05T10:22:01.247Z\",\"updatedAt\":\"2026-10-05T10:22:01.247Z\","
            + "\"file\":{\"name\":\"" + id + "." + ext + "\",\"extension\":\"" + ext + "\",\"bytes\":657},\"thumbnailUrl\":\"http://example.invalid/media/" + id + "/thumb.png\","
            + "\"pageUrl\":\"http://example.invalid/builds/" + id + "\",\"downloadUrl\":\"http://example.invalid/api/v1/builds/" + id + "/download\"}";
    }

    static String listing(int page, int pages, long total, String... items) {
        return "{\"apiVersion\":1,\"page\":" + page + ",\"perPage\":12,\"total\":" + total + ",\"pages\":" + pages + ",\"items\":[" + String.join(",", items) + "]}";
    }
}
