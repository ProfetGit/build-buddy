package io.github.profetgit.cyanotype.command;

import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.LitematicException;
import io.github.profetgit.cyanotype.blueprint.LitematicReader;
import io.github.profetgit.cyanotype.blueprint.LitematicWriter;
import io.github.profetgit.cyanotype.demo.Samples;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Temporary chat commands (typed as /cyanotype ..., handled on the client and never sent to a server) so the ghost can
 * be tried before the real UI exists (milestone M4). One loaded blueprint, one placement.
 */
public final class DevCommands {
    private static Blueprint loaded;
    private static String loadedName = "";
    private static Placement placement;

    private DevCommands() {
    }

    public static Path blueprintDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("cyanotype").resolve("blueprints");
    }

    /** Where Litematica keeps its files, so existing libraries show up. */
    private static Path litematicaDir() {
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
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
                case "move" -> move(arg);
                case "rotate" -> rotate();
                case "mirror" -> mirror(arg);
                case "opacity" -> opacity(arg);
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
        say("/cyanotype list | load <name> | sample | place [x y z] | move x y z | rotate | mirror [x] | opacity <0-100> | info | clear");
        say("Files go in " + blueprintDir() + " (the schematics folder of Litematica works too).");
    }

    private static List<Path> files() {
        List<Path> out = new ArrayList<>();
        for (Path dir : List.of(blueprintDir(), litematicaDir())) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic")).sorted().forEach(out::add);
            } catch (IOException e) {
                Cyanotype.LOG.warn("Cannot list {}", dir, e);
            }
        }
        return out;
    }

    private static void list() {
        List<Path> files = files();
        if (files.isEmpty()) {
            say("No .litematic files yet. Put some in " + blueprintDir() + ", or try /cyanotype sample.");
            return;
        }
        for (Path p : files) say(p.getFileName().toString());
    }

    private static void load(String name) {
        if (name.isEmpty()) {
            say("Which file? Try /cyanotype list.");
            return;
        }
        Path found = null;
        for (Path p : files()) {
            String n = p.getFileName().toString();
            if (n.equalsIgnoreCase(name) || n.equalsIgnoreCase(name + ".litematic")) found = p;
        }
        if (found == null) {
            say("No file called \"" + name + "\". /cyanotype list shows what there is.");
            return;
        }
        Path file = found;
        say("Loading " + file.getFileName() + " ...");
        Util.backgroundExecutor().execute(() -> {
            long t0 = System.nanoTime();
            try {
                Blueprint bp = LitematicReader.read(file);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Minecraft.getInstance().execute(() -> {
                    loaded = bp;
                    loadedName = file.getFileName().toString();
                    say(bp.meta.name() + ": " + bp.sizeX + " x " + bp.sizeY + " x " + bp.sizeZ + ", " + bp.totalBlocks() + " blocks, " + bp.regions.size() + " region(s), read in " + ms + " ms.");
                    if (!bp.unknownBlocks().isEmpty()) say("Unknown blocks (shown red): " + String.join(", ", bp.unknownBlocks()));
                    say("Now /cyanotype place.");
                });
            } catch (LitematicException | IOException e) {
                Minecraft.getInstance().execute(() -> say("Could not open " + file.getFileName() + ": " + e.getMessage()));
            }
        });
    }

    private static void sample() {
        try {
            Files.createDirectories(blueprintDir());
            Path file = blueprintDir().resolve("sample-house.litematic");
            LitematicWriter.write(Samples.house(), file);
            loaded = LitematicReader.read(file);
            loadedName = file.getFileName().toString();
            say("Wrote and loaded " + file + ". Now /cyanotype place.");
        } catch (IOException e) {
            say("Could not write the sample: " + e.getMessage());
        }
    }

    private static void place(Minecraft mc, String arg) {
        if (loaded == null) {
            say("Load something first: /cyanotype list, then /cyanotype load <name>.");
            return;
        }
        BlockPos at;
        if (!arg.isEmpty()) {
            at = parsePos(arg);
            if (at == null) {
                say("Use /cyanotype place x y z, or no numbers to put it where you look.");
                return;
            }
        } else if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            at = hit.getBlockPos().relative(hit.getDirection());
        } else if (mc.player != null) {
            at = mc.player.blockPosition();
        } else {
            return;
        }
        if (placement != null) GhostRenderer.hide(placement);
        placement = new Placement(loadedName, loaded, at, Orientation.NONE);
        GhostRenderer.show(placement);
        say("Placed at " + at.getX() + " " + at.getY() + " " + at.getZ() + ".");
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

    private static boolean havePlacement() {
        if (placement == null) say("Nothing is placed yet.");
        return placement != null;
    }

    private static void move(String arg) {
        if (!havePlacement()) return;
        BlockPos at = parsePos(arg);
        if (at == null) {
            say("Use /cyanotype move x y z.");
            return;
        }
        placement.origin = at;
        GhostRenderer.rebuild(placement);
    }

    private static void rotate() {
        if (!havePlacement()) return;
        placement.orientation = new Orientation(placement.orientation.rotation().getRotated(Rotation.CLOCKWISE_90), placement.orientation.mirror());
        GhostRenderer.rebuild(placement);
        say("Rotated to " + placement.orientation.rotation().name().toLowerCase(Locale.ROOT) + ".");
    }

    private static void mirror(String arg) {
        if (!havePlacement()) return;
        Mirror target = arg.equalsIgnoreCase("x") ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT;
        Mirror now = placement.orientation.mirror() == target ? Mirror.NONE : target;
        placement.orientation = new Orientation(placement.orientation.rotation(), now);
        GhostRenderer.rebuild(placement);
        say("Mirror: " + now.name().toLowerCase(Locale.ROOT) + ".");
    }

    private static void opacity(String arg) {
        if (!havePlacement()) return;
        try {
            placement.opacity = Math.max(0.05f, Math.min(1f, Integer.parseInt(arg) / 100f));
            say("Opacity " + Math.round(placement.opacity * 100) + "%.");
        } catch (NumberFormatException e) {
            say("Use /cyanotype opacity 5 to 100.");
        }
    }

    private static void clear() {
        if (placement != null) GhostRenderer.hide(placement);
        placement = null;
        say("Cleared.");
    }

    private static void info() {
        if (loaded == null) {
            say("Nothing loaded.");
            return;
        }
        say(loadedName + ": " + loaded.totalBlocks() + " blocks" + (placement == null ? ", not placed." : ", placed at " + placement.origin.toShortString() + ", " + placement.orientation.rotation().name().toLowerCase(Locale.ROOT) + "."));
        say("Ghost: " + GhostRenderer.Stats.drawn + " sections drawn of " + GhostRenderer.Stats.sections + ", " + GhostRenderer.Stats.quadsDrawn + " quads.");
    }
}
