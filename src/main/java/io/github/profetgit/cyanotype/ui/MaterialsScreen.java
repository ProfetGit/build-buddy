package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.CellHighlight;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.verify.Verifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What the build still needs (PRD 7.5): one row per material with what is needed, what is in the inventory and what is
 * left to get ("3 stacks + 12"), the biggest shortage first. Click a row to light up where it goes in the world, pick a
 * layer to see only what that layer takes, group wood and dye variants, and copy or save the shopping list.
 */
public final class MaterialsScreen extends Screen {
    private static final int ROW_H = 20, ICON = 16;

    private Materials.Result result = new Materials.Result(List.of(), 0, 0);
    private final Map<Item, ItemStack> stacks = new HashMap<>();
    private int layerLo = -1, layerHi = -1;
    private boolean lastGroup;
    private boolean dirty = true;
    private long computedNs;
    private long computedChanges = -1;
    private float scroll, scrollTarget;
    private final long openedNs = System.nanoTime();
    private long toastNs;
    private String toast = "";
    private String down = "";
    private String downRow = "";

    public MaterialsScreen() {
        super(Component.literal("Materials"));
    }

    @Override
    protected void init() {
        Placement p = Placements.active();
        if (p != null && p.layered()) {
            layerLo = p.layerLo;
            layerHi = p.layerHi;
        }
        lastGroup = Settings.get().groupVariants;
        Sfx.play(Sfx.OPEN);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        g.fillGradient(0, 0, width, height, 0x30000000, 0x70000000);
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

    private int listY() {
        return py() + 56;
    }

    private int listH() {
        return panelH() - 56 - 40;
    }

    private Placement placement() {
        Placement p = Placements.active();
        return p != null && p.ready() ? p : null;
    }

    private Verifier verifier() {
        Placement p = placement();
        return p != null && p.locked ? GhostRenderer.verifierOf(p) : null;
    }

    // ---- the numbers

    private void refresh() {
        Verifier v = verifier();
        if (v == null) {
            result = new Materials.Result(List.of(), 0, 0);
            return;
        }
        boolean group = Settings.get().groupVariants;
        if (group != lastGroup) {
            lastGroup = group;
            dirty = true;
        }
        long now = System.nanoTime();
        // the inventory and the build change all the time: look again a few times a second
        if (!dirty && v.changes() == computedChanges && now - computedNs < 250_000_000L) return;
        dirty = false;
        computedNs = now;
        computedChanges = v.changes();
        Map<Item, Integer> have = new HashMap<>();
        if (minecraft != null && minecraft.player != null) {
            Inventory inv = minecraft.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty()) have.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        result = Materials.compute(v, layerLo, layerHi, item -> have.getOrDefault(item, 0), group);
        int max = Math.max(0, contentH() - listH());
        scrollTarget = Math.min(scrollTarget, max);
    }

    private int contentH() {
        return result.rows().size() * ROW_H;
    }

    private String scope() {
        if (layerLo < 0 && layerHi < 0) return "";
        int lo = Math.max(0, layerLo) + 1, hi = (layerHi < 0 ? placementHeight() - 1 : layerHi) + 1;
        return lo == hi ? "layer " + lo : "layers " + lo + "-" + hi;
    }

    private int placementHeight() {
        Placement p = placement();
        return p == null ? 0 : p.sizeY();
    }

    private String layerTabLabel() {
        if (layerLo < 0 && layerHi < 0) return "One layer";
        int lo = Math.max(0, layerLo) + 1, hi = (layerHi < 0 ? placementHeight() - 1 : layerHi) + 1;
        return lo == hi ? "Layer " + lo : "Layers " + lo + "-" + hi;
    }

    private boolean filtered() {
        return layerLo >= 0 || layerHi >= 0;
    }

    private void setLayers(int lo, int hi) {
        layerLo = lo;
        layerHi = hi;
        dirty = true;
        scrollTarget = 0;
        CellHighlight.clear();
    }

    private void layerOn() {
        Verifier v = verifier();
        int start = 0;
        if (v != null) {
            for (int l = 0; l < v.height; l++) {
                if (v.layerCount(l, Verifier.MISSING) + v.layerCount(l, Verifier.WRONG) > 0) {
                    start = l;
                    break;
                }
            }
        }
        setLayers(start, start);
    }

    private void layerStep(int delta) {
        if (!filtered()) return;
        int h = placementHeight(), lo = Math.max(0, layerLo), hi = layerHi < 0 ? h - 1 : layerHi;
        int thick = hi - lo;
        int nlo = Math.max(0, Math.min(h - 1 - thick, lo + delta));
        if (nlo != lo) {
            Sfx.play(Sfx.SNAP, 0.9f + 0.5f * nlo / Math.max(1, h));
            setLayers(nlo, nlo + thick);
        }
    }

    // ---- layout of the header

    private int tabAllW() {
        return 52;
    }

    private int tabLayerW() {
        return Math.max(52, Ui.font().width(layerTabLabel()) + 14);
    }

    private int tabX2() {
        return px() + 10 + tabAllW() + 2;
    }

    private int stepX() {
        return tabX2() + tabLayerW() + 4;
    }

    private int checkX() {
        return px() + panelW() - 10 - 12 - Ui.font().width("Group variants");
    }

    // ---- drawing

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
        Placement p = placement();
        Verifier v = verifier();

        Ui.text(g, "Materials", px + 10, py + 8, Ui.withAlpha(Ui.LINE, inner));
        if (p != null) Ui.right(g, Ui.fit(p.name, pw - 120), px + pw - 10, py + 8, Ui.withAlpha(Ui.DIM, inner));

        int fy = py + ph - 24;
        if (p == null) {
            emptyState(g, mx, my, "No blueprint placed yet", "Pick one in the Library, then put it in the world.", "Open Library", inner);
        } else if (v == null) {
            emptyState(g, mx, my, "Lock the blueprint in place first", "The list starts counting once it is locked in the world.", null, inner);
        } else {
            header(g, mx, my, inner);
            if (!result.rows().isEmpty()) columns(g, px, py, pw, inner);
            list(g, mx, my, t, inner, v);
        }

        // footer
        boolean can = v != null && !result.rows().isEmpty();
        Ui.button(g, "mat#copy", px + 10, fy, 66, 16, "Copy list", null, mx, my, down.equals("copy"), can);
        Ui.button(g, "mat#save", px + 80, fy, 66, 16, "Save file", null, mx, my, down.equals("save"), can);
        Ui.button(g, "mat#close", px + pw - 10 - 52, fy, 52, 16, "Close", null, mx, my, down.equals("close"), true);
        String line = v == null ? "" : summary(v);
        Ui.text(g, Ui.fit(line, pw - 20 - 66 - 66 - 52 - 24), px + 154, fy + 4, Ui.withAlpha(Ui.DIM, inner));
        long age = System.nanoTime() - toastNs;
        if (!toast.isEmpty() && age < 4_000_000_000L) {
            // a chip over the foot of the list, fading out at the end
            float a = inner * (age > 3_400_000_000L ? (float) (1 - (age - 3_400_000_000L) / 600e6) : 1f);
            int tw = Ui.font().width(toast) + 12, tx = px + (pw - tw) / 2, ty = fy - 18;
            g.fill(tx, ty, tx + tw, ty + 13, Ui.withAlpha(Ui.DEEP, 0.92f * a));
            g.outline(tx, ty, tw, 13, Ui.withAlpha(Ui.WARN, a));
            Ui.text(g, toast, tx + 6, ty + 3, Ui.withAlpha(Ui.WARN, a));
        }
    }

    private String summary(Verifier v) {
        StringBuilder sb = new StringBuilder();
        if (!v.settled()) sb.append("Counting...  ");
        int kinds = result.rows().size();
        if (kinds == 0) return sb.append(v.settled() ? "Nothing left to build" : "").toString();
        sb.append(kinds).append(kinds == 1 ? " material" : " materials").append(", ").append(result.blocks()).append(" blocks");
        if (result.noItem() > 0) sb.append(", ").append(result.noItem()).append(" with no item");
        return sb.toString();
    }

    private void header(GuiGraphicsExtractor g, int mx, int my, float inner) {
        int py = py();
        Ui.tab(g, "mat#tabAll", px() + 10, py + 25, tabAllW(), "All layers", !filtered(), mx, my);
        Ui.tab(g, "mat#tabOne", tabX2(), py + 25, tabLayerW(), layerTabLabel(), filtered(), mx, my);
        Ui.button(g, "mat#down", stepX(), py + 24, 16, 15, "-", null, mx, my, down.equals("down"), filtered());
        Ui.button(g, "mat#up", stepX() + 18, py + 24, 16, 15, "+", null, mx, my, down.equals("up"), filtered());
        boolean on = Settings.get().groupVariants;
        int cx = checkX();
        boolean over = Ui.inside(mx, my, cx - 2, py + 24, px() + panelW() - 8 - cx, 15);
        Ui.checkbox(g, "mat#group", cx, py + 27, on, over);
        Ui.text(g, "Group variants", cx + 14, py + 28, Ui.withAlpha(over ? Ui.WHITE : Ui.LINE, inner));
        if (over) Ui.tooltip(g, mx, my, "Count every wood and every dye as one kind,\nso any planks you have cover the planks you need.");
    }

    private void columns(GuiGraphicsExtractor g, int px, int py, int pw, float inner) {
        int cl = px + 10, y = py + 44;
        int dim = Ui.withAlpha(Ui.DIM, inner);
        Ui.text(g, "Material", cl + 24, y, dim);
        Ui.right(g, "Need", cl + needR(), y, dim);
        Ui.right(g, "Have", cl + haveR(), y, dim);
        Ui.text(g, "To get", cl + toGetL(), y, dim);
        g.fill(cl, y + 10, px + pw - 10, y + 11, Ui.withAlpha(Ui.DIM, 0.35f * inner));
    }

    private int needR() {
        return 176;
    }

    private int haveR() {
        return 216;
    }

    private int toGetL() {
        return 228;
    }

    private ItemStack stack(Item item) {
        return stacks.computeIfAbsent(item, ItemStack::new);
    }

    private void list(GuiGraphicsExtractor g, int mx, int my, double t, float inner, Verifier v) {
        List<Materials.Row> rows = result.rows();
        int cl = px() + 10, cw = panelW() - 20, ly = listY(), lh = listH();
        scroll = Motion.follow("mat#scroll", scrollTarget, 0.06);
        if (rows.isEmpty()) {
            nothing(g, cl, ly, cw, lh, v, inner);
            return;
        }
        g.enableScissor(cl - 2, ly, cl + cw + 2, ly + lh);
        Materials.Row hover = null;
        for (int i = 0; i < rows.size(); i++) {
            Materials.Row r = rows.get(i);
            int y = ly + i * ROW_H - Math.round(scroll);
            if (y + ROW_H < ly || y > ly + lh) continue;
            boolean over = Ui.inside(mx, my, cl, y, cw, ROW_H) && Ui.inside(mx, my, cl, ly, cw, lh);
            if (over) hover = r;
            row(g, r, cl, y, cw, over, down.equals("row") && downRow.equals(r.key()), t, inner, i);
        }
        g.disableScissor();
        int ch = contentH();
        if (ch > lh) {
            int barH = Math.max(12, lh * lh / ch);
            int barY = ly + (int) ((lh - barH) * (scroll / (float) (ch - lh)));
            int bx = px() + panelW() - 7;
            g.fill(bx, ly, bx + 2, ly + lh, Ui.withAlpha(Ui.DEEP, 0.7f));
            g.fill(bx, barY, bx + 2, barY + barH, Ui.withAlpha(Ui.CYAN, 0.9f));
        }
        if (hover != null) {
            String tip = "Needs " + hover.needed() + ", you have " + hover.have() + (hover.group() ? "\n" + hover.variants() + " kinds counted together" : "")
                + "\n" + (CellHighlight.showing(hover.key()) ? "Click to stop showing it" : "Click to show where it goes");
            Ui.tooltip(g, mx, my, tip);
        }
    }

    private void row(GuiGraphicsExtractor g, Materials.Row r, int x, int y, int w, boolean over, boolean pressed, double t, float inner, int index) {
        String key = "mat#row" + r.key();
        float hv = Motion.hover(key, over), pr = Motion.press(key, pressed);
        boolean selected = CellHighlight.showing(r.key());
        if (index % 2 == 0) g.fill(x, y, x + w, y + ROW_H, Ui.withAlpha(0xFFFFFF, 0.04f));
        if (hv > 0.01f) g.fill(x, y, x + w, y + ROW_H, Ui.withAlpha(Ui.CYAN, 0.10f * hv + 0.08f * pr));
        int iy = y + (ROW_H - ICON) / 2 + Math.round(pr);
        g.item(stack(r.icon()), x + 4, iy);
        Ui.text(g, Ui.fit(r.name(), 118), x + 24, y + 6, Ui.withAlpha(Ui.LINE, inner));
        Ui.right(g, String.valueOf(r.needed()), x + needR(), y + 6, Ui.withAlpha(Ui.LINE, inner));
        Ui.right(g, String.valueOf(r.have()), x + haveR(), y + 6, Ui.withAlpha(r.have() >= r.needed() ? Ui.GOOD : Ui.LINE, inner));
        if (r.missing() > 0) {
            Ui.text(g, Ui.fit(r.missingText(), w - toGetL() - 4), x + toGetL(), y + 6, Ui.withAlpha(Ui.WARN, inner));
        } else {
            Ui.icon(g, "check", x + toGetL(), y + 3, false, 14, inner);
            Ui.text(g, "enough", x + toGetL() + 17, y + 6, Ui.withAlpha(Ui.GOOD, inner));
        }
        if (selected) Ui.marching(g, x, y, w, ROW_H, Ui.withAlpha(Ui.WARN, inner), t);
    }

    private void nothing(GuiGraphicsExtractor g, int x, int y, int w, int h, Verifier v, float a) {
        int cx = x + w / 2, cy = y + h / 2 - 16;
        if (!v.settled()) {
            Ui.centered(g, "Counting the build" + ".".repeat(1 + (int) (System.nanoTime() / 400_000_000L) % 3), cx, cy, Ui.withAlpha(Ui.DIM, a));
            return;
        }
        Ui.icon(g, "check", cx - 14, cy - 28, false, 28, a);
        Ui.centered(g, filtered() ? "Nothing left to build on " + scope() : "Nothing left to build", cx, cy + 6, Ui.withAlpha(Ui.GOOD, a));
        if (filtered()) Ui.centered(g, "Pick All layers to see the whole build.", cx, cy + 20, Ui.withAlpha(Ui.DIM, a));
    }

    private void emptyState(GuiGraphicsExtractor g, int mx, int my, String title, String more, String button, float a) {
        int cx = px() + panelW() / 2, cy = py() + panelH() / 2 - 30;
        Ui.icon(g, "list", cx - 14, cy - 36, false, 28, a);
        Ui.centered(g, title, cx, cy, Ui.withAlpha(Ui.LINE, a));
        Ui.centered(g, more, cx, cy + 14, Ui.withAlpha(Ui.DIM, a));
        if (button != null) Ui.button(g, "mat#openlib", cx - 45, cy + 34, 90, 16, button, "folder", mx, my, down.equals("openlib"), true);
    }

    // ---- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int mx = (int) event.x(), my = (int) event.y();
        int pw = panelW(), px = px(), py = py(), fy = py + panelH() - 24;
        Placement p = placement();
        Verifier v = verifier();
        if (Ui.inside(mx, my, px + pw - 10 - 52, fy, 52, 16)) return press("close");
        if (v != null && !result.rows().isEmpty()) {
            if (Ui.inside(mx, my, px + 10, fy, 66, 16)) return press("copy");
            if (Ui.inside(mx, my, px + 80, fy, 66, 16)) return press("save");
        }
        if (p == null) {
            int cx = px + pw / 2, cy = py + panelH() / 2 - 30;
            if (Ui.inside(mx, my, cx - 45, cy + 34, 90, 16)) return press("openlib");
            return false;
        }
        if (v == null) return false;
        if (Ui.inside(mx, my, px + 10, py + 25, tabAllW(), 13)) {
            if (filtered()) {
                Sfx.play(Sfx.PRESS, 1.1f);
                setLayers(-1, -1);
            }
            return true;
        }
        if (Ui.inside(mx, my, tabX2(), py + 25, tabLayerW(), 13)) {
            if (!filtered()) {
                Sfx.play(Sfx.PRESS, 1.1f);
                layerOn();
            }
            return true;
        }
        if (filtered() && Ui.inside(mx, my, stepX(), py + 24, 16, 15)) return press("down");
        if (filtered() && Ui.inside(mx, my, stepX() + 18, py + 24, 16, 15)) return press("up");
        int cx = checkX();
        if (Ui.inside(mx, my, cx - 2, py + 24, px + pw - 8 - cx, 15)) {
            Settings.get().groupVariants = !Settings.get().groupVariants;
            Settings.changed();
            Sfx.play(Sfx.PRESS, 1.2f);
            CellHighlight.clear();
            return true;
        }
        Materials.Row hit = rowAt(mx, my);
        if (hit != null) {
            down = "row";
            downRow = hit.key();
            Sfx.play(Sfx.PRESS, 1.2f);
            return true;
        }
        return false;
    }

    private boolean press(String what) {
        down = what;
        Sfx.play(Sfx.PRESS);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mx = (int) event.x(), my = (int) event.y();
        int pw = panelW(), px = px(), py = py(), fy = py + panelH() - 24;
        String was = down, rowKey = downRow;
        down = "";
        downRow = "";
        switch (was) {
            case "close" -> {
                if (Ui.inside(mx, my, px + pw - 10 - 52, fy, 52, 16)) {
                    Sfx.play(Sfx.CLOSE);
                    onClose();
                    return true;
                }
            }
            case "copy" -> {
                if (Ui.inside(mx, my, px + 10, fy, 66, 16)) {
                    copyList();
                    return true;
                }
            }
            case "save" -> {
                if (Ui.inside(mx, my, px + 80, fy, 66, 16)) {
                    saveList();
                    return true;
                }
            }
            case "down" -> {
                if (Ui.inside(mx, my, stepX(), py + 24, 16, 15)) {
                    layerStep(-1);
                    return true;
                }
            }
            case "up" -> {
                if (Ui.inside(mx, my, stepX() + 18, py + 24, 16, 15)) {
                    layerStep(1);
                    return true;
                }
            }
            case "openlib" -> {
                Sfx.play(Sfx.RELEASE);
                onClose();
                minecraft.gui.setScreen(new LibraryScreen());
                return true;
            }
            case "row" -> {
                Materials.Row r = rowAt(mx, my);
                if (r != null && r.key().equals(rowKey)) {
                    toggleHighlight(r);
                    return true;
                }
            }
            default -> {
            }
        }
        return super.mouseReleased(event);
    }

    private Materials.Row rowAt(int mx, int my) {
        int cl = px() + 10, cw = panelW() - 20, ly = listY(), lh = listH();
        if (!Ui.inside(mx, my, cl, ly, cw, lh)) return null;
        int i = (my - ly + Math.round(scroll)) / ROW_H;
        List<Materials.Row> rows = result.rows();
        return i >= 0 && i < rows.size() ? rows.get(i) : null;
    }

    private void toggleHighlight(Materials.Row r) {
        Verifier v = verifier();
        if (v == null) return;
        if (CellHighlight.showing(r.key())) {
            CellHighlight.clear();
            Sfx.play(Sfx.RELEASE, 0.9f);
            say("Stopped showing " + Ui.fit(r.name(), 150));
            return;
        }
        Interaction.reveal();
        CellHighlight.show(v, r.slots(), r.key(), r.name(), layerLo, layerHi);
        Sfx.play(Sfx.RELEASE, 1.15f);
        say("Gold boxes in the world: where " + Ui.fit(r.name(), 120) + " goes");
    }

    /** The list as text: what is still to get, for the layers on show. */
    public String shoppingText() {
        Placement p = placement();
        return Materials.shoppingList(p == null ? "" : p.name, scope(), result.rows());
    }

    private void copyList() {
        minecraft.keyboardHandler.setClipboard(shoppingText());
        Sfx.play(Sfx.COMPLETE, 1.2f);
        say("Copied the list to the clipboard");
    }

    public void saveList() {
        Placement p = placement();
        String name = (p == null ? "list" : p.name).replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
        if (name.isEmpty()) name = "list";
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cyanotype").resolve("shopping").resolve(name + ".txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, shoppingText());
            Sfx.play(Sfx.COMPLETE, 1.2f);
            say("Saved as " + Ui.fit(file.getFileName().toString(), 200));
        } catch (IOException e) {
            Cyanotype.LOG.warn("Cannot save {}", file, e);
            Sfx.play(Sfx.ERROR);
            say("Could not save the list: " + e.getMessage());
        }
    }

    private void say(String text) {
        toast = text;
        toastNs = System.nanoTime();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int max = Math.max(0, contentH() - listH());
        scrollTarget = Math.max(0, Math.min(max, scrollTarget - (float) scrollY * ROW_H * 1.5f));
        return true;
    }

    // ---- dev demo

    public List<Materials.Row> rows() {
        return result.rows();
    }

    public Materials.Result result() {
        return result;
    }

    public int[] rowCenter(int index) {
        if (index < 0 || index >= result.rows().size()) return null;
        return new int[]{px() + panelW() / 2, listY() + index * ROW_H - Math.round(scroll) + ROW_H / 2};
    }

    public void layers(int lo, int hi) {
        setLayers(lo, hi);
    }

    public void clickRow(int index) {
        toggleHighlight(result.rows().get(index));
    }

    public void group(boolean on) {
        Settings.get().groupVariants = on;
        Settings.changed();
    }
}
