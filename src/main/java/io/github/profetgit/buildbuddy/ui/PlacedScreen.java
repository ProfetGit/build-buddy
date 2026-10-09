package io.github.profetgit.buildbuddy.ui;

import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.interaction.Interaction;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.PlacementStore;
import io.github.profetgit.buildbuddy.placement.Placements;
import io.github.profetgit.buildbuddy.verify.Counts;
import io.github.profetgit.buildbuddy.verify.Verifier;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The blueprints placed in this world, as a list: which one is selected, how far each is built, and for each a button to
 * hide or show it, one to edit it, one to remove it; Undo and Redo underneath. It is where the things that once had their
 * own wheel segments live, reached from the Library. Removing asks first (and can be undone).
 */
public final class PlacedScreen extends Screen {
    private static final int ROW_H = 24, BTN = 18;

    private final long openedNs = System.nanoTime();
    private String down = "";
    private float scroll, scrollTarget;

    public PlacedScreen() {
        super(Component.literal("Placed"));
    }

    @Override
    protected void init() {
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

    private int pw() {
        return Math.min(width - 24, 330);
    }

    private int ph() {
        return Math.min(height - 24, 52 + Math.max(2, Math.min(7, Placements.all().size())) * (ROW_H + 3) + 34);
    }

    private int px() {
        return (width - pw()) / 2;
    }

    private int py() {
        return (height - ph()) / 2;
    }

    private int listTop() {
        return py() + 28;
    }

    private int listBottom() {
        return py() + ph() - 32;
    }

    private int rowY(int i) {
        return listTop() + i * (ROW_H + 3) - Math.round(scroll);
    }

    private int eyeX() {
        return px() + pw() - 10 - 3 * BTN - 4;
    }

    private int editX() {
        return eyeX() + BTN + 2;
    }

    private int trashX() {
        return editX() + BTN + 2;
    }

    private int footY() {
        return py() + ph() - 26;
    }

    private float maxScroll() {
        return Math.max(0, Placements.all().size() * (ROW_H + 3) - (listBottom() - listTop()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int rawX, int rawY, float partial) {
        Motion.frame();
        int mx = Ui.mx(rawX), my = Ui.my(rawY);
        double open = Motion.reduced() ? 1 : Math.min(1, (System.nanoTime() - openedNs) / 1e9 / 0.2);
        int px = px(), py = py(), pw = pw(), ph = ph();
        float inner = Ui.panelOpening(g, px, py, pw, ph, open);
        if (inner < 0.05f) return;
        double t = System.nanoTime() / 1e9;
        Ui.text(g, "Placed blueprints", px + 10, py + 8, Ui.withAlpha(Ui.LINE, inner));
        List<Placement> all = Placements.all();
        Ui.right(g, all.size() + (all.size() == 1 ? " placed" : " placed"), px + pw - 10, py + 8, Ui.withAlpha(Ui.DIM, inner));
        scroll = Motion.follow("pl#scroll", Math.max(0, Math.min(maxScroll(), scrollTarget)), 0.06);
        g.enableScissor(px + 6, listTop(), px + pw - 6, listBottom());
        String tip = null;
        for (int i = 0; i < all.size(); i++) {
            Placement p = all.get(i);
            int y = rowY(i);
            if (y + ROW_H < listTop() || y > listBottom()) continue;
            boolean active = p == Placements.active();
            Ui.inset(g, px + 8, y, pw - 16, ROW_H);
            g.fill(px + 11, y + 4, px + 14, y + ROW_H - 4, Ui.withAlpha(p.accent & 0xFFFFFF, inner * (p.visible ? 1f : 0.35f)));
            int textW = eyeX() - (px + 20) - 4;
            Ui.text(g, Ui.fit(p.name, textW), px + 19, y + 4, Ui.withAlpha(p.visible ? Ui.WHITE : Ui.DIM, inner));
            Verifier v = GhostRenderer.verifierOf(p);
            String info = p.sizeX() + " x " + p.sizeY() + " x " + p.sizeZ();
            if (v != null) {
                info += switch (v.phase()) {
                    case CHECKING -> "   checking...";
                    case EMPTY -> "";
                    case DONE -> "   done";
                    case BUILDING -> "   " + Math.round(v.progress() * 100) + "%";
                };
            } else if (p.status == Placement.Status.MISSING) {
                info = "file missing";
            } else if (p.status == Placement.Status.FAILED) {
                info = "cannot read it";
            }
            Ui.text(g, Ui.fit(info, textW), px + 19, y + 14, Ui.withAlpha(p.status == Placement.Status.READY ? Ui.DIM : Ui.WARN, inner));
            if (active) Ui.marching(g, px + 7, y - 1, pw - 14, ROW_H + 2, Ui.withAlpha(Ui.CYAN, inner * 0.8f), t);
            int by = y + (ROW_H - BTN) / 2;
            if (Ui.button(g, "pl#eye" + i, eyeX(), by, BTN, BTN, "", p.visible ? "eye" : "eye_off", mx, my, down.equals("eye" + i), true)) tip = p.visible ? "Hide it" : "Show it";
            boolean editable = p.ready() && p.locked;
            if (Ui.button(g, "pl#edit" + i, editX(), by, BTN, BTN, "", "move", mx, my, down.equals("edit" + i), editable)) tip = "Move, turn and flip it";
            if (Ui.button(g, "pl#trash" + i, trashX(), by, BTN, BTN, "", "trash", mx, my, down.equals("trash" + i), true)) tip = "Remove it";
        }
        g.disableScissor();
        if (all.isEmpty()) {
            Ui.centered(g, "Nothing is placed yet.", px + pw / 2, listTop() + 14, Ui.withAlpha(Ui.LINE, inner));
            Ui.centered(g, "Open the Library and click a blueprint.", px + pw / 2, listTop() + 26, Ui.withAlpha(Ui.DIM, inner));
        }
        int fy = footY();
        Ui.button(g, "pl#undo", px + 10, fy, 62, 16, "Undo", "undo", mx, my, down.equals("undo"), Placements.canUndo());
        Ui.button(g, "pl#redo", px + 76, fy, 62, 16, "Redo", "redo", mx, my, down.equals("redo"), Placements.canRedo());
        Ui.button(g, "pl#done", px + pw - 10 - 52, fy, 52, 16, "Done", null, mx, my, down.equals("done"), true);
        if (tip != null) Ui.tooltip(g, mx, my, tip);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        int px = px(), pw = pw(), fy = footY();
        if (Ui.inside(mx, my, px + pw - 62, fy, 52, 16)) return press("done");
        if (Placements.canUndo() && Ui.inside(mx, my, px + 10, fy, 62, 16)) return press("undo");
        if (Placements.canRedo() && Ui.inside(mx, my, px + 76, fy, 62, 16)) return press("redo");
        if (my >= listTop() && my < listBottom()) {
            List<Placement> all = Placements.all();
            for (int i = 0; i < all.size(); i++) {
                int y = rowY(i), by = y + (ROW_H - BTN) / 2;
                if (Ui.inside(mx, my, eyeX(), by, BTN, BTN)) return press("eye" + i);
                if (all.get(i).ready() && all.get(i).locked && Ui.inside(mx, my, editX(), by, BTN, BTN)) return press("edit" + i);
                if (Ui.inside(mx, my, trashX(), by, BTN, BTN)) return press("trash" + i);
                if (Ui.inside(mx, my, px + 8, y, pw - 16, ROW_H)) {
                    Placements.select(all.get(i));
                    Sfx.play(Sfx.PRESS, 1.2f);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean press(String what) {
        down = what;
        Sfx.play(Sfx.PRESS);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mx = (int) event.x(), my = (int) event.y();
        String was = down;
        down = "";
        if (was.isEmpty()) return super.mouseReleased(event);
        int px = px(), pw = pw(), fy = footY();
        List<Placement> all = Placements.all();
        switch (was) {
            case "done" -> {
                if (Ui.inside(mx, my, px + pw - 62, fy, 52, 16)) {
                    Sfx.play(Sfx.CLOSE);
                    onClose();
                }
            }
            case "undo" -> {
                if (Ui.inside(mx, my, px + 10, fy, 62, 16)) Interaction.undo(minecraft);
            }
            case "redo" -> {
                if (Ui.inside(mx, my, px + 76, fy, 62, 16)) Interaction.redo(minecraft);
            }
            default -> {
                for (int i = 0; i < all.size(); i++) {
                    int by = rowY(i) + (ROW_H - BTN) / 2;
                    Placement p = all.get(i);
                    if (was.equals("eye" + i) && Ui.inside(mx, my, eyeX(), by, BTN, BTN)) {
                        p.visible = !p.visible;
                        PlacementStore.markDirty();
                        Sfx.play(p.visible ? Sfx.OPEN : Sfx.CLOSE);
                    } else if (was.equals("edit" + i) && Ui.inside(mx, my, editX(), by, BTN, BTN)) {
                        Placements.select(p);
                        onClose();
                        Interaction.enterEdit(minecraft);
                    } else if (was.equals("trash" + i) && Ui.inside(mx, my, trashX(), by, BTN, BTN)) {
                        Placements.removeUndoable(p);
                        Sfx.play(Sfx.CLOSE, 0.8f);
                        Interaction.say(minecraft, "Removed " + p.name + ". Undo brings it back.");
                        break;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) scrollY * 20));
        return true;
    }

    // ---- for the demo

    /** Dev demo: the middle of a control: "eye:0", "edit:0", "trash:0", "row:0", "undo", "redo", "done". */
    public int[] anchor(String id) {
        int fy = footY();
        switch (id) {
            case "undo":
                return new int[]{px() + 41, fy + 8};
            case "redo":
                return new int[]{px() + 107, fy + 8};
            case "done":
                return new int[]{px() + pw() - 36, fy + 8};
            default:
                break;
        }
        String[] p = id.split(":");
        if (p.length != 2) return null;
        int i = Integer.parseInt(p[1]), y = rowY(i) + ROW_H / 2;
        return switch (p[0]) {
            case "eye" -> new int[]{eyeX() + BTN / 2, y};
            case "edit" -> new int[]{editX() + BTN / 2, y};
            case "trash" -> new int[]{trashX() + BTN / 2, y};
            case "row" -> new int[]{px() + 40, y};
            default -> null;
        };
    }
}
