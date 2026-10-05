package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.community.Api;
import io.github.profetgit.cyanotype.community.ApiException;
import io.github.profetgit.cyanotype.community.Community;
import io.github.profetgit.cyanotype.community.CommunityConfig;
import io.github.profetgit.cyanotype.community.CommunityModel;
import io.github.profetgit.cyanotype.community.Words;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The Community tab of the Library: a grid of builds other people shared (search, category chips, sort, pages), a detail view
 * with materials and a Download button, and honest states for everything else (not asked yet, switched off, no address,
 * loading, offline, empty). It draws into the Library's panel and gets its clicks from the Library screen. All network work
 * is in {@link CommunityModel}, off the render thread; this class only reads what the model has so far.
 */
public final class CommunityPane {
    /** What the pane needs from the Library around it. */
    public interface Host {
        void place(Path file);

        void showMine();

        void say(String text);

        void rescan();

        void openSettings();
    }

    /** What the pane is showing. */
    public enum Gate {
        NO_ADDRESS, NEEDS_CONSENT, OFF, ON
    }

    private record Hit(String id, int x, int y, int w, int h, Object payload) {
    }

    static final int CARD_W = 74, CARD_H = 106, GAP = 6, THUMB_W = 64, THUMB_H = 48;

    private final Host host;
    private CommunityModel model;
    private String modelBase = "";
    private final List<Hit> hits = new ArrayList<>();
    private final Map<CommunityModel.Thumb, Identifier> textures = new HashMap<>();
    private final Map<CommunityModel.Thumb, DynamicTexture> owned = new HashMap<>();
    private Api.Listing lastListing;
    private CommunityModel.DetailState lastDetail;
    private String down = "";
    private Object downPayload;
    private float scroll, scrollTarget, detailScroll, detailScrollTarget;
    private int uploadsThisFrame;
    private String searchText = "";

    // where the last frame put things (the demo clicks on them, and the click handlers read them)
    private int gx, gy, gw, gh, detailTop, detailBottom;

    public CommunityPane(Host host) {
        this.host = host;
    }

    // ---- state

    public Gate gate() {
        String base = Community.base();
        if (base == null) return Gate.NO_ADDRESS;
        return switch (Community.consent()) {
            case CommunityConfig.ON -> Gate.ON;
            case CommunityConfig.OFF -> Gate.OFF;
            default -> Gate.NEEDS_CONSENT;
        };
    }

    /** Whether the Library's search box means something right now. */
    public boolean searchable() {
        return gate() == Gate.ON && (model == null || model.detail() == null);
    }

    public CommunityModel model() {
        return model;
    }

    /** The search box changed. */
    public void typed(String text) {
        searchText = text;
        if (model != null) model.typed(text);
    }

    public void submit() {
        if (model != null) model.submitSearch();
    }

    /** A pane stays alive while the Library is open; the model is made when the player has agreed and an address exists. */
    private void ensureModel() {
        if (gate() != Gate.ON) {
            if (model != null) {
                // switched off (or no address any more): whatever was queued must not be sent
                model.shutdown();
                Community.stop();
            }
            model = null;
            modelBase = "";
            return;
        }
        String base = Community.base();
        if (model == null || !modelBase.equals(base)) {
            if (model != null) model.shutdown();
            model = Community.newModel(base);
            modelBase = base;
            model.start();
            if (!searchText.isBlank()) model.typed(searchText);
        }
    }

    /** True when Esc should go back from the detail view instead of closing the Library. */
    public boolean backFromDetail() {
        if (model != null && model.detail() != null) {
            model.close();
            Sfx.play(Sfx.RELEASE);
            return true;
        }
        return false;
    }

    public void removed() {
        if (model != null) model.shutdown();
        for (DynamicTexture t : owned.values()) t.close();
        for (Identifier id : textures.values()) Minecraft.getInstance().getTextureManager().release(id);
        owned.clear();
        textures.clear();
    }

    // ---- layout

    private static int tabW(String label) {
        return Ui.font().width(label) + 12;
    }

    private static int chipW(String label) {
        return Ui.font().width(label) + 10;
    }

    /** The chips: "All" and each category the site has, as (id, label, count) with where they sit. */
    private record Chip(String id, String label, int x, int y, int w, long count) {
    }

    private List<Chip> chips(int x0, int y0, int width) {
        List<Chip> out = new ArrayList<>();
        Api.Categories c = model == null ? null : model.categories();
        List<String[]> items = new ArrayList<>();
        items.add(new String[]{"", "All", ""});
        if (c != null) for (Api.Category k : c.categories()) items.add(new String[]{k.id(), k.chip(), String.valueOf(k.builds())});
        int x = x0, y = y0;
        for (String[] it : items) {
            int w = chipW(it[1]);
            if (x + w > x0 + width && x > x0) {
                x = x0;
                y += 14;
            }
            out.add(new Chip(it[0], it[1], x, y, w, it[2].isEmpty() ? -1 : Long.parseLong(it[2])));
            x += w + 3;
        }
        return out;
    }

    // ---- drawing

    private void reg(String id, int x, int y, int w, int h, Object payload) {
        hits.add(new Hit(id, x, y, w, h, payload));
    }

    private boolean btn(GuiGraphicsExtractor g, String id, int x, int y, int w, String label, boolean enabled, int mx, int my) {
        if (enabled) reg(id, x, y, w, 16, null);
        return Ui.button(g, "com#" + id, x, y, w, 16, label, null, mx, my, down.equals(id), enabled);
    }

    /**
     * Draws the tab's body and footer into the Library's panel.
     *
     * @param inner how visible the contents are while the panel opens
     */
    public void render(GuiGraphicsExtractor g, int px, int py, int pw, int ph, int mx, int my, float inner, double t) {
        hits.clear();
        uploadsThisFrame = 0;
        ensureModel();
        int bodyX = px + 10, bodyW = pw - 20, bodyY = py + 26, footY = py + ph - 24;
        switch (gate()) {
            case NO_ADDRESS -> notice(g, bodyX, bodyY, bodyW, footY - bodyY, inner, "No community website yet",
                "There is no website address to talk to, so this tab sends nothing. When the community site has a public address it appears here by itself. To try a copy on this computer, use Settings.",
                new String[][]{{"settings", "Settings"}}, mx, my);
            case NEEDS_CONSENT -> {
                String host = CommunityConfig.hostOf(Community.base());
                notice(g, bodyX, bodyY, bodyW, footY - bodyY, inner, "Browse builds from other players?",
                    "This tab shows builds that people shared on " + host + ". To do that, Cyanotype contacts that website: it asks for the list, the preview pictures and any file you choose to download. "
                        + "The website sees what you search for and your internet address, like any website you visit. Nothing about you or your worlds is sent. You can change this any time in Settings.",
                    new String[][]{{"consent:on", "Turn on"}, {"consent:off", "No thanks"}}, mx, my);
            }
            case OFF -> notice(g, bodyX, bodyY, bodyW, footY - bodyY, inner, "The Community tab is off",
                "Nothing is sent to the community website while it is off. Turn it on to browse and download builds that other players shared.",
                new String[][]{{"consent:on", "Turn on"}}, mx, my);
            case ON -> {
                CommunityModel.DetailState d = model.detail();
                if (d != null) detail(g, d, px, py, pw, ph, mx, my, inner, t);
                else list(g, px, py, pw, ph, mx, my, inner, t);
            }
        }
        int fy = footY;
        if (gate() != Gate.ON || model == null || model.detail() == null) {
            // a click lands on a button of the body or the footer; the Library draws Close itself
            footerList(g, px, fy, pw, mx, my, inner);
        }
    }

    private void notice(GuiGraphicsExtractor g, int x, int y, int w, int h, float a, String title, String body, String[][] buttons, int mx, int my) {
        int cx = x + w / 2, top = y + Math.max(4, h / 2 - 62);
        Ui.icon(g, "folder", cx - 7, top, false);
        Ui.centered(g, title, cx, top + 20, Ui.withAlpha(Ui.LINE, a));
        int ly = top + 34;
        for (String line : Ui.wrap(body, Math.min(w - 20, 290), 9)) {
            Ui.centered(g, line, cx, ly, Ui.withAlpha(Ui.DIM, a));
            ly += 10;
        }
        int total = 0;
        for (String[] b : buttons) total += Math.max(60, Ui.font().width(b[1]) + 20) + 6;
        int bx = cx - (total - 6) / 2;
        for (String[] b : buttons) {
            int bw = Math.max(60, Ui.font().width(b[1]) + 20);
            btn(g, b[0], bx, ly + 6, bw, b[1], true, mx, my);
            bx += bw + 6;
        }
    }

    private void list(GuiGraphicsExtractor g, int px, int py, int pw, int ph, int mx, int my, float inner, double t) {
        model.tick();
        Api.Query q = model.query();
        Api.Listing listing = model.listing();
        if (listing != lastListing) {
            lastListing = listing;
            releaseUnused();
        }
        int bodyX = px + 10, bodyW = pw - 20;

        // sort tabs
        int tx = bodyX, ty = py + 26;
        for (String[] s : new String[][]{{Api.Query.NEW, "Newest"}, {Api.Query.POPULAR, "Popular"}}) {
            int w = tabW(s[1]) + 6;
            Ui.tab(g, "com#sort" + s[0], tx, ty, w, s[1], q.sort().equals(s[0]), mx, my);
            reg("sort:" + s[0], tx, ty, w, 13, null);
            tx += w + 2;
        }
        String status = model.searchPending() ? "Searching..." : model.loading() ? "Loading..." : listing != null && model.error() == null ? Words.builds(listing.total()) : "";
        Ui.right(g, status, px + pw - 10, ty + 3, Ui.withAlpha(Ui.DIM, inner));

        // category chips
        int chipY = py + 42;
        List<Chip> chips = chips(bodyX, chipY, bodyW);
        for (Chip c : chips) {
            boolean on = q.category().equals(c.id);
            boolean over = Ui.inside(mx, my, c.x, c.y, c.w, 12);
            float hv = Motion.hover("com#chip" + c.id, over && !on);
            if (on) Ui.blit(g, "tab_on", c.x, c.y, c.w, 12);
            else Ui.inset(g, c.x, c.y, c.w, 12);
            int color = on ? Ui.DEEP : Ui.mixColor(c.count == 0 ? 0xFF5E7C99 : Ui.DIM, Ui.WHITE, hv);
            Ui.centered(g, c.label, c.x + c.w / 2, c.y + 2, color);
            reg("cat:" + c.id, c.x, c.y, c.w, 12, null);
        }
        int rows = chips.isEmpty() ? 1 : (chips.get(chips.size() - 1).y - chipY) / 14 + 1;

        gx = bodyX;
        gy = chipY + rows * 14 + 2;
        gw = bodyW;
        gh = py + ph - 30 - gy;

        ApiException err = model.error();
        if (err != null) {
            problem(g, err, inner, mx, my);
        } else if (listing == null) {
            loadingDots(g, inner, t);
        } else if (listing.items().isEmpty()) {
            empty(g, q, inner, mx, my);
        } else {
            grid(g, listing, mx, my, inner, t);
        }
        if (listing != null && err == null && !listing.items().isEmpty() && model.loading()) {
            Ui.centered(g, "Loading...", gx + gw / 2, gy + gh - 10, Ui.withAlpha(Ui.CYAN, inner));
        }
    }

    private void loadingDots(GuiGraphicsExtractor g, float a, double t) {
        int dots = 1 + (int) (t * 3) % 3;
        Ui.centered(g, "Loading builds" + ".".repeat(dots), gx + gw / 2, gy + gh / 2 - 4, Ui.withAlpha(Ui.DIM, a));
    }

    private void problem(GuiGraphicsExtractor g, ApiException e, float a, int mx, int my) {
        int cx = gx + gw / 2, cy = gy + gh / 2 - 34;
        Ui.icon(g, "cross", cx - 7, cy, false);
        int ly = cy + 22;
        for (String line : Ui.wrap(e.forPlayer(), Math.min(gw - 30, 280), 3)) {
            Ui.centered(g, line, cx, ly, Ui.withAlpha(Ui.LINE, a));
            ly += 10;
        }
        Ui.centered(g, CommunityConfig.hostOf(model.base()), cx, ly + 2, Ui.withAlpha(Ui.DIM, a * 0.8f));
        if (e.retryable() || e.kind == ApiException.Kind.BAD_RESPONSE) btn(g, "retry", cx - 30, ly + 16, 60, "Try again", true, mx, my);
    }

    private void empty(GuiGraphicsExtractor g, Api.Query q, float a, int mx, int my) {
        int cx = gx + gw / 2, cy = gy + gh / 2 - 26;
        Ui.icon(g, "folder", cx - 7, cy, false);
        boolean filtered = !q.q().isEmpty() || !q.category().isEmpty();
        Ui.centered(g, filtered ? "Nothing matches that" : "No builds on the site yet", cx, cy + 22, Ui.withAlpha(Ui.LINE, a));
        Ui.centered(g, filtered ? "Try other words, or another category." : "Check back later.", cx, cy + 36, Ui.withAlpha(Ui.DIM, a));
        if (filtered) btn(g, "clear", cx - 40, cy + 50, 80, "Clear filters", true, mx, my);
    }

    private int cols() {
        return Math.max(1, (gw + GAP) / (CARD_W + GAP));
    }

    private int contentH(int n) {
        int rows = (n + cols() - 1) / cols();
        return rows == 0 ? 0 : rows * (CARD_H + GAP) - GAP;
    }

    private void grid(GuiGraphicsExtractor g, Api.Listing listing, int mx, int my, float inner, double t) {
        List<Api.Build> items = listing.items();
        int max = Math.max(0, contentH(items.size()) - gh);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        scroll = Motion.follow("com#scroll", scrollTarget, 0.06);
        g.enableScissor(gx - 2, gy, gx + gw + 2, gy + gh);
        int cols = cols(), left = (gw - (cols * (CARD_W + GAP) - GAP)) / 2;
        Api.Build hover = null;
        for (int i = 0; i < items.size(); i++) {
            Api.Build b = items.get(i);
            int cx = gx + left + (i % cols) * (CARD_W + GAP), cy = gy + (i / cols) * (CARD_H + GAP) - Math.round(scroll);
            if (cy + CARD_H < gy - 2 || cy > gy + gh) continue;
            boolean over = Ui.inside(mx, my, cx, cy, CARD_W, CARD_H) && Ui.inside(mx, my, gx, gy, gw, gh);
            if (over) hover = b;
            card(g, b, cx, cy, over, down.equals("card") && downPayload == b, t);
            if (Ui.inside(cx, cy, gx - CARD_W, gy - CARD_H, gw + CARD_W * 2, gh + CARD_H * 2)) reg("card", cx, cy, CARD_W, CARD_H, b);
        }
        g.disableScissor();
        if (max > 0) {
            int barH = Math.max(12, gh * gh / (max + gh));
            int barY = gy + (int) ((gh - barH) * (scroll / (float) max));
            g.fill(gx + gw - 1, gy, gx + gw + 1, gy + gh, Ui.withAlpha(Ui.DEEP, 0.7f));
            g.fill(gx + gw - 1, barY, gx + gw + 1, barY + barH, Ui.withAlpha(Ui.CYAN, 0.9f));
        }
        if (hover != null) {
            StringBuilder tip = new StringBuilder(hover.title()).append("\nby ").append(hover.author().isEmpty() ? "unknown" : hover.author());
            for (String line : Ui.wrap(hover.summary(), 170, 4)) tip.append('\n').append(line);
            if (!hover.isLitematic()) tip.append("\nA .").append(hover.fileExtension()).append(" file: this version opens .litematic only.");
            Ui.tooltip(g, mx, my, tip.toString());
        }
    }

    private void card(GuiGraphicsExtractor g, Api.Build b, int x, int y, boolean over, boolean pressed, double t) {
        String key = "com#card" + b.id();
        float hv = Motion.hover(key, over), pr = Motion.press(key, pressed);
        y -= Math.round(hv) - Math.round(pr);
        Ui.inset(g, x, y, CARD_W, CARD_H);
        int tx = x + 5, ty = y + 5;
        CommunityModel.Thumb th = model.thumbFor(b);
        Identifier tex = textureOf(th);
        if (tex != null) {
            CommunityModel.Pixels p = th.pixels;
            g.blit(RenderPipelines.GUI_TEXTURED, tex, tx, ty, 0f, 0f, THUMB_W, THUMB_H, p.w(), p.h(), p.w(), p.h());
        } else if (th.failed) {
            Ui.icon(g, "cross", tx + 25, ty + 17, false);
        } else {
            Ui.centered(g, ".".repeat(1 + (int) (t * 3) % 3), tx + THUMB_W / 2, ty + 18, Ui.DIM);
        }
        if (!b.isLitematic()) {
            String ext = "." + b.fileExtension();
            int w = Ui.font().width(ext) + 4;
            g.fill(tx + THUMB_W - w, ty + THUMB_H - 10, tx + THUMB_W, ty + THUMB_H, Ui.withAlpha(Ui.DEEP, 0.85f));
            Ui.text(g, ext, tx + THUMB_W - w + 2, ty + THUMB_H - 9, Ui.WARN);
        }
        int ly = y + 57;
        for (String line : Ui.wrap(b.title(), CARD_W - 8, 2)) {
            Ui.text(g, line, x + 5, ly, Ui.LINE);
            ly += 9;
        }
        Ui.text(g, Ui.fit("by " + (b.author().isEmpty() ? "unknown" : b.author()), CARD_W - 8), x + 5, y + 76, Ui.DIM);
        Ui.text(g, Ui.fit(b.sizeLabel().isEmpty() ? Words.count(b.blockCount()) + " blocks" : b.sizeLabel(), CARD_W - 8), x + 5, y + 86, Ui.CYAN);
        Ui.text(g, Ui.fit(Words.downloads(b.downloads()), CARD_W - 8), x + 5, y + 95, Ui.DIM);
        if (hv > 0.02f) Ui.marching(g, x - 1, y - 1, CARD_W + 2, CARD_H + 2, Ui.withAlpha(Ui.CYAN, hv), t);
    }

    /** A texture for a preview, made on the render thread (two a frame, so a page of them does not stall one frame). */
    private Identifier textureOf(CommunityModel.Thumb th) {
        Identifier have = textures.get(th);
        if (have != null) return have;
        CommunityModel.Pixels p = th.pixels;
        if (p == null || uploadsThisFrame >= 2) return null;
        uploadsThisFrame++;
        NativeImage img = new NativeImage(p.w(), p.h(), true);
        for (int y = 0; y < p.h(); y++) {
            for (int x = 0; x < p.w(); x++) img.setPixel(x, y, p.argb()[y * p.w() + x]);
        }
        Skin.Smooth tex = new Skin.Smooth("Cyanotype community preview", img, false);
        Identifier id = Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, "community/" + Integer.toHexString(System.identityHashCode(th)) + "_" + System.nanoTime());
        Minecraft.getInstance().getTextureManager().register(id, tex);
        owned.put(th, tex);
        textures.put(th, id);
        return id;
    }

    /** Lets go of the textures of pictures that are no longer on screen or in the detail view. */
    private void releaseUnused() {
        Set<CommunityModel.Thumb> keep = new HashSet<>();
        if (model != null && lastListing != null) for (Api.Build b : lastListing.items()) keep.add(model.thumbFor(b));
        if (lastDetail != null) keep.add(lastDetail.big);
        var it = textures.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (keep.contains(e.getKey())) continue;
            DynamicTexture dt = owned.remove(e.getKey());
            if (dt != null) dt.close();
            Minecraft.getInstance().getTextureManager().release(e.getValue());
            it.remove();
        }
    }

    // ---- footer (list mode)

    private void footerList(GuiGraphicsExtractor g, int px, int fy, int pw, int mx, int my, float inner) {
        if (gate() != Gate.ON || model == null) return;
        Api.Listing l = model.listing();
        if (l == null || model.error() != null || l.pages() <= 1 && l.items().isEmpty()) return;
        Api.Query q = model.query();
        btn(g, "prev", px + 10, fy, 46, "Prev", q.page() > 1, mx, my);
        btn(g, "next", px + 10 + 50 + 74, fy, 46, "Next", q.page() < l.pages(), mx, my);
        Ui.centered(g, Words.page(q.page(), l.pages()), px + 10 + 50 + 37, fy + 4, Ui.withAlpha(Ui.DIM, inner));
    }

    // ---- the detail view

    private void detail(GuiGraphicsExtractor g, CommunityModel.DetailState d, int px, int py, int pw, int ph, int mx, int my, float inner, double t) {
        if (d != lastDetail) {
            lastDetail = d;
            detailScroll = detailScrollTarget = 0;
            Motion.drop("com#dscroll");
            releaseUnused();
        }
        Api.Build b = d.summary;
        Api.Detail full = d.data;
        int x0 = px + 10, w = pw - 20, top = py + 26, fy = py + ph - 24;
        detailTop = top;
        detailBottom = fy - 6;
        int viewH = detailBottom - top, bar = 6, cw = w - bar;

        // lay out first, to know the height
        List<Runnable> draw = new ArrayList<>();
        int[] y = {0};
        int tw = 128;
        int colX = tw + 8, colW = cw - colX;
        // picture
        draw.add(() -> picture(g, d, x0, top - Math.round(detailScroll), tw, 96, t));
        // facts
        List<String[]> facts = new ArrayList<>();
        facts.add(new String[]{"by " + (b.author().isEmpty() ? "unknown" : b.author()), "dim"});
        if (!b.categoryLabel().isEmpty()) facts.add(new String[]{b.categoryLabel(), "dim"});
        facts.add(new String[]{Words.size(b) + "  (" + Words.count(b.blockCount()) + " blocks)", "cyan"});
        if (!b.sizeLabel().isEmpty()) facts.add(new String[]{b.sizeLabel() + " build", "cyan"});
        if (!b.minecraftVersion().isEmpty()) facts.add(new String[]{"Minecraft " + b.minecraftVersion(), "dim"});
        facts.add(new String[]{"." + (b.fileExtension().isEmpty() ? "?" : b.fileExtension()) + " file, " + Words.bytes(b.fileBytes()), b.isLitematic() ? "dim" : "warn"});
        facts.add(new String[]{Words.downloads(b.downloads()) + (b.favorites() > 0 ? ", " + b.favorites() + (b.favorites() == 1 ? " favourite" : " favourites") : ""), "dim"});
        if (b.remixOf() != null) facts.add(new String[]{"A remix of another build", "dim"});
        int fh = 0;
        List<String> titleLines = Ui.wrap(b.title(), colW, 2);
        fh += titleLines.size() * 10 + 3;
        fh += facts.size() * 9;
        int headH = Math.max(96, fh);
        draw.add(() -> {
            int ly = top - Math.round(detailScroll);
            for (String line : titleLines) {
                Ui.text(g, line, x0 + colX, ly, Ui.withAlpha(Ui.WHITE, inner));
                ly += 10;
            }
            ly += 3;
            for (String[] f : facts) {
                int color = f[1].equals("cyan") ? Ui.CYAN : f[1].equals("warn") ? Ui.WARN : Ui.DIM;
                Ui.text(g, Ui.fit(f[0], colW), x0 + colX, ly, Ui.withAlpha(color, inner));
                ly += 9;
            }
        });
        y[0] = headH + 8;

        // a message about the file: already have it, cannot open this format, saved, failed
        String[] note = noteFor(d);
        if (note != null) {
            List<String> lines = Ui.wrap(note[0], cw - 6, 4);
            int at = y[0], color = note[1].equals("good") ? Ui.GOOD : note[1].equals("bad") ? Ui.BAD : Ui.WARN;
            draw.add(() -> {
                int ly = top + at - Math.round(detailScroll);
                for (String line : lines) {
                    Ui.text(g, line, x0, ly, Ui.withAlpha(color, inner));
                    ly += 10;
                }
            });
            y[0] += lines.size() * 10 + 6;
        }

        if (full == null) {
            int at = y[0];
            ApiException e = d.error;
            draw.add(() -> {
                int ly = top + at - Math.round(detailScroll);
                if (e == null) Ui.text(g, "Loading the details" + ".".repeat(1 + (int) (t * 3) % 3), x0, ly, Ui.withAlpha(Ui.DIM, inner));
                else {
                    Ui.text(g, Ui.fit(e.forPlayer(), cw), x0, ly, Ui.withAlpha(Ui.BAD, inner));
                    if (e.retryable()) btn(g, "retryDetail", x0, ly + 12, 60, "Try again", true, mx, my);
                }
            });
            y[0] += 32;
        } else {
            if (!full.description().isBlank()) {
                int at = y[0];
                List<String> lines = new ArrayList<>();
                for (String para : full.description().split("\n")) {
                    if (para.isBlank()) lines.add("");
                    else lines.addAll(Ui.wrap(para, cw - 4, 6));
                    if (lines.size() >= 14) break;
                }
                draw.add(() -> {
                    int ly = top + at - Math.round(detailScroll);
                    Ui.text(g, "About", x0, ly, Ui.withAlpha(Ui.CYAN, inner));
                    ly += 11;
                    for (String line : lines) {
                        Ui.text(g, line, x0, ly, Ui.withAlpha(Ui.LINE, inner));
                        ly += 9;
                    }
                });
                y[0] += 11 + lines.size() * 9 + 8;
            }
            List<Api.Material> mats = full.materials();
            if (!mats.isEmpty()) {
                int at = y[0], shown = Math.min(mats.size(), 40);
                int half = Math.max(1, cw / 2 - 4);
                int rows = (shown + 1) / 2;
                draw.add(() -> {
                    int ly = top + at - Math.round(detailScroll);
                    Ui.text(g, "Materials", x0, ly, Ui.withAlpha(Ui.CYAN, inner));
                    Ui.right(g, mats.size() + (mats.size() == 1 ? " kind" : " kinds") + (mats.size() > shown ? ", the biggest " + shown + " shown" : ""), x0 + cw, ly, Ui.withAlpha(Ui.DIM, inner));
                    ly += 11;
                    for (int i = 0; i < shown; i++) {
                        Api.Material m = mats.get(i);
                        int cx = x0 + (i % 2) * (half + 8), cy = ly + (i / 2) * 9;
                        String count = Words.count(m.count());
                        Ui.right(g, count, cx + 30, cy, Ui.withAlpha(Ui.WHITE, inner));
                        Ui.text(g, Ui.fit(m.name(), half - 36), cx + 36, cy, Ui.withAlpha(Ui.LINE, inner));
                    }
                });
                y[0] += 11 + rows * 9 + 6;
            }
        }
        int contentH = y[0];
        int max = Math.max(0, contentH - viewH);
        detailScrollTarget = Math.max(0, Math.min(max, detailScrollTarget));
        detailScroll = Motion.follow("com#dscroll", detailScrollTarget, 0.06);
        g.enableScissor(x0 - 2, top, x0 + w + 2, detailBottom);
        for (Runnable r : draw) r.run();
        g.disableScissor();
        if (max > 0) {
            int barH = Math.max(12, viewH * viewH / (max + viewH));
            int barY = top + (int) ((viewH - barH) * (detailScroll / (float) max));
            g.fill(px + pw - 7, top, px + pw - 5, detailBottom, Ui.withAlpha(Ui.DEEP, 0.7f));
            g.fill(px + pw - 7, barY, px + pw - 5, barY + barH, Ui.withAlpha(Ui.CYAN, 0.9f));
        }
        // the buttons that matter stay put under the scrolling part
        detailFooter(g, d, px, fy, pw, mx, my, inner, t);
    }

    /** What to say about the file, with a mood: good, bad or warn. Null when there is nothing to say. */
    private static String[] noteFor(CommunityModel.DetailState d) {
        Api.Build b = d.summary;
        if (d.download == CommunityModel.DownloadPhase.DONE && d.saved != null) {
            return new String[]{"Saved as " + d.saved.getFileName() + " in your library. It is in the Mine tab now.", "good"};
        }
        if (d.download == CommunityModel.DownloadPhase.FAILED && d.downloadError != null) return new String[]{d.downloadError.forPlayer(), "bad"};
        if (!b.isLitematic()) {
            return new String[]{"This is a ." + (b.fileExtension().isEmpty() ? "?" : b.fileExtension()) + " file. This version of Cyanotype opens .litematic files only, so it cannot be downloaded here. "
                + "You can still get it from its page on the website.", "warn"};
        }
        if (d.existing != null && d.download == CommunityModel.DownloadPhase.IDLE) {
            return new String[]{"You already have this build: " + d.existing.getFileName() + ". Downloading again makes a second copy.", "good"};
        }
        return null;
    }

    private void picture(GuiGraphicsExtractor g, CommunityModel.DetailState d, int x, int y, int w, int h, double t) {
        Ui.inset(g, x, y, w + 4, h + 4);
        Identifier tex = textureOf(d.big);
        if (tex != null) {
            CommunityModel.Pixels p = d.big.pixels;
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x + 2, y + 2, 0f, 0f, w, h, p.w(), p.h(), p.w(), p.h());
        } else if (d.big.failed) {
            Ui.icon(g, "cross", x + w / 2 - 5, y + h / 2 - 7, false);
        } else {
            Ui.centered(g, ".".repeat(1 + (int) (t * 3) % 3), x + w / 2, y + h / 2 - 4, Ui.DIM);
        }
    }

    private void detailFooter(GuiGraphicsExtractor g, CommunityModel.DetailState d, int px, int fy, int pw, int mx, int my, float inner, double t) {
        int x = px + 10;
        btn(g, "back", x, fy, 46, "Back", true, mx, my);
        x += 50;
        boolean running = d.download == CommunityModel.DownloadPhase.RUNNING;
        if (d.download == CommunityModel.DownloadPhase.DONE && d.saved != null) {
            btn(g, "place", x, fy, 76, "Place it", true, mx, my);
            x += 80;
            btn(g, "mine", x, fy, 90, "In my library", true, mx, my);
            return;
        }
        if (running) {
            Ui.bar(g, "com#dlbar", x, fy + 2, 120, 12, d.total > 0 ? d.received / (double) d.total : 0.0, t);
            Ui.text(g, Words.progress(d.received, d.total), x + 126, fy + 4, Ui.withAlpha(Ui.DIM, inner));
            return;
        }
        String label = d.download == CommunityModel.DownloadPhase.FAILED ? "Try again" : d.existing != null ? "Download again" : "Download";
        btn(g, "download", x, fy, Math.max(76, Ui.font().width(label) + 20), label, CommunityModel.canDownload(d), mx, my);
        x += Math.max(76, Ui.font().width(label) + 20) + 4;
        if (d.existing != null && d.existing.getFileName() != null && java.nio.file.Files.isRegularFile(d.existing)) {
            btn(g, "place", x, fy, 60, "Place it", true, mx, my);
            x += 64;
        }
        if (!d.summary.pageUrl().isEmpty()) btn(g, "website", x, fy, 62, "Website", true, mx, my);
    }

    // ---- input

    /** @return whether the pane took the click */
    public boolean mouseClicked(int mx, int my) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!Ui.inside(mx, my, h.x, h.y, h.w, h.h)) continue;
            if (h.id.equals("card") && !Ui.inside(mx, my, gx, gy, gw, gh)) continue;
            down = h.id;
            downPayload = h.payload;
            Sfx.play(Sfx.PRESS, h.id.equals("card") ? 1.2f : 1.0f);
            return true;
        }
        return false;
    }

    /** @return whether the pane took the release */
    public boolean mouseReleased(int mx, int my) {
        String was = down;
        Object payload = downPayload;
        down = "";
        downPayload = null;
        if (was.isEmpty()) return false;
        for (Hit h : hits) {
            if (h.id.equals(was) && h.payload == payload && Ui.inside(mx, my, h.x, h.y, h.w, h.h)) {
                if (was.equals("card") && !Ui.inside(mx, my, gx, gy, gw, gh)) return true;
                act(was, payload);
                return true;
            }
        }
        return true;
    }

    private void act(String id, Object payload) {
        Sfx.play(Sfx.RELEASE);
        if (id.startsWith("sort:")) {
            model.setSort(id.substring(5));
            scrollTarget = 0;
        } else if (id.startsWith("cat:")) {
            model.setCategory(id.substring(4));
            scrollTarget = 0;
        } else {
            switch (id) {
                case "card" -> model.open((Api.Build) payload);
                case "prev" -> {
                    model.goToPage(model.query().page() - 1);
                    scrollTarget = 0;
                }
                case "next" -> {
                    model.goToPage(model.query().page() + 1);
                    scrollTarget = 0;
                }
                case "retry" -> model.load();
                case "clear" -> {
                    searchText = "";
                    model.setCategory("");
                    model.typed("");
                    model.submitSearch();
                    clearSearchBox = true;
                }
                case "consent:on" -> Community.setConsent(CommunityConfig.ON);
                case "consent:off" -> {
                    Community.setConsent(CommunityConfig.OFF);
                    host.say("Community is off. Nothing was sent.");
                }
                case "settings" -> host.openSettings();
                case "back" -> model.close();
                case "download" -> model.download();
                case "retryDetail" -> model.retryDetail();
                case "mine" -> {
                    model.close();
                    host.rescan();
                    host.showMine();
                }
                case "place" -> {
                    CommunityModel.DetailState d = model.detail();
                    Path file = d == null ? null : d.saved != null ? d.saved : d.existing;
                    if (file != null) host.place(file);
                }
                case "website" -> openPage();
                default -> {
                }
            }
        }
        CommunityModel.DetailState d = model == null ? null : model.detail();
        if (d != null && d.download == CommunityModel.DownloadPhase.DONE) host.rescan();
    }

    /** Set when "Clear filters" wants the Library's search box emptied too. */
    public boolean clearSearchBox;

    private void openPage() {
        CommunityModel.DetailState d = model.detail();
        if (d == null) return;
        String page = d.summary.pageUrl();
        // only a page on the site in use is opened, never an address the answer made up
        String path = model.base() == null ? null : Community.client(model.base()).net().localPath(page);
        if (path == null) {
            host.say("That page is not on the community website.");
            return;
        }
        try {
            com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(model.base() + path));
        } catch (RuntimeException e) {
            host.say("Could not open the page.");
        }
    }

    public boolean mouseScrolled(double scrollY) {
        if (model != null && model.detail() != null) {
            detailScrollTarget = Math.max(0, detailScrollTarget - (float) scrollY * 24);
            return true;
        }
        Api.Listing l = model == null ? null : model.listing();
        if (l == null) return false;
        int max = Math.max(0, contentH(l.items().size()) - gh);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget - (float) scrollY * 30));
        return true;
    }

    // ---- the demo drives the real mouse paths through these

    /** Dev demo: the middle of a control on screen (its id as registered: "card" needs {@link #cardCenter}), or null. */
    public int[] anchor(String id) {
        for (Hit h : hits) if (h.id.equals(id) && !id.equals("card")) return new int[]{h.x + h.w / 2, h.y + h.h / 2};
        return null;
    }

    /** Dev demo: the middle of the card of a build on screen, or null. */
    public int[] cardCenter(String buildId) {
        for (Hit h : hits) if (h.id.equals("card") && ((Api.Build) h.payload).id().equals(buildId)) return new int[]{h.x + h.w / 2, h.y + h.h / 2};
        return null;
    }

    /** Dev demo: the ids of every control drawn last frame. */
    public List<String> controls() {
        List<String> out = new ArrayList<>();
        for (Hit h : hits) out.add(h.id);
        return out;
    }

    /** Dev demo: how many preview pictures have a texture. */
    public int textureCount() {
        return textures.size();
    }

    public int visibleCards() {
        int n = 0;
        for (Hit h : hits) if (h.id.equals("card") && h.y + h.h > gy && h.y < gy + gh) n++;
        return n;
    }
}
