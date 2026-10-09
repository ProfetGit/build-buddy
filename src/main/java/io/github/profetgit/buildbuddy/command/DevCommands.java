package io.github.profetgit.buildbuddy.command;

import io.github.profetgit.buildbuddy.BuildBuddy;
import io.github.profetgit.buildbuddy.blueprint.Blueprint;
import io.github.profetgit.buildbuddy.blueprint.LitematicWriter;
import io.github.profetgit.buildbuddy.demo.Samples;
import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.interaction.Interaction;
import io.github.profetgit.buildbuddy.placement.BlueprintLibrary;
import io.github.profetgit.buildbuddy.placement.Orientation;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.PlacementStore;
import io.github.profetgit.buildbuddy.placement.Placements;
import io.github.profetgit.buildbuddy.verify.Counts;
import io.github.profetgit.buildbuddy.verify.Verifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;

/**
 * Chat commands (typed as /buildbuddy ..., handled on the client and never sent to a server) for the things the mouse
 * tools do not cover yet: choosing a file, naming a placement, exact numbers. The Library and Materials panels (M4) will
 * replace the file and list commands.
 */
public final class DevCommands {
    private static Blueprint loaded;
    private static String loadedRef = "";
    private static String loadedName = "";
    private static boolean loading;
    private static final java.util.ArrayList<String> queued = new java.util.ArrayList<>();

    private DevCommands() {
    }

    /** The blueprint last loaded with /buildbuddy load (dev checks). */
    public static Blueprint loadedBlueprint() {
        return loaded;
    }

    public static boolean handles(String message) {
        String m = message.startsWith("/") ? message.substring(1) : message;
        return m.equals("buildbuddy") || m.startsWith("buildbuddy ");
    }

    public static void run(String message) {
        Minecraft mc = Minecraft.getInstance();
        String line = (message.startsWith("/") ? message.substring(1) : message).substring("buildbuddy".length()).trim();
        String[] parts = line.isEmpty() ? new String[0] : line.split("\\s+", 2);
        String cmd = parts.length == 0 ? "help" : parts[0].toLowerCase(Locale.ROOT);
        if (loading && !cmd.equals("load")) {
            queued.add(message);
            return;
        }
        String arg = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (cmd) {
                case "list" -> list();
                case "load" -> load(arg);
                case "sample" -> sample();
                case "place" -> place(mc, arg);
                case "placements", "ls" -> placements();
                case "select" -> select(arg);
                case "show" -> visibility(arg, true);
                case "hide" -> visibility(arg, false);
                case "remove" -> remove(arg);
                case "move" -> move(arg);
                case "rotate" -> rotate();
                case "mirror" -> mirror(arg);
                case "opacity" -> opacity(arg);
                case "status" -> status();
                case "layer" -> layer(arg);
                case "next" -> next();
                case "verify" -> verify(arg);
                case "clear" -> clear();
                case "info" -> info();
                case "ponder" -> ponder(mc, arg);
                case "auto" -> auto(mc, arg);
                case "samples" -> samples();
                case "library" -> mc.gui.setScreen(new io.github.profetgit.buildbuddy.ui.LibraryScreen());
                case "paste" -> paste(mc);
                case "materials" -> mc.gui.setScreen(new io.github.profetgit.buildbuddy.ui.MaterialsScreen());
                case "savename" -> {
                    if (mc.gui.screen() instanceof io.github.profetgit.buildbuddy.ui.SaveScreen ss) ss.setName(arg);
                }
                default -> help();
            }
        } catch (RuntimeException e) {
            BuildBuddy.LOG.error("Build Buddy command failed: {}", line, e);
            say("Something went wrong: " + e.getMessage());
        }
    }

    /** Dev: /buildbuddy ponder record <id> [x y z sx sy sz] | mark <name> | stop. See PonderRecorder. */
    private static void ponder(Minecraft mc, String arg) {
        if (!io.github.profetgit.buildbuddy.demo.Director.ACTIVE && !Boolean.getBoolean("buildbuddy.dev")) {
            say("The lesson recorder is for development: start the game with -Dbuildbuddy.dev=true.");
            return;
        }
        String[] a = arg.isEmpty() ? new String[0] : arg.split("\\s+");
        if (a.length == 0) {
            say("ponder record <id> [x y z sx sy sz] | mark <name> | stop");
            return;
        }
        switch (a[0]) {
            case "record" -> {
                if (a.length < 2) {
                    say("Which take? ponder record <id> [x y z sx sy sz]");
                    return;
                }
                int[] box = a.length >= 8 ? new int[]{Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])} : null;
                int[] sz = a.length >= 8 ? new int[]{Integer.parseInt(a[5]), Integer.parseInt(a[6]), Integer.parseInt(a[7])} : null;
                io.github.profetgit.buildbuddy.ponder.PonderRecorder.start(mc, a[1], box, sz);
                say("Recording " + a[1] + ". ponder mark <name> names a moment, ponder stop ends it.");
            }
            case "mark" -> {
                io.github.profetgit.buildbuddy.ponder.PonderRecorder.mark(a.length > 1 ? a[1] : "mark");
                say("Marked " + (a.length > 1 ? a[1] : "mark"));
            }
            case "stop" -> {
                var file = io.github.profetgit.buildbuddy.ponder.PonderRecorder.stop(mc);
                say(file == null ? "Nothing was being recorded." : "Recording written to " + file);
            }
            default -> say("ponder record <id> [x y z sx sy sz] | mark <name> | stop");
        }
    }

    private static void say(String text) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Build Buddy] " + text));
    }

    /** /buildbuddy auto assist | sweep | off: the same modes as the N key and the Build panel (scripted recordings use it). */
    private static void auto(Minecraft mc, String arg) {
        var mode = switch (arg.toLowerCase(Locale.ROOT)) {
            case "assist" -> io.github.profetgit.buildbuddy.auto.AutoBuilder.Mode.ASSIST;
            case "sweep" -> io.github.profetgit.buildbuddy.auto.AutoBuilder.Mode.SWEEP;
            case "off" -> io.github.profetgit.buildbuddy.auto.AutoBuilder.Mode.OFF;
            default -> null;
        };
        if (mode == null) say("auto assist | sweep | off");
        else io.github.profetgit.buildbuddy.auto.AutoBuilder.request(mc, mode);
    }

    /** /buildbuddy samples: a few dev builds into the library (scripted recordings). */
    private static void samples() {
        try {
            Files.createDirectories(BlueprintLibrary.ownDir());
            LitematicWriter.write(Samples.pyramid(), BlueprintLibrary.ownDir().resolve("little-pyramid.litematic"));
            LitematicWriter.write(Samples.bridge(), BlueprintLibrary.ownDir().resolve("arch-bridge.litematic"));
            LitematicWriter.write(Samples.tower(), BlueprintLibrary.ownDir().resolve("watch-tower.litematic"));
            LitematicWriter.write(Samples.house(), BlueprintLibrary.ownDir().resolve("sample-house.litematic"));
            say("Wrote four sample builds.");
        } catch (IOException e) {
            say("Could not write the samples: " + e.getMessage());
        }
    }

    /** /buildbuddy paste: pastes the active placement (creative, a world you host). */
    private static void paste(Minecraft mc) {
        Placement p = Placements.active();
        if (p == null) say("Nothing placed.");
        else io.github.profetgit.buildbuddy.paste.Paste.pasteWhenReady(mc, p);
    }

    private static void help() {
        say("Files: list | load <name> | sample | place [x y z]");
        say("Placements: placements | select <n or name> | show / hide / remove [n or name] | move x y z | rotate | mirror [x] | opacity 5-100 | info | clear");
        say("Building: status | layer <n> [m] / up / down / all | next | verify on or off");
        say("Keys: V edit, H show or hide, Z undo (see Controls). Files go in " + BlueprintLibrary.saveDir() + " (Litematica's schematics folder works too).");
    }

    private static void list() {
        List<Path> files = BlueprintLibrary.files();
        if (files.isEmpty()) {
            say("No schematic files (.litematic, .schem, .schematic) yet. Put some in " + BlueprintLibrary.saveDir() + ", or try /buildbuddy sample.");
            return;
        }
        for (Path p : files) say(p.getFileName().toString());
    }

    private static void load(String name) {
        if (name.isEmpty()) {
            say("Which file? Try /buildbuddy list.");
            return;
        }
        Path found = BlueprintLibrary.find(name);
        if (found == null) {
            say("No file called \"" + name + "\". /buildbuddy list shows what there is.");
            return;
        }
        say("Loading " + found.getFileName() + " ...");
        long t0 = System.nanoTime();
        loading = true;
        BlueprintLibrary.load(found, bp -> {
            loaded = bp;
            loadedRef = BlueprintLibrary.refOf(found);
            loadedName = bp.meta.name().isBlank() ? found.getFileName().toString().replaceFirst("(?i)\\.(litematic|schem|schematic)$", "") : bp.meta.name();
            say(loadedName + ": " + bp.sizeX + " x " + bp.sizeY + " x " + bp.sizeZ + ", " + bp.totalBlocks() + " blocks, " + bp.regions.size() + " region(s), read in " + (System.nanoTime() - t0) / 1_000_000 + " ms.");
            if (!bp.unknownBlocks().isEmpty()) say("Unknown blocks (shown red): " + String.join(", ", bp.unknownBlocks()));
            say("Now /buildbuddy place, then aim and click.");
            loading = false;
            for (String m : new java.util.ArrayList<>(queued)) run(m);
            queued.clear();
        }, why -> {
            loading = false;
            queued.clear();
            say("Could not open " + found.getFileName() + ": " + why);
        });
    }

    private static void sample() {
        try {
            Files.createDirectories(BlueprintLibrary.ownDir());
            Path file = BlueprintLibrary.ownDir().resolve("sample-house.litematic");
            LitematicWriter.write(Samples.house(), file);
            load("sample-house");
        } catch (IOException e) {
            say("Could not write the sample: " + e.getMessage());
        }
    }

    private static void place(Minecraft mc, String arg) {
        if (loaded == null) {
            say("Load something first: /buildbuddy list, then /buildbuddy load <name>.");
            return;
        }
        if (arg.isEmpty()) {
            Interaction.startPlacing(loadedName, loaded, loadedRef);
            return;
        }
        BlockPos at = parsePos(arg);
        if (at == null) {
            say("Use /buildbuddy place x y z, or no numbers to aim it with the mouse.");
            return;
        }
        Placement p = new Placement(loadedName, loaded, loadedRef, mc.level.dimension().identifier().toString(), at, Orientation.NONE);
        p.locked = true;
        p.opacity = io.github.profetgit.buildbuddy.ui.Settings.get().opacity;
        Placements.add(p);
        say("Placed " + p.name + " at " + at.toShortString() + ".");
    }

    private static BlockPos parsePos(String arg) {
        String[] p = arg.split("\\s+");
        if (p.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void placements() {
        if (Placements.all().isEmpty()) {
            say("Nothing placed. /buildbuddy load <name>, then /buildbuddy place.");
            return;
        }
        int n = 1;
        for (Placement p : Placements.all()) {
            String state = switch (p.status) {
                case LOADING -> "loading";
                case MISSING -> "FILE MISSING (" + p.ref + ")";
                case FAILED -> "cannot be read: " + p.problem;
                case READY -> p.visible ? (p.locked ? "placed" : "being placed") : "hidden";
            };
            say((p == Placements.active() ? "> " : "  ") + n++ + ". " + p.name + " at " + p.origin.toShortString() + " [" + p.dimension + "] " + state);
        }
    }

    private static Placement target(String arg) {
        Placement p = arg.isEmpty() ? Placements.active() : Placements.find(arg);
        if (p == null) say(arg.isEmpty() ? "No active placement. /buildbuddy placements lists them, /buildbuddy select picks one." : "No placement \"" + arg + "\".");
        return p;
    }

    private static void select(String arg) {
        Placement p = target(arg);
        if (p == null || arg.isEmpty()) return;
        Placements.select(p);
        say("Selected " + p.name + ". Press V to edit it.");
    }

    private static void visibility(String arg, boolean visible) {
        Placement p = target(arg);
        if (p == null) return;
        p.visible = visible;
        PlacementStore.markDirty();
        say(p.name + (visible ? " shown" : " hidden"));
    }

    private static void remove(String arg) {
        Placement p = target(arg);
        if (p == null) return;
        Placements.removeUndoable(p);
        say("Removed " + p.name + " (the file is untouched; Ctrl+Z brings it back).");
    }

    private static void move(String arg) {
        Placement p = target("");
        if (p == null) return;
        BlockPos at = parsePos(arg);
        if (at == null) {
            say("Use /buildbuddy move x y z.");
            return;
        }
        Placements.remember(p);
        p.set(at, p.orientation);
        PlacementStore.markDirty();
    }

    private static void rotate() {
        Placement p = target("");
        if (p == null) return;
        Placements.remember(p);
        Orientation o = p.orientation.rotated(Rotation.CLOCKWISE_90);
        int[] xz = io.github.profetgit.buildbuddy.interaction.Moves.keepCenter(p.origin.getX(), p.origin.getZ(), p.sizeX(), p.sizeZ(),
            o.sizeX(p.blueprint.sizeX, p.blueprint.sizeZ), o.sizeZ(p.blueprint.sizeX, p.blueprint.sizeZ));
        p.set(new BlockPos(xz[0], p.origin.getY(), xz[1]), o);
        PlacementStore.markDirty();
    }

    private static void mirror(String arg) {
        Placement p = target("");
        if (p == null) return;
        Placements.remember(p);
        p.set(p.origin, p.orientation.flipped(arg.equalsIgnoreCase("x") ? Direction.Axis.X : Direction.Axis.Z));
        PlacementStore.markDirty();
    }

    private static void opacity(String arg) {
        Placement p = target("");
        if (p == null) return;
        try {
            p.opacity = Math.max(0.05f, Math.min(1f, Integer.parseInt(arg) / 100f));
            PlacementStore.markDirty();
            say("Opacity " + Math.round(p.opacity * 100) + "%.");
        } catch (NumberFormatException e) {
            say("Use /buildbuddy opacity 5 to 100.");
        }
    }

    private static void status() {
        Placement p = target("");
        if (p == null) return;
        Verifier v = GhostRenderer.verifierOf(p);
        if (v == null) {
            say(p.locked ? "Not compared with the world yet (it is still being baked, or verifying is off)." : "Lock the placement first: the world is compared once it is placed.");
            return;
        }
        Counts c = v.counts();
        say(p.name + ": " + (v.scanned() ? Math.round(v.progress() * 100) + "% built. " : "still checking the world (" + Math.round(v.scanFraction() * 100) + "%). ") + c.correct() + " right, " + c.missing() + " missing, " + c.wrong() + " wrong"
            + (c.unknown() > 0 ? ", " + c.unknown() + " unknown blocks" : "") + (c.unloaded() > 0 ? ", " + c.unloaded() + " in chunks that are not loaded" : "") + (v.settled() ? "." : " (still checking)."));
        for (int l = 0; l < v.height; l++) {
            int todo = v.layerCount(l, Verifier.MISSING) + v.layerCount(l, Verifier.WRONG);
            if (todo > 0) {
                say("First unfinished layer: " + (l + 1) + " of " + v.height + " (" + todo + " to do).");
                break;
            }
        }
    }

    private static void layer(String arg) {
        Placement p = target("");
        if (p == null) return;
        int h = p.sizeY();
        String a = arg.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || a.equals("all")) {
            p.layerLo = p.layerHi = -1;
            say("All " + h + " layers shown.");
        } else if (a.equals("up") || a.equals("down")) {
            int cur = p.layerLo >= 0 && p.layerLo == p.layerHi ? p.layerLo : (a.equals("up") ? -1 : h);
            int next = Math.max(0, Math.min(h - 1, cur + (a.equals("up") ? 1 : -1)));
            p.layerLo = p.layerHi = next;
            say("Layer " + (next + 1) + " of " + h + ".");
        } else {
            String[] parts = a.split("\\s+");
            try {
                int lo = Integer.parseInt(parts[0]) - 1, hi = (parts.length > 1 ? Integer.parseInt(parts[1]) : Integer.parseInt(parts[0])) - 1;
                p.layerLo = Math.max(0, Math.min(lo, hi));
                p.layerHi = Math.min(h - 1, Math.max(lo, hi));
                say(p.layerLo == p.layerHi ? "Layer " + (p.layerLo + 1) + " of " + h + "." : "Layers " + (p.layerLo + 1) + " to " + (p.layerHi + 1) + " of " + h + ".");
            } catch (NumberFormatException e) {
                say("Use /buildbuddy layer 3, layer 3 5, layer up, layer down or layer all.");
                return;
            }
        }
        PlacementStore.markDirty();
    }

    private static void next() {
        Interaction.guide = !Interaction.guide;
        say(Interaction.guide ? "Showing the next block to build." : "Next-block guide off.");
    }

    private static void verify(String arg) {
        String a = arg.trim().toLowerCase(Locale.ROOT);
        GhostRenderer.verifyEnabled = a.isEmpty() ? !GhostRenderer.verifyEnabled : a.equals("on");
        say("Comparing with the world: " + (GhostRenderer.verifyEnabled ? "on" : "off (applies to ghosts built from now on)"));
    }

    private static void clear() {
        for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
        say("Cleared.");
    }

    private static void info() {
        Placement p = Placements.active();
        if (p == null) {
            say(loaded == null ? "Nothing loaded." : loadedName + " is loaded, not placed.");
            return;
        }
        say(p.name + ": " + (p.blueprint == null ? "?" : p.blueprint.totalBlocks()) + " blocks at " + p.origin.toShortString() + ", " + p.orientation.rotation().name().toLowerCase(Locale.ROOT)
            + (p.orientation.isMirrored() ? ", flipped" : "") + ", opacity " + Math.round(p.opacity * 100) + "%.");
        say("Ghost: " + GhostRenderer.Stats.drawn + " sections drawn of " + GhostRenderer.Stats.sections + ", " + GhostRenderer.Stats.quadsDrawn + " quads.");
    }
}
