package io.github.profetgit.cyanotype.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.cyanotype.auto.AutoBuilder;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The warning before auto-placing is switched on for a multiplayer server (PRD 7.8). "Keep it off" has the focus, so Enter
 * and Esc say no; "Enable on this server" is dead for three seconds, so it cannot be clicked away unread. "Don't ask again
 * for this server" makes the answer stick, either way.
 */
public final class AutoDisclaimerScreen extends Screen {
    /** The warning, word for word as the design has it. */
    public static final String WARNING = "Auto-placing may break this server's rules and can get you banned. Cyanotype can't know what this server allows. "
        + "Check the rules or ask the staff first. You're responsible for how you use it.";
    /** How long the enabling button stays dead. */
    public static final long WAIT_MS = 3000;

    private final String server;
    private final AutoBuilder.Mode wanted;
    private final long openedNs = System.nanoTime();
    private int focus;
    private boolean dontAsk;
    private String down = "";
    /** Dev demo only: a clock for the wait (null = the real one). */
    public static volatile Long testWaitedMs;

    public AutoDisclaimerScreen(String server, AutoBuilder.Mode wanted) {
        super(Component.literal("Auto-placing on a server"));
        this.server = server;
        this.wanted = wanted;
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
        g.fillGradient(0, 0, width, height, 0x40000000, 0x80000000);
    }

    private int pw() {
        return Math.min(width - 24, 292);
    }

    private int ph() {
        return 148;
    }

    private int px() {
        return (width - pw()) / 2;
    }

    private int py() {
        return (height - ph()) / 2;
    }

    private int keepX() {
        return px() + 10;
    }

    private int enableX() {
        return px() + pw() - 10 - 134;
    }

    private int buttonY() {
        return py() + ph() - 26;
    }

    private int checkY() {
        return py() + ph() - 46;
    }

    /** Milliseconds since the screen opened. */
    public long waited() {
        Long t = testWaitedMs;
        return t != null ? t : (System.nanoTime() - openedNs) / 1_000_000L;
    }

    public boolean enableReady() {
        return waited() >= WAIT_MS;
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
        Ui.text(g, "Auto-placing on " + Ui.fit(server, pw - 130), px + 10, py + 8, Ui.withAlpha(Ui.WARN, inner));
        int y = py + 24;
        for (String line : Ui.wrap(WARNING, pw - 20, 6)) {
            Ui.text(g, line, px + 10, y, Ui.withAlpha(Ui.LINE, inner));
            y += 10;
        }
        Ui.checkbox(g, "ad#ask", px + 10, checkY(), dontAsk, Ui.inside(mx, my, px + 10, checkY(), pw - 20, 11));
        Ui.text(g, "Don't ask again for this server", px + 26, checkY() + 1, Ui.withAlpha(Ui.DIM, inner));
        Ui.button(g, "ad#keep", keepX(), buttonY(), 110, 16, "Keep it off", null, mx, my, down.equals("keep"), true);
        boolean ready = enableReady();
        long left = Math.max(0, (WAIT_MS - waited() + 999) / 1000);
        Ui.button(g, "ad#enable", enableX(), buttonY(), 134, 16, ready ? "Enable on this server" : "Enable on this server (" + left + ")", null, mx, my, down.equals("enable"), ready);
        int fx = switch (focus) {
            case 0 -> keepX() - 2;
            case 1 -> enableX() - 2;
            default -> px + 8;
        };
        int fy = focus == 2 ? checkY() - 2 : buttonY() - 2;
        int fw = focus == 0 ? 114 : focus == 1 ? 138 : pw - 16;
        Ui.marching(g, fx, fy, fw, focus == 2 ? 15 : 20, Ui.withAlpha(Ui.CYAN, inner), t);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        if (Ui.inside(mx, my, px() + 10, checkY(), pw() - 20, 11)) {
            dontAsk = !dontAsk;
            focus = 2;
            Sfx.play(Sfx.PRESS, 1.1f);
            return true;
        }
        if (Ui.inside(mx, my, keepX(), buttonY(), 110, 16)) {
            down = "keep";
            focus = 0;
            Sfx.play(Sfx.PRESS);
            return true;
        }
        if (Ui.inside(mx, my, enableX(), buttonY(), 134, 16)) {
            if (enableReady()) {
                down = "enable";
                focus = 1;
                Sfx.play(Sfx.PRESS);
            } else {
                Sfx.play(Sfx.ERROR, 0.8f);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mx = (int) event.x(), my = (int) event.y();
        String was = down;
        down = "";
        if (was.equals("keep") && Ui.inside(mx, my, keepX(), buttonY(), 110, 16)) {
            answer(false);
            return true;
        }
        if (was.equals("enable") && Ui.inside(mx, my, enableX(), buttonY(), 134, 16) && enableReady()) {
            answer(true);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            answer(false);
            return true;
        }
        if (event.isLeft() || event.input() == InputConstants.KEY_UP) {
            focus = (focus + 2) % 3;
            Sfx.play(Sfx.PRESS, 1.1f);
            return true;
        }
        if (event.isRight() || event.input() == InputConstants.KEY_TAB || event.input() == InputConstants.KEY_DOWN) {
            focus = (focus + 1) % 3;
            Sfx.play(Sfx.PRESS, 1.1f);
            return true;
        }
        if (event.isConfirmation() || event.input() == InputConstants.KEY_SPACE) {
            switch (focus) {
                case 0 -> answer(false);
                case 1 -> {
                    if (enableReady()) answer(true);
                    else Sfx.play(Sfx.ERROR, 0.8f);
                }
                default -> {
                    dontAsk = !dontAsk;
                    Sfx.play(Sfx.PRESS, 1.1f);
                }
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void answer(boolean enable) {
        minecraft.gui.setScreen(null);
        AutoBuilder.answered(minecraft, server, wanted, enable, dontAsk);
    }

    // ---- for the demo

    public int[] anchor(String which) {
        return switch (which) {
            case "keep" -> new int[]{keepX() + 55, buttonY() + 8};
            case "enable" -> new int[]{enableX() + 67, buttonY() + 8};
            default -> new int[]{px() + 14, checkY() + 5};
        };
    }

    public boolean dontAsk() {
        return dontAsk;
    }
}
