package io.github.profetgit.cyanotype.paste;

import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.Sfx;
import io.github.profetgit.cyanotype.verify.Verifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Creative paste: the whole placed blueprint goes into the world at once instead of being built block by block. In a world the
 * player hosts (singleplayer, or a LAN world they opened) the client reaches the server in the same process and {@link PasteJob}
 * places the blocks on the server thread. On another server an operator in creative mode gets {@link CommandPasteJob}, which
 * sends {@code /fill} and {@code /setblock} commands a few per tick (operators are exempt from the command-spam kick). This
 * class decides when it is allowed, hands the job its turn, shows how it is going and takes part in Ctrl+Z.
 */
public final class Paste {
    /** The server thread's share per tick, so a big paste never stalls the world. */
    private static final long SLICE_NS = 8_000_000L;

    /** The most cells of work a tick may do when the game is running smoothly, and the least when it is struggling. */
    private static final int MAX_PER_TICK = 6000, MIN_PER_TICK = 150;
    /** Commands a tick on a server when the game is smooth; {@link CommandPasteJob} lowers it further when the server is slow to show the blocks. */
    private static final double COMMANDS_PER_TICK = 20;
    /** How long frames have been taking, the worst of the last few (ms), and the share of {@link #MAX_PER_TICK} the paste allows itself. */
    private static final double[] RECENT_FRAMES = new double[8];
    private static int frameAt;
    private static long lastFrameEndNs;
    private static double pace = 1.0;
    private static volatile int blocksPerTick = MAX_PER_TICK;

    /** Dev only: pretend to be on a server (true: as an operator, false: not one), so the command path can be tried in singleplayer. */
    public static volatile Boolean testServerOps;

    private static PasteRun job;
    /** The last result message, for the dev checks. */
    public static String lastReport = "";
    private static @Nullable Placement target;
    private static boolean inFlight, wasDone;
    private static long finishedNs;
    /** A placement to paste as soon as the world has been looked at (the key was pressed before the check finished), and since when. */
    private static @Nullable Placement queued;
    private static long queuedNs;
    /** The ghost taken away because its build was pasted: it comes back with the undo. */
    private static @Nullable Placement removedForPaste;

    private Paste() {
    }

    /** Whether pasting goes through commands (a server the player is an operator on) instead of the server thread of a world they host. */
    public static boolean commandMode(Minecraft mc) {
        return testServerOps != null || mc.getSingleplayerServer() == null;
    }

    private static boolean isOp(Minecraft mc) {
        Boolean t = testServerOps;
        return t != null ? t : mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** Why a paste is not possible right now, in words for the player, or empty when it is. */
    public static String unavailable(Minecraft mc) {
        if (mc.level == null || mc.player == null) return "Not in a world.";
        if (commandMode(mc)) {
            if (!isOp(mc)) return "Pasting on a server needs operator permission.";
            if (!mc.player.isCreative()) return "Switch to creative mode to paste.";
            return "";
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (!mc.player.isCreative()) return "Switch to creative mode to paste.";
        if (!server.isSingleplayerOwner(mc.player.nameAndId())) return "Only the host of the world can paste.";
        return "";
    }

    /** Whether the blueprint in the world right now (a locked, baked placement) can be pasted. */
    public static boolean ready(Placement p) {
        return p != null && p.locked && p.ready() && GhostRenderer.verifierOf(p) != null && GhostRenderer.verifierOf(p).scanned();
    }

    public static boolean busy() {
        return job != null && job.state() != PasteJob.State.DONE && job.state() != PasteJob.State.UNDONE;
    }

    /** When the last op paste (or its undo) was still sending commands: their answers keep arriving a moment after. */
    private static long commandsSeenNs;

    /** Whether a chat line is the server's answer to one of an op paste's own /fill or /setblock commands (shown to nobody but the log). */
    public static boolean isOwnCommandFeedback(net.minecraft.network.chat.Component message) {
        if (!(job instanceof CommandPasteJob)) return false;
        if (!busy() && System.nanoTime() - commandsSeenNs > 10_000_000_000L) return false;
        if (!(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t)) return false;
        String key = t.getKey();
        return key.startsWith("commands.setblock.") || key.startsWith("commands.fill.");
    }

    public static @Nullable PasteRun job() {
        return job;
    }

    /** What a paste would do, for the confirmation: {blocks that go in, blocks of the world that are in the way, blocks already right}. */
    public static long[] preview(Placement p) {
        Verifier v = GhostRenderer.verifierOf(p);
        if (v == null) return new long[]{0, 0, 0};
        var c = v.counts();
        return new long[]{c.todo(), c.wrong(), c.correct()};
    }

    /** Starts pasting a placement. @return false (with a message) when it cannot be done */
    public static boolean start(Minecraft mc, Placement p) {
        String why = unavailable(mc);
        if (!why.isEmpty()) {
            Interaction.say(mc, why);
            Sfx.play(Sfx.ERROR);
            return false;
        }
        if (busy()) {
            Interaction.say(mc, "A paste is already running.");
            Sfx.play(Sfx.ERROR);
            return false;
        }
        Verifier v = GhostRenderer.verifierOf(p);
        if (!ready(p) || v == null) {
            Interaction.say(mc, "Place a blueprint first (and lock it).");
            Sfx.play(Sfx.ERROR);
            return false;
        }
        boolean commands = commandMode(mc);
        ServerLevel level = null;
        if (commands) {
            if (!mc.level.dimension().identifier().equals(Identifier.parse(p.dimension))) {
                Interaction.say(mc, "That dimension is not the one you are in.");
                Sfx.play(Sfx.ERROR);
                return false;
            }
        } else {
            level = mc.getSingleplayerServer().getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(p.dimension)));
            if (level == null) {
                Interaction.say(mc, "That dimension is not loaded.");
                Sfx.play(Sfx.ERROR);
                return false;
            }
        }
        // the undo log lives in memory: a paste that would not leave room for it (and for the game) is refused rather than risking a crash
        long need = PasteJob.undoBytes(preview(p)[0] + preview(p)[1]);
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (need > free / 3) {
            Interaction.say(mc, "This build is too big to paste with this much memory (it needs about " + need / 1048576 + " MB to be undoable, " + free / 1048576 + " MB are free). Give Minecraft more memory, or paste a smaller part.");
            Sfx.play(Sfx.ERROR);
            return false;
        }
        pace = 1.0;
        blocksPerTick = MAX_PER_TICK;
        java.util.Arrays.fill(RECENT_FRAMES, 0);
        List<PasteJob.Part> parts = new ArrayList<>();
        for (Verifier.Part part : v.parts) parts.add(PasteJob.Part.of(part.region, part.wx, part.wy, part.wz));
        job = commands ? new CommandPasteJob(mc.level, parts) : new PasteJob(level, parts);
        target = p;
        inFlight = false;
        wasDone = false;
        final PasteRun made = job;
        Placements.rememberPaste(p, () -> undo(mc, made));
        // the ghost steps aside while the build goes in: it would compare and re-draw every block the paste changes, on top of what the game itself does
        Placements.removeAfterPaste(p);
        removedForPaste = p;
        Sfx.play(Sfx.PRESS, 1.2f);
        Interaction.say(mc, "Pasting " + String.format(Locale.ROOT, "%,d", job.total()) + " blocks...");
        return true;
    }

    /** Pastes a placement now, or as soon as the world has been looked at when that is still going on. No question: Ctrl+Z undoes it. */
    public static void pasteWhenReady(Minecraft mc, Placement p) {
        String why = unavailable(mc);
        if (!why.isEmpty()) {
            Interaction.say(mc, why);
            Sfx.play(Sfx.ERROR);
            return;
        }
        if (busy()) {
            Interaction.say(mc, "A paste is already running.");
            Sfx.play(Sfx.ERROR);
            return;
        }
        if (ready(p)) {
            queued = null;
            start(mc, p);
            return;
        }
        queued = p;
        queuedNs = System.nanoTime();
        Sfx.play(Sfx.PRESS, 1.2f);
        Interaction.say(mc, "Checking the world, then pasting...");
    }

    /** Undoes a paste (what went in comes out, what was overwritten comes back). Called by the undo history. */
    public static void undo(Minecraft mc, PasteRun j) {
        if (j == null) return;
        j.beginUndo();
        job = j;
        wasDone = false;
        Interaction.say(mc, "Undoing the paste...");
    }

    /** Called at the end of every frame: remembers how long it took while a paste is running, so the paste can slow down when the game struggles. */
    public static void frameEnded() {
        long now = System.nanoTime();
        if (lastFrameEndNs != 0 && busy()) {
            RECENT_FRAMES[frameAt++ & 7] = (now - lastFrameEndNs) / 1e6;
        }
        lastFrameEndNs = now;
    }

    /**
     * Adjusts how much the paste does per tick to how the last frames went: frames over about 45 ms mean the client cannot keep up
     * with the sections it is told to redraw, so the paste does less per tick (it takes longer, the game stays playable); smooth
     * frames let it speed up again. Never to zero, so it always finishes.
     */
    static void adapt() {
        double worst = 0;
        for (double f : RECENT_FRAMES) worst = Math.max(worst, f);
        if (worst > 45) pace = Math.max(MIN_PER_TICK / (double) MAX_PER_TICK, pace * 0.6);
        else if (worst > 33) pace = Math.max(MIN_PER_TICK / (double) MAX_PER_TICK, pace * 0.85);
        else if (worst < 24) pace = Math.min(1.0, pace * 1.15 + 0.02);
        blocksPerTick = Math.max(MIN_PER_TICK, (int) (MAX_PER_TICK * pace));
    }

    /** Dev checks: how many cells a tick may do right now. */
    public static int blocksPerTick() {
        return blocksPerTick;
    }

    /** Once per client tick: hands the server a slice of work, and says what happened when it is done. */
    public static void tick(Minecraft mc) {
        if (job instanceof CommandPasteJob && busy()) commandsSeenNs = System.nanoTime();
        Placement q = queued;
        if (q != null && mc.level != null) {
            if (!Placements.all().contains(q)) {
                queued = null;
            } else if (ready(q) && !busy()) {
                queued = null;
                start(mc, q);
            } else if (System.nanoTime() - queuedNs > 30_000_000_000L) {
                queued = null;
                Interaction.say(mc, "Could not paste: the world is still being checked. Try again.");
                Sfx.play(Sfx.ERROR);
            }
        }
        PasteRun j = job;
        if (j == null || mc.level == null || mc.player == null) return;
        PasteJob.State s = j.state();
        boolean finished = s == PasteJob.State.DONE && !j.undoPending() || s == PasteJob.State.UNDONE;
        if (finished && !inFlight) {
            if (!wasDone) {
                wasDone = true;
                finishedNs = System.nanoTime();
                report(mc, j, s);
            }
            return;
        }
        if (inFlight) return;
        adapt();
        if (s == PasteJob.State.RUNNING && !mc.player.isCreative()) j.stop("Stopped: you are not in creative mode any more.");
        if (j instanceof CommandPasteJob cj) {
            cj.tick(mc, COMMANDS_PER_TICK * pace);
            return;
        }
        PasteJob local = (PasteJob) j;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        inFlight = true;
        server.execute(() -> {
            try {
                local.work(SLICE_NS, blocksPerTick);
            } finally {
                inFlight = false;
            }
        });
    }

    private static void report(Minecraft mc, PasteRun j, PasteJob.State s) {
        if (s == PasteJob.State.UNDONE) {
            // undoing a paste brings back the ghost that went when it was pasted
            Placement back = removedForPaste;
            removedForPaste = null;
            if (back != null) Placements.putBack(back);
            Sfx.play(Sfx.CLOSE, 1.1f);
            String undone = "Undone: " + String.format(Locale.ROOT, "%,d", j.undoable()) + " blocks put back."
                + (j instanceof CommandPasteJob cj && cj.lostContainers() > 0 ? " Items that were inside chests and other containers are not restored." : "");
            lastReport = undone;
            Interaction.say(mc, undone);
            return;
        }
        Sfx.play(j.stopped().isEmpty() ? Sfx.COMPLETE : Sfx.ERROR);
        // the build is in the world and the ghost has done its job (it went when the paste started); it comes back when some of the build could
        // not go in, so it can be pasted again or built by hand
        if (!j.stopped().isEmpty() || j.unloaded() > 0) {
            Placement back = removedForPaste;
            removedForPaste = null;
            if (back != null) Placements.putBack(back);
        }
        String text = "Pasted " + String.format(Locale.ROOT, "%,d", j.placed()) + " blocks"
            + (j.same() > 0 ? " (" + String.format(Locale.ROOT, "%,d", j.same()) + " were already right)" : "")
            + (j.unloaded() > 0 ? ". " + String.format(Locale.ROOT, "%,d", j.unloaded()) + " were in chunks that are not loaded: walk closer and paste again" : "")
            + (j.stopped().isEmpty() ? "." : ". " + j.stopped()) + " Ctrl+Z undoes it.";
        lastReport = text;
        Interaction.say(mc, text);
    }

    /** 0..1 while a paste or an undo runs, else -1 (for the progress panel). */
    public static double progress() {
        PasteRun j = job;
        if (j == null) return -1;
        PasteJob.State s = j.state();
        if (s == PasteJob.State.DONE || s == PasteJob.State.UNDONE) return System.nanoTime() - finishedNs < 1_500_000_000L && wasDone ? 1.0 : -1;
        return j.progress();
    }

    public static boolean undoing() {
        return job != null && (job.state() == PasteJob.State.UNDOING || job.state() == PasteJob.State.UNDONE);
    }

    /** Forgets everything (the world changed). */
    public static void reset() {
        if (job != null && job.state() == PasteJob.State.RUNNING) job.stop("");
        job = null;
        target = null;
        queued = null;
        removedForPaste = null;
        inFlight = false;
        wasDone = false;
    }
}
