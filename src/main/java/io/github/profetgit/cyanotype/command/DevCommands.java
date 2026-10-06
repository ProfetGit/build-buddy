package io.github.profetgit.cyanotype.command;

import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.LitematicWriter;
import io.github.profetgit.cyanotype.demo.Samples;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.BlueprintLibrary;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.PlacementStore;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.verify.Counts;
import io.github.profetgit.cyanotype.verify.Verifier;
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
 * Chat commands (typed as /cyanotype ..., handled on the client and never sent to a server) for the things the mouse
 * tools do not cover yet: choosing a file, naming a placement, exact numbers. The Library and Materials panels (M4) will
 * replace the file and list commands.
 */
public final class DevCommands {
    private static Blueprint loaded;
    private static String loadedRef = "";
    private static String loadedName = "";

    private DevCommands() {
    }

    /** The blueprint last loaded with /cyanotype load (dev checks). */
    public static Blueprint loadedBlueprint() {
        return loaded;
    }

    public static boolean handles(String message) {
        String m = message.startsWith("/") ? message.substring(1) : message;
        return m.equals("cyanotype") || m.startsWith("cyanotype ");
    }

    public static void run(String message) {
        Minecraft mc = Minecraft.getInstance();
        String line = (message.startsWith("/") ? message.substring(1) : message).substring("cyanotype".length()).trim();
        String[] parts = line.isEmpty() ? new String[0] : line.split("\\s+", 2);
        String cmd = parts.length == 0 ? "help" : parts[0].toLowerCase(Locale.ROOT);
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
                default -> help();
            }
        } catch (RuntimeException e) {
            Cyanotype.LOG.error("Cyanotype command failed: {}", line, e);
            say("Something went wrong: " + e.getMessage());
        }
    }

    private static void say(String text) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Cyanotype] " + text));
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
            say("No .litematic files yet. Put some in " + BlueprintLibrary.saveDir() + ", or try /cyanotype sample.");
            return;
        }
        for (Path p : files) say(p.getFileName().toString());
    }

    private static void load(String name) {
        if (name.isEmpty()) {
            say("Which file? Try /cyanotype list.");
            return;
        }
        Path found = BlueprintLibrary.find(name);
        if (found == null) {
            say("No file called \"" + name + "\". /cyanotype list shows what there is.");
            return;
        }
        say("Loading " + found.getFileName() + " ...");
        long t0 = System.nanoTime();
        BlueprintLibrary.load(found, bp -> {
            loaded = bp;
            loadedRef = BlueprintLibrary.refOf(found);
            loadedName = bp.meta.name().isBlank() ? found.getFileName().toString().replaceFirst("(?i)\\.litematic$", "") : bp.meta.name();
            say(loadedName + ": " + bp.sizeX + " x " + bp.sizeY + " x " + bp.sizeZ + ", " + bp.totalBlocks() + " blocks, " + bp.regions.size() + " region(s), read in " + (System.nanoTime() - t0) / 1_000_000 + " ms.");
            if (!bp.unknownBlocks().isEmpty()) say("Unknown blocks (shown red): " + String.join(", ", bp.unknownBlocks()));
            say("Now /cyanotype place, then aim and click.");
        }, why -> say("Could not open " + found.getFileName() + ": " + why));
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
            say("Load something first: /cyanotype list, then /cyanotype load <name>.");
            return;
        }
        if (arg.isEmpty()) {
            Interaction.startPlacing(loadedName, loaded, loadedRef);
            return;
        }
        BlockPos at = parsePos(arg);
        if (at == null) {
            say("Use /cyanotype place x y z, or no numbers to aim it with the mouse.");
            return;
        }
        Placement p = new Placement(loadedName, loaded, loadedRef, mc.level.dimension().identifier().toString(), at, Orientation.NONE);
        p.locked = true;
        p.opacity = io.github.profetgit.cyanotype.ui.Settings.get().opacity;
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
            say("Nothing placed. /cyanotype load <name>, then /cyanotype place.");
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
        if (p == null) say(arg.isEmpty() ? "No active placement. /cyanotype placements lists them, /cyanotype select picks one." : "No placement \"" + arg + "\".");
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
            say("Use /cyanotype move x y z.");
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
        int[] xz = io.github.profetgit.cyanotype.interaction.Moves.keepCenter(p.origin.getX(), p.origin.getZ(), p.sizeX(), p.sizeZ(),
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
            say("Use /cyanotype opacity 5 to 100.");
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
                say("Use /cyanotype layer 3, layer 3 5, layer up, layer down or layer all.");
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
