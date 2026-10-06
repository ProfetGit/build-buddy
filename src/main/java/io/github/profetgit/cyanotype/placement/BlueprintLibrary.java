package io.github.profetgit.cyanotype.placement;

import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;

/**
 * Where blueprint files live and a cache of the ones read. A file is named by a short reference ("cyanotype:house.litematic"
 * for the mod's own folder, "schematics:house.litematic" for Litematica's), which is what a saved placement stores.
 */
public final class BlueprintLibrary {
    public static final String OWN = "cyanotype:", LITEMATICA = "schematics:";

    private record Cached(long modified, long size, Blueprint blueprint) {
    }

    private static final Map<Path, Cached> CACHE = new HashMap<>();

    private BlueprintLibrary() {
    }

    public static Path ownDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("cyanotype").resolve("blueprints");
    }

    public static Path litematicaDir() {
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
    }

    /** Where a new build is written: the game's schematics folder (shared with Litematica) unless the setting says the mod's own folder. */
    public static Path saveDir() {
        return io.github.profetgit.cyanotype.ui.Settings.get().saveToSchematics ? litematicaDir() : ownDir();
    }

    /** Every schematic (.litematic, .schem, .schematic) in both folders, sorted by name within each. */
    public static List<Path> files() {
        List<Path> out = new ArrayList<>();
        for (Path dir : List.of(ownDir(), litematicaDir())) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> io.github.profetgit.cyanotype.blueprint.SchematicReader.opens(p.getFileName().toString())).sorted().forEach(out::add);
            } catch (IOException e) {
                Cyanotype.LOG.warn("Cannot list {}", dir, e);
            }
        }
        return out;
    }

    public static String refOf(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        String name = abs.getFileName().toString();
        if (abs.getParent().equals(ownDir().toAbsolutePath().normalize())) return OWN + name;
        if (abs.getParent().equals(litematicaDir().toAbsolutePath().normalize())) return LITEMATICA + name;
        return abs.toString();
    }

    public static Path resolve(String ref) {
        if (ref.startsWith(OWN)) {
            Path own = ownDir().resolve(ref.substring(OWN.length()));
            // a build moved from the mod's old folder into schematics/ is still found by the reference a saved placement kept
            Path moved = litematicaDir().resolve(ref.substring(OWN.length()));
            return !Files.exists(own) && Files.exists(moved) ? moved : own;
        }
        if (ref.startsWith(LITEMATICA)) return litematicaDir().resolve(ref.substring(LITEMATICA.length()));
        return Path.of(ref);
    }

    /** Finds a file by name (with or without extension) among both folders. */
    public static Path find(String name) {
        for (Path p : files()) {
            String n = p.getFileName().toString();
            if (n.equalsIgnoreCase(name) || n.equalsIgnoreCase(name + ".litematic") || n.equalsIgnoreCase(name + ".schem") || n.equalsIgnoreCase(name + ".schematic")) return p;
        }
        return null;
    }

    /** Reads a blueprint on a background thread and calls back on the main thread; repeated reads of an unchanged file are served from memory. */
    public static void load(Path file, Consumer<Blueprint> ok, Consumer<String> fail) {
        Util.backgroundExecutor().execute(() -> {
            try {
                Path key = file.toAbsolutePath().normalize();
                long modified = Files.getLastModifiedTime(key).toMillis(), size = Files.size(key);
                Blueprint bp = null;
                synchronized (CACHE) {
                    Cached c = CACHE.get(key);
                    if (c != null && c.modified == modified && c.size == size) bp = c.blueprint;
                }
                if (bp == null) {
                    bp = io.github.profetgit.cyanotype.blueprint.SchematicReader.read(key);
                    synchronized (CACHE) {
                        CACHE.put(key, new Cached(modified, size, bp));
                    }
                }
                Blueprint result = bp;
                net.minecraft.client.Minecraft.getInstance().execute(() -> ok.accept(result));
            } catch (java.nio.file.NoSuchFileException e) {
                net.minecraft.client.Minecraft.getInstance().execute(() -> fail.accept("missing"));
            } catch (IOException | RuntimeException e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                net.minecraft.client.Minecraft.getInstance().execute(() -> fail.accept(why));
            }
        });
    }
}
