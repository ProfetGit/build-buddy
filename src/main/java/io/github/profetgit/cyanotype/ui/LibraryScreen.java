package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.BlueprintLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Every blueprint the player has, as a grid of cards: a small preview that turns while the mouse is on it, the name, the
 * size and the number of blocks. Type to search, pick a sort, click a card to start placing it, drop a .litematic file on
 * the window to add it (PRD 7.1). With no files it says how to get some.
 */
public final class LibraryScreen extends Screen {
    private static final int CARD_W = 74, CARD_H = 96, GAP = 6;

    private final LibraryModel model = new LibraryModel();
    private final Map<LibraryModel.Entry, Identifier> textures = new HashMap<>();
    private final Map<LibraryModel.Entry, DynamicTexture> owned = new HashMap<>();
    private EditBox search;
    private LibraryModel.Sort sort = LibraryModel.Sort.RECENT;
    private List<LibraryModel.Entry> view = List.of();
    private String lastQuery = null;
    private LibraryModel.Sort lastSort;
    private int lastKnown = -1;
    private float scroll, scrollTarget;
    private long openedNs = System.nanoTime(), toastNs;
    private String toast = "";
    private String down = "";
    private LibraryModel.Entry downCard;

    public LibraryScreen() {
        super(Component.literal("Library"));
    }

    @Override
    protected void init() {
        model.rescan();
        search = new EditBox(font, 0, 0, 118, 12, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(40);
        search.setTextColor(Ui.WHITE);
        addRenderableWidget(search);
        setInitialFocus(search);
        Sfx.play(Sfx.OPEN);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        g.fillGradient(0, 0, width, height, 0x30000000, 0x70000000);
    }

    @Override
    public void removed() {
        for (DynamicTexture t : owned.values()) t.close();
        for (Identifier id : textures.values()) minecraft.getTextureManager().release(id);
        owned.clear();
        textures.clear();
    }

    private int panelW() {
        return Math.min(width - 24, 372);
    }

    private int panelH() {
        return Math.min(height - 24, 300);
    }

    private int px() {
        return (width - panelW()) / 2;
    }

    private int py() {
        return (height - panelH()) / 2;
    }

    private int gridX() {
        return px() + 10;
    }

    private int gridY() {
        return py() + 42;
    }

    private int gridW() {
        return panelW() - 20;
    }

    private int gridH() {
        return panelH() - 42 - 30;
    }

    private int cols() {
        return Math.max(1, (gridW() + GAP) / (CARD_W + GAP));
    }

    private int contentH() {
        int rows = (view.size() + cols() - 1) / cols();
        return rows == 0 ? 0 : rows * (CARD_H + GAP) - GAP;
    }

    private void refresh() {
        String q = search == null ? "" : search.getValue();
        int known = model.known();
        boolean moved = !q.equals(lastQuery) || sort != lastSort;
        if (moved || known != lastKnown) {
            lastQuery = q;
            lastSort = sort;
            lastKnown = known;
            view = model.view(q, sort);
            if (moved) scrollTarget = 0;
        }
        // sorting by size needs to know the sizes: ask for everything, nearest the top first
        if (sort == LibraryModel.Sort.SIZE) for (LibraryModel.Entry e : model.all()) model.request(e);
    }

    private Identifier textureOf(LibraryModel.Entry e) {
        Identifier have = textures.get(e);
        if (have != null) return have;
        int[][] frames = e.thumbs;
        if (frames == null) return null;
        NativeImage img = new NativeImage(LibraryModel.THUMB_W * LibraryModel.THUMB_FRAMES, LibraryModel.THUMB_H, true);
        for (int f = 0; f < frames.length; f++) {
            for (int y = 0; y < LibraryModel.THUMB_H; y++) {
                for (int x = 0; x < LibraryModel.THUMB_W; x++) img.setPixel(f * LibraryModel.THUMB_W + x, y, frames[f][y * LibraryModel.THUMB_W + x]);
            }
        }
        Skin.Smooth tex = new Skin.Smooth("Cyanotype library preview", img, false);
        Identifier id = Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, "library/" + Integer.toHexString(System.identityHashCode(e)) + "_" + System.nanoTime());
        minecraft.getTextureManager().register(id, tex);
        owned.put(e, tex);
        textures.put(e, id);
        return id;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        refresh();
        double open = Motion.reduced() ? 1 : Math.min(1, (System.nanoTime() - openedNs) / 1e9 / 0.25);
        int pw = panelW(), ph = panelH(), px = px(), py = py();
        float inner = Ui.panelOpening(g, px, py, pw, ph, open);
        if (inner < 0.05f) return;
        double t = System.nanoTime() / 1e9;

        // header
        Ui.text(g, "Library", px + 10, py + 8, Ui.withAlpha(Ui.LINE, inner));
        int sbx = px + pw - 10 - 124;
        Ui.inset(g, sbx, py + 5, 124, 14);
        search.setX(sbx + 4);
        search.setY(py + 7);
        search.extractRenderState(g, mx, my, partial);
        if (search.getValue().isEmpty()) Ui.text(g, "Name or tag", sbx + 12, py + 8, Ui.withAlpha(Ui.DIM, inner * 0.8f));

        // sort tabs
        int tx = px + 10;
        for (LibraryModel.Sort s : LibraryModel.Sort.values()) {
            String label = switch (s) {
                case RECENT -> "Recent";
                case NAME -> "Name";
                case SIZE -> "Size";
            };
            Ui.tab(g, "lib#tab" + s, tx, py + 26, 44, label, sort == s, mx, my);
            tx += 46;
        }

        // the grid
        scroll = Motion.follow("lib#scroll", scrollTarget, 0.06);
        int gx = gridX(), gy = gridY(), gw = gridW(), gh = gridH();
        g.enableScissor(gx - 2, gy, gx + gw + 2, gy + gh);
        int cols = cols();
        int left = (gw - (cols * (CARD_W + GAP) - GAP)) / 2;
        LibraryModel.Entry hover = null;
        for (int i = 0; i < view.size(); i++) {
            LibraryModel.Entry e = view.get(i);
            int cx = gx + left + (i % cols) * (CARD_W + GAP), cy = gy + (i / cols) * (CARD_H + GAP) - Math.round(scroll);
            if (cy + CARD_H < gy - 2 || cy > gy + gh) continue;
            model.request(e);
            boolean over = Ui.inside(mx, my, cx, cy, CARD_W, CARD_H) && Ui.inside(mx, my, gx, gy, gw, gh);
            if (over) hover = e;
            card(g, e, cx, cy, over, down.equals("card") && downCard == e, t, inner);
        }
        g.disableScissor();
        if (view.isEmpty()) emptyState(g, gx, gy, gw, gh, inner);

        // a thin scroll mark when the grid is longer than the panel
        int ch = contentH();
        if (ch > gh) {
            int barH = Math.max(12, gh * gh / ch);
            int barY = gy + (int) ((gh - barH) * (scroll / (float) (ch - gh)));
            g.fill(px + pw - 7, gy, px + pw - 5, gy + gh, Ui.withAlpha(Ui.DEEP, 0.7f));
            g.fill(px + pw - 7, barY, px + pw - 5, barY + barH, Ui.withAlpha(Ui.CYAN, 0.9f));
        }

        // footer
        int fy = py + ph - 24;
        boolean overFolder = Ui.button(g, "lib#folder", px + 10, fy, 86, 16, "Open folder", "folder", mx, my, down.equals("folder"), true);
        boolean overClose = Ui.button(g, "lib#close", px + pw - 10 - 52, fy, 52, 16, "Close", null, mx, my, down.equals("close"), true);
        String count = model.all().size() + (model.all().size() == 1 ? " blueprint" : " blueprints") + (view.size() != model.all().size() ? "  (" + view.size() + " shown)" : "");
        Ui.text(g, count, px + 104, fy + 4, Ui.withAlpha(Ui.DIM, inner));
        if (!toast.isEmpty() && System.nanoTime() - toastNs < 3_500_000_000L) {
            Ui.centered(g, toast, px + pw / 2, py + 8, Ui.withAlpha(Ui.WARN, inner));
        }
        if (hover != null && hover.info != null && !hover.info.author().isBlank()) {
            Ui.tooltip(g, mx, my, hover.title() + "\nby " + hover.info.author() + (hover.info.description().isBlank() ? "" : "\n" + hover.info.description()));
        } else if (hover != null && hover.error != null) {
            Ui.tooltip(g, mx, my, "Cannot read this file:\n" + hover.error);
        }
    }

    private void card(GuiGraphicsExtractor g, LibraryModel.Entry e, int x, int y, boolean over, boolean pressed, double t, float inner) {
        String key = "lib#card" + System.identityHashCode(e);
        float hv = Motion.hover(key, over), pr = Motion.press(key, pressed);
        int lift = Math.round(hv) - Math.round(pr);
        y -= lift;
        Ui.inset(g, x, y, CARD_W, CARD_H);
        // the preview
        int tx = x + 5, ty = y + 5;
        Identifier tex = textureOf(e);
        if (tex != null) {
            int frame = over && !Motion.reduced() ? (int) (t * 4) % LibraryModel.THUMB_FRAMES : 0;
            g.blit(RenderPipelines.GUI_TEXTURED, tex, tx, ty, (float) (frame * LibraryModel.THUMB_W), 0f, LibraryModel.THUMB_SHOW_W, LibraryModel.THUMB_SHOW_H,
                LibraryModel.THUMB_W, LibraryModel.THUMB_H, LibraryModel.THUMB_W * LibraryModel.THUMB_FRAMES, LibraryModel.THUMB_H);
        } else if (e.error != null) {
            Ui.icon(g, "cross", tx + 25, ty + 17, false);
        } else {
            int dots = 1 + (int) (t * 3) % 3;
            Ui.centered(g, ".".repeat(dots), tx + LibraryModel.THUMB_SHOW_W / 2, ty + 18, Ui.DIM);
        }
        // the words
        int ly = y + 57;
        for (String line : Ui.wrap(e.title(), CARD_W - 8, 2)) {
            Ui.text(g, line, x + 5, ly, Ui.LINE);
            ly += 9;
        }
        LibraryModel.Info i = e.info;
        if (i != null) {
            Ui.text(g, i.sx() + "x" + i.sy() + "x" + i.sz(), x + 5, y + 76, Ui.CYAN);
            String blocks = i.blocks() >= 10000 ? String.format(Locale.ROOT, "%.1fk", i.blocks() / 1000.0) : String.valueOf(i.blocks());
            Ui.text(g, blocks + " blocks" + (i.unknown() > 0 ? " !" : ""), x + 5, y + 85, i.unknown() > 0 ? Ui.WARN : Ui.DIM);
        } else if (e.error != null) {
            Ui.text(g, "Cannot read", x + 5, y + 76, Ui.BAD);
        } else {
            Ui.text(g, "Reading", x + 5, y + 76, Ui.DIM);
        }
        if (hv > 0.02f) Ui.marching(g, x - 1, y - 1, CARD_W + 2, CARD_H + 2, Ui.withAlpha(Ui.CYAN, hv), t);
    }

    private void emptyState(GuiGraphicsExtractor g, int x, int y, int w, int h, float a) {
        int cx = x + w / 2, cy = y + h / 2 - 20;
        Ui.icon(g, "folder", cx - 7, cy - 22, false);
        if (model.all().isEmpty()) {
            Ui.centered(g, "No blueprints yet", cx, cy, Ui.withAlpha(Ui.LINE, a));
            Ui.centered(g, "Drop a .litematic file onto the window,", cx, cy + 14, Ui.withAlpha(Ui.DIM, a));
            Ui.centered(g, "or put one in the blueprints folder.", cx, cy + 25, Ui.withAlpha(Ui.DIM, a));
        } else {
            Ui.centered(g, "Nothing matches that", cx, cy, Ui.withAlpha(Ui.LINE, a));
            Ui.centered(g, "Clear the search to see everything.", cx, cy + 14, Ui.withAlpha(Ui.DIM, a));
        }
    }

    // ---- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int mx = (int) event.x(), my = (int) event.y();
        int pw = panelW(), px = px(), py = py(), fy = py + panelH() - 24;
        int tx = px + 10;
        for (LibraryModel.Sort s : LibraryModel.Sort.values()) {
            if (Ui.inside(mx, my, tx, py + 26, 44, 13)) {
                sort = s;
                Sfx.play(Sfx.PRESS, 1.1f);
                return true;
            }
            tx += 46;
        }
        if (Ui.inside(mx, my, px + 10, fy, 86, 16)) {
            down = "folder";
            Sfx.play(Sfx.PRESS);
            return true;
        }
        if (Ui.inside(mx, my, px + pw - 10 - 52, fy, 52, 16)) {
            down = "close";
            Sfx.play(Sfx.PRESS);
            return true;
        }
        LibraryModel.Entry hit = cardAt(mx, my);
        if (hit != null) {
            down = "card";
            downCard = hit;
            Sfx.play(Sfx.PRESS, 1.2f);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mx = (int) event.x(), my = (int) event.y();
        int pw = panelW(), px = px(), fy = py() + panelH() - 24;
        String was = down;
        LibraryModel.Entry card = downCard;
        down = "";
        downCard = null;
        if (was.equals("folder") && Ui.inside(mx, my, px + 10, fy, 86, 16)) {
            Sfx.play(Sfx.RELEASE);
            try {
                Files.createDirectories(BlueprintLibrary.ownDir());
            } catch (IOException ignored) {
                // opening it will say
            }
            com.mojang.blaze3d.Blaze3D.openPath(BlueprintLibrary.ownDir());
            return true;
        }
        if (was.equals("close") && Ui.inside(mx, my, px + pw - 10 - 52, fy, 52, 16)) {
            Sfx.play(Sfx.CLOSE);
            onClose();
            return true;
        }
        if (was.equals("card") && card != null && cardAt(mx, my) == card) {
            choose(card);
            return true;
        }
        return super.mouseReleased(event);
    }

    private LibraryModel.Entry cardAt(int mx, int my) {
        int gx = gridX(), gy = gridY(), gw = gridW(), gh = gridH();
        if (!Ui.inside(mx, my, gx, gy, gw, gh)) return null;
        int cols = cols();
        int left = (gw - (cols * (CARD_W + GAP) - GAP)) / 2;
        for (int i = 0; i < view.size(); i++) {
            int cx = gx + left + (i % cols) * (CARD_W + GAP), cy = gy + (i / cols) * (CARD_H + GAP) - Math.round(scroll);
            if (Ui.inside(mx, my, cx, cy, CARD_W, CARD_H)) return view.get(i);
        }
        return null;
    }

    private void choose(LibraryModel.Entry e) {
        if (e.error != null) {
            Sfx.play(Sfx.ERROR);
            say("Cannot read " + e.fileName + ": " + e.error);
            return;
        }
        if (!e.loaded()) return;
        Sfx.play(Sfx.RELEASE);
        onClose();
        String title = e.title(), ref = e.ref();
        BlueprintLibrary.load(e.file, bp -> Interaction.startPlacing(title, bp, ref), why -> Interaction.say(minecraft, "Could not open " + e.fileName + ": " + why));
    }

    private void say(String text) {
        toast = text;
        toastNs = System.nanoTime();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int max = Math.max(0, contentH() - gridH());
        scrollTarget = Math.max(0, Math.min(max, scrollTarget - (float) scrollY * 30));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return super.keyPressed(event);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        int added = 0, skipped = 0;
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
                skipped++;
                continue;
            }
            try {
                Files.createDirectories(BlueprintLibrary.ownDir());
                Path target = BlueprintLibrary.ownDir().resolve(name);
                for (int n = 2; Files.exists(target); n++) target = BlueprintLibrary.ownDir().resolve(name.replaceFirst("(?i)\\.litematic$", "") + " " + n + ".litematic");
                Files.copy(f, target, StandardCopyOption.COPY_ATTRIBUTES);
                added++;
            } catch (IOException e) {
                Cyanotype.LOG.warn("Cannot add {}", f, e);
                skipped++;
            }
        }
        model.rescan();
        lastQuery = null;
        if (added > 0) Sfx.play(Sfx.COMPLETE);
        else Sfx.play(Sfx.ERROR);
        say(added > 0 ? "Added " + added + (added == 1 ? " blueprint" : " blueprints") + (skipped > 0 ? ", skipped " + skipped : "") : "Only .litematic files can be added.");
    }

    /** Dev demo: types into the search box. */
    public void searchFor(String text) {
        search.setValue(text);
    }

    /** Dev demo: picks a sort by its name. */
    public void sortBy(String name) {
        sort = LibraryModel.Sort.valueOf(name);
    }

    /** Dev demo: the entries as laid out (to click on). */
    public List<LibraryModel.Entry> entries() {
        return view;
    }

    /** Dev demo: the middle of a card on screen, or null. */
    public int[] cardCenter(int index) {
        if (index < 0 || index >= view.size()) return null;
        int cols = cols();
        int left = (gridW() - (cols * (CARD_W + GAP) - GAP)) / 2;
        return new int[]{gridX() + left + (index % cols) * (CARD_W + GAP) + CARD_W / 2, gridY() + (index / cols) * (CARD_H + GAP) - Math.round(scroll) + CARD_H / 2};
    }
}
