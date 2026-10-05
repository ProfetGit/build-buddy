package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Asks before a placed blueprint is taken away: the ghost goes, the file stays in the library. "Keep it" has the focus,
 * so Enter or Esc never removes anything; the arrow keys and Tab move the focus.
 */
public final class RemoveScreen extends Screen {
    private final Placement target;
    private final long openedNs = System.nanoTime();
    private int focus;
    private String down = "";

    public RemoveScreen(Placement target) {
        super(Component.literal("Remove"));
        this.target = target;
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
        return Math.min(width - 24, 244);
    }

    private int ph() {
        return 86;
    }

    private int px() {
        return (width - pw()) / 2;
    }

    private int py() {
        return (height - ph()) / 2;
    }

    private int keepX() {
        return px() + pw() / 2 - 4 - 70;
    }

    private int removeX() {
        return px() + pw() / 2 + 4;
    }

    private int buttonY() {
        return py() + ph() - 26;
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
        Ui.centered(g, Ui.fit("Remove " + target.name + "?", pw - 24), px + pw / 2, py + 10, Ui.withAlpha(Ui.WHITE, inner));
        int y = py + 24;
        for (String line : Ui.wrap("The ghost goes away. The blueprint file stays in your library.", pw - 28, 2)) {
            Ui.centered(g, line, px + pw / 2, y, Ui.withAlpha(Ui.DIM, inner));
            y += 10;
        }
        Ui.button(g, "rm#keep", keepX(), buttonY(), 70, 16, "Keep it", null, mx, my, down.equals("keep"), true);
        Ui.button(g, "rm#remove", removeX(), buttonY(), 70, 16, "Remove", "trash", mx, my, down.equals("remove"), true);
        int fx = focus == 0 ? keepX() : removeX();
        Ui.marching(g, fx - 2, buttonY() - 2, 74, 20, Ui.withAlpha(Ui.CYAN, inner), t);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        if (Ui.inside(mx, my, keepX(), buttonY(), 70, 16)) {
            down = "keep";
            focus = 0;
            Sfx.play(Sfx.PRESS);
            return true;
        }
        if (Ui.inside(mx, my, removeX(), buttonY(), 70, 16)) {
            down = "remove";
            focus = 1;
            Sfx.play(Sfx.PRESS);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mx = (int) event.x(), my = (int) event.y();
        String was = down;
        down = "";
        if (was.equals("keep") && Ui.inside(mx, my, keepX(), buttonY(), 70, 16)) {
            keep();
            return true;
        }
        if (was.equals("remove") && Ui.inside(mx, my, removeX(), buttonY(), 70, 16)) {
            remove();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isLeft() || event.isRight() || event.input() == InputConstants.KEY_TAB) {
            focus = 1 - focus;
            Sfx.play(Sfx.PRESS, 1.1f);
            return true;
        }
        if (event.isConfirmation()) {
            if (focus == 0) keep();
            else remove();
            return true;
        }
        return super.keyPressed(event);
    }

    private void keep() {
        Sfx.play(Sfx.CLOSE);
        onClose();
    }

    private void remove() {
        String name = target.name;
        Placements.remove(target);
        Sfx.play(Sfx.CLOSE, 0.8f);
        onClose();
        Interaction.say(minecraft, "Removed " + name + ".");
    }

    /** Dev demo: the middle of a button on screen ("keep" or "remove"). */
    public int[] anchor(String which) {
        return new int[]{(which.equals("keep") ? keepX() : removeX()) + 35, buttonY() + 8};
    }

    /** Dev demo: the placement this asks about. */
    public List<Placement> asks() {
        return List.of(target);
    }
}
