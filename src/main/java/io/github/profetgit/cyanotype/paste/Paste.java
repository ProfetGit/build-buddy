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
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Creative paste: the whole placed blueprint goes into the world at once instead of being built block by block. It works where
 * the game lets the player do that without a command: creative mode in a world the player hosts (singleplayer, or a LAN world
 * they opened), where the client reaches the server in the same process. On somebody else's server it is not offered, and the
 * panel says why. The work is a few milliseconds of the server's time per tick ({@link PasteJob}); this class decides when it
 * is allowed, hands the job its slices, shows how it is going and takes part in Ctrl+Z.
 */
public final class Paste {
    /** The server thread's share per tick, so a big paste never stalls the world. */
    private static final long SLICE_NS = 8_000_000L;

    private static PasteJob job;
    private static @Nullable Placement target;
    private static boolean inFlight, wasDone;
    private static long finishedNs;

    private Paste() {
    }

    /** Why a paste is not possible right now, in words for the player, or empty when it is. */
    public static String unavailable(Minecraft mc) {
        if (mc.level == null || mc.player == null) return "Not in a world.";
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return "Pasting works in a world you host: singleplayer, or a LAN world you opened.";
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

    public static @Nullable PasteJob job() {
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
        MinecraftServer server = mc.getSingleplayerServer();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(p.dimension)));
        if (level == null) {
            Interaction.say(mc, "That dimension is not loaded.");
            Sfx.play(Sfx.ERROR);
            return false;
        }
        List<PasteJob.Part> parts = new ArrayList<>();
        for (Verifier.Part part : v.parts) parts.add(PasteJob.Part.of(part.region, part.wx, part.wy, part.wz));
        job = new PasteJob(level, parts);
        target = p;
        inFlight = false;
        wasDone = false;
        final PasteJob made = job;
        Placements.rememberPaste(p, () -> undo(mc, made));
        Sfx.play(Sfx.PRESS, 1.2f);
        Interaction.say(mc, "Pasting " + String.format(Locale.ROOT, "%,d", job.total()) + " blocks...");
        return true;
    }

    /** Undoes a paste (what went in comes out, what was overwritten comes back). Called by the undo history. */
    public static void undo(Minecraft mc, PasteJob j) {
        if (j == null) return;
        j.beginUndo();
        job = j;
        wasDone = false;
        Interaction.say(mc, "Undoing the paste...");
    }

    /** Once per client tick: hands the server a slice of work, and says what happened when it is done. */
    public static void tick(Minecraft mc) {
        PasteJob j = job;
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
        if (s == PasteJob.State.RUNNING && !mc.player.isCreative()) j.stop("Stopped: you are not in creative mode any more.");
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        inFlight = true;
        server.execute(() -> {
            try {
                j.work(SLICE_NS);
            } finally {
                inFlight = false;
            }
        });
    }

    private static void report(Minecraft mc, PasteJob j, PasteJob.State s) {
        if (s == PasteJob.State.UNDONE) {
            Sfx.play(Sfx.CLOSE, 1.1f);
            Interaction.say(mc, "Undone: " + String.format(Locale.ROOT, "%,d", j.undoable()) + " blocks put back.");
            return;
        }
        Sfx.play(j.stopped().isEmpty() ? Sfx.COMPLETE : Sfx.ERROR);
        String text = "Pasted " + String.format(Locale.ROOT, "%,d", j.placed()) + " blocks"
            + (j.same() > 0 ? " (" + String.format(Locale.ROOT, "%,d", j.same()) + " were already right)" : "")
            + (j.unloaded() > 0 ? ". " + String.format(Locale.ROOT, "%,d", j.unloaded()) + " were in chunks that are not loaded: walk closer and paste again" : "")
            + (j.stopped().isEmpty() ? "." : ". " + j.stopped()) + " Ctrl+Z undoes it.";
        Interaction.say(mc, text);
    }

    /** 0..1 while a paste or an undo runs, else -1 (for the progress panel). */
    public static double progress() {
        PasteJob j = job;
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
        inFlight = false;
        wasDone = false;
    }
}
