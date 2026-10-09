package io.github.profetgit.buildbuddy.community;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * What the Community tab shows and what it is waiting for. Every network call runs on the executor it is given (never the
 * render thread); results are published through volatile fields that the screen reads each frame. A result that belongs to
 * a question the player has since replaced is thrown away (a generation number), so a slow answer to "cas" cannot overwrite
 * the answer to "castle". No drawing and no game classes here.
 */
public final class CommunityModel {
    public static final int PER_PAGE = 12, CARD_W = 192, CARD_H = 144, DETAIL_W = 400, DETAIL_H = 300, KEEP_THUMBS = 60;
    public static final long DEBOUNCE_MS = 350;

    public record Pixels(int w, int h, int[] argb) {
    }

    /** Turns PNG bytes into pixels (the game's NativeImage in the client, anything in a test). */
    @FunctionalInterface
    public interface Decoder {
        Pixels decode(byte[] png) throws Exception;
    }

    /** A preview picture on its way: pixels at the size to show, or failed. */
    public static final class Thumb {
        public volatile Pixels pixels;
        public volatile boolean failed;
        volatile boolean started;
    }

    public enum DownloadPhase {
        IDLE, RUNNING, DONE, FAILED
    }

    /** The detail view of one build: the summary at once, the rest when the site answers, and the download. */
    public static final class DetailState {
        public final Api.Build summary;
        public volatile Api.Detail data;
        public volatile ApiException error;
        public final Thumb big = new Thumb();
        public volatile DownloadPhase download = DownloadPhase.IDLE;
        public volatile long received, total;
        public volatile Path saved, existing;
        public volatile ApiException downloadError;
        final AtomicBoolean cancel = new AtomicBoolean();

        DetailState(Api.Build summary) {
            this.summary = summary;
        }
    }

    private final CommunityClient client;
    private final Executor exec;
    private final Decoder decoder;
    private final Path dir;
    private final Downloads.Validator validator;
    private final LongSupplier clock;

    private volatile Api.Query query = Api.Query.first(PER_PAGE);
    private volatile Api.Listing listing;
    private volatile ApiException error;
    private volatile boolean loading;
    private volatile boolean down;
    private volatile Api.Categories categories;
    private int generation;
    private String pendingText;
    private long pendingAt;
    private volatile DetailState detail;
    private int detailGeneration;
    private final Map<String, Thumb> thumbs = new LinkedHashMap<>(64, 0.75f, true);
    /** Counts of calls started, for tests and the demo. */
    public volatile int listCalls, detailCalls;

    public CommunityModel(CommunityClient client, Executor exec, Decoder decoder, Path dir, Downloads.Validator validator, LongSupplier clock) {
        this.client = client;
        this.exec = exec;
        this.decoder = decoder;
        this.dir = dir;
        this.validator = validator;
        this.clock = clock;
    }

    // ---- the list

    public Api.Query query() {
        return query;
    }

    public Api.Listing listing() {
        return listing;
    }

    public ApiException error() {
        return error;
    }

    public boolean loading() {
        return loading;
    }

    public Api.Categories categories() {
        return categories;
    }

    public String base() {
        return client.net().base();
    }

    /** The model is finished with: queued calls do nothing. */
    public void shutdown() {
        down = true;
        close();
    }

    /** Asks for the categories (once) and the first page. */
    public void start() {
        exec.execute(() -> {
            if (down) return;
            try {
                categories = client.categories();
            } catch (ApiException ignored) {
                // the chips just stay out; the list call below reports the real trouble
            }
        });
        load();
    }

    /** Asks again for the current page (the Retry button, or after a change to the query). */
    public void load() {
        if (down) return;
        int gen;
        Api.Query q = query;
        synchronized (this) {
            gen = ++generation;
            loading = true;
            error = null;
            listCalls++;
        }
        exec.execute(() -> {
            if (down) return;
            Api.Listing result = null;
            ApiException failure = null;
            try {
                result = client.builds(q);
            } catch (ApiException e) {
                failure = e;
            } catch (RuntimeException e) {
                failure = new ApiException(ApiException.Kind.BAD_RESPONSE, 0, 0, e.toString(), e);
            }
            synchronized (this) {
                if (gen != generation) return;
                if (failure != null) error = failure;
                else listing = result;
                loading = false;
            }
        });
    }

    /** The search box changed: ask after a pause in typing, not at every letter. */
    public synchronized void typed(String text) {
        String t = text == null ? "" : text;
        if (pendingText == null && t.trim().replaceAll("\\s+", " ").equals(query.q())) return;
        pendingText = t;
        pendingAt = clock.getAsLong();
    }

    /** Enter in the search box: do not wait. */
    public synchronized void submitSearch() {
        if (pendingText == null) return;
        applyPending();
    }

    /** Call every frame: starts the search once typing has paused. */
    public synchronized void tick() {
        if (pendingText != null && clock.getAsLong() - pendingAt >= DEBOUNCE_MS) applyPending();
    }

    private void applyPending() {
        String t = pendingText;
        pendingText = null;
        Api.Query next = query.withText(t);
        if (!next.equals(query)) {
            query = next;
            load();
        }
    }

    public boolean searchPending() {
        return pendingText != null;
    }

    public void setCategory(String id) {
        change(query.withCategory(id));
    }

    public void setSort(String sort) {
        change(query.withSort(sort));
    }

    public void goToPage(int page) {
        Api.Listing l = listing;
        int max = l == null ? Api.MAX_PAGE : l.pages();
        change(query.withPage(Math.max(1, Math.min(max, page))));
    }

    private void change(Api.Query next) {
        synchronized (this) {
            if (next.equals(query)) return;
            query = next;
        }
        load();
    }

    // ---- preview pictures

    /** The preview of a build for the card grid; the first call starts fetching it. */
    public Thumb thumbFor(Api.Build b) {
        Thumb t;
        synchronized (thumbs) {
            t = thumbs.get(b.id());
            if (t == null) {
                t = new Thumb();
                thumbs.put(b.id(), t);
                // forget the oldest ones: a picture is 110 kB of pixels
                var it = thumbs.entrySet().iterator();
                while (thumbs.size() > KEEP_THUMBS && it.hasNext()) {
                    if (it.next().getValue() == t) continue;
                    it.remove();
                }
            }
        }
        fetchThumb(b, t, CARD_W, CARD_H);
        return t;
    }

    private void fetchThumb(Api.Build b, Thumb t, int maxW, int maxH) {
        synchronized (t) {
            if (t.started) return;
            t.started = true;
        }
        exec.execute(() -> {
            if (down) return;
            try {
                Pixels full = decoder.decode(client.thumbnail(b));
                double scale = Math.min(1.0, Math.min(maxW / (double) full.w(), maxH / (double) full.h()));
                int w = Math.max(1, (int) Math.round(full.w() * scale)), h = Math.max(1, (int) Math.round(full.h() * scale));
                t.pixels = new Pixels(w, h, ImageOps.boxDownscale(full.argb(), full.w(), full.h(), w, h));
            } catch (Exception e) {
                t.failed = true;
            }
        });
    }

    // ---- one build

    public DetailState detail() {
        return detail;
    }

    public void open(Api.Build b) {
        close();
        DetailState d = new DetailState(b);
        detail = d;
        fetchThumb(b, d.big, DETAIL_W, DETAIL_H);
        loadDetail(d);
        exec.execute(() -> {
            if (!down) d.existing = Downloads.existingCopy(dir, client.net().base(), b.id());
        });
    }

    public void retryDetail() {
        DetailState d = detail;
        if (d != null && d.data == null) loadDetail(d);
    }

    private void loadDetail(DetailState d) {
        d.error = null;
        detailCalls++;
        exec.execute(() -> {
            if (down) return;
            try {
                Api.Detail got = client.build(d.summary.id());
                d.data = got;
            } catch (ApiException e) {
                d.error = e;
            } catch (RuntimeException e) {
                d.error = new ApiException(ApiException.Kind.BAD_RESPONSE, 0, 0, e.toString(), e);
            }
        });
    }

    /** Leaves the detail view; a download that is still running is stopped (its half file is deleted). */
    public void close() {
        DetailState d = detail;
        detail = null;
        if (d != null) d.cancel.set(true);
    }

    /** Whether the Download button can do anything now. */
    public static boolean canDownload(DetailState d) {
        return d != null && d.data != null && d.summary.canOpen() && d.download != DownloadPhase.RUNNING;
    }

    public void download() {
        DetailState d = detail;
        if (!canDownload(d)) return;
        d.download = DownloadPhase.RUNNING;
        d.received = 0;
        d.total = d.summary.fileBytes();
        d.downloadError = null;
        d.cancel.set(false);
        exec.execute(() -> {
            if (down) return;
            try {
                Downloads.Result r = client.download(d.data, dir, validator, (got, all) -> {
                    d.received = got;
                    d.total = all >= 0 ? all : d.summary.fileBytes();
                }, d.cancel::get);
                d.saved = r.file();
                d.existing = r.file();
                d.download = DownloadPhase.DONE;
            } catch (ApiException e) {
                d.downloadError = e;
                d.download = DownloadPhase.FAILED;
            } catch (RuntimeException e) {
                d.downloadError = new ApiException(ApiException.Kind.DISK, 0, 0, e.toString(), e);
                d.download = DownloadPhase.FAILED;
            }
        });
    }
}
