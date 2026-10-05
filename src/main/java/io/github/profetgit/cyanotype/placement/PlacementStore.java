package io.github.profetgit.cyanotype.placement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.profetgit.cyanotype.Cyanotype;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Placements per world (singleplayer: the save folder) or per server address, kept as JSON in
 * {@code config/cyanotype/placements/}. A save is written a moment after the last change, off the main thread, to a
 * temporary file that then replaces the old one, so a crash cannot leave half a file. A placement whose blueprint file
 * is gone is kept (and reported) rather than dropped.
 */
public final class PlacementStore {
    public static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SAVE_DELAY_MS = 800;

    /** One saved placement, before its blueprint has been read. */
    public record Entry(String name, String ref, String dimension, BlockPos origin, Orientation orientation, float opacity, boolean visible, boolean locked, int accent, int layerLo, int layerHi) {
    }

    private static volatile long dirtyAtMs;
    private static volatile Path file;

    private PlacementStore() {
    }

    // ---- format

    public static String toJson(List<Placement> placements) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray list = new JsonArray();
        for (Placement p : placements) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.name);
            o.addProperty("file", p.ref);
            o.addProperty("dimension", p.dimension);
            JsonArray at = new JsonArray();
            at.add(p.origin.getX());
            at.add(p.origin.getY());
            at.add(p.origin.getZ());
            o.add("origin", at);
            o.addProperty("rotation", p.orientation.rotation().name().toLowerCase(Locale.ROOT));
            o.addProperty("mirror", p.orientation.mirror().name().toLowerCase(Locale.ROOT));
            o.addProperty("opacity", p.opacity);
            o.addProperty("visible", p.visible);
            o.addProperty("locked", p.locked);
            o.addProperty("accent", String.format(Locale.ROOT, "#%06X", p.accent & 0xFFFFFF));
            if (p.layered()) {
                JsonArray layers = new JsonArray();
                layers.add(p.layerLo);
                layers.add(p.layerHi);
                o.add("layers", layers);
            }
            list.add(o);
        }
        root.add("placements", list);
        return GSON.toJson(root);
    }

    /** Reads a save; entries it cannot make sense of are skipped, a file that is not JSON gives an empty list. */
    public static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            Cyanotype.LOG.warn("Placements file is not valid JSON: {}", e.getMessage());
            return out;
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("placements")) return out;
        for (JsonElement e : root.getAsJsonObject().getAsJsonArray("placements")) {
            try {
                JsonObject o = e.getAsJsonObject();
                JsonArray at = o.getAsJsonArray("origin");
                Rotation rotation = Rotation.valueOf(o.get("rotation").getAsString().toUpperCase(Locale.ROOT));
                Mirror mirror = Mirror.valueOf(o.get("mirror").getAsString().toUpperCase(Locale.ROOT));
                String accent = o.has("accent") ? o.get("accent").getAsString() : "";
                int color = accent.startsWith("#") ? 0xFF000000 | Integer.parseInt(accent.substring(1), 16) : Placement.ACCENTS[0];
                out.add(new Entry(
                    o.get("name").getAsString(), o.get("file").getAsString(), o.has("dimension") ? o.get("dimension").getAsString() : "minecraft:overworld",
                    new BlockPos(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt()), new Orientation(rotation, mirror),
                    o.has("opacity") ? Math.max(0.05f, Math.min(1f, o.get("opacity").getAsFloat())) : 0.6f,
                    !o.has("visible") || o.get("visible").getAsBoolean(), !o.has("locked") || o.get("locked").getAsBoolean(), color,
                    o.has("layers") ? o.getAsJsonArray("layers").get(0).getAsInt() : -1, o.has("layers") ? o.getAsJsonArray("layers").get(1).getAsInt() : -1));
            } catch (RuntimeException ex) {
                Cyanotype.LOG.warn("Skipping a placement that cannot be read: {}", ex.toString());
            }
        }
        return out;
    }

    // ---- where

    /** One file per singleplayer save or multiplayer address. */
    public static String worldKey(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "sp-" + sanitize(root.getFileName().toString());
        }
        ServerData server = mc.getCurrentServer();
        if (server != null) return "mp-" + sanitize(server.ip);
        return "other";
    }

    static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
    }

    public static Path fileFor(String key) {
        return FabricLoader.getInstance().getConfigDir().resolve("cyanotype").resolve("placements").resolve(key + ".json");
    }

    // ---- loading and saving

    /** Reads the placements of a world; their blueprints load in the background and the ghosts appear as they arrive. */
    public static void load(String key) {
        file = fileFor(key);
        Placements.clear();
        if (!Files.isRegularFile(file)) return;
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            Cyanotype.LOG.warn("Cannot read {}", file, e);
            return;
        }
        for (Entry e : parse(text)) {
            Placement p = new Placement(e.name, null, e.ref, e.dimension, e.origin, e.orientation);
            p.opacity = e.opacity;
            p.visible = e.visible;
            p.locked = e.locked;
            p.accent = e.accent;
            p.layerLo = e.layerLo;
            p.layerHi = e.layerHi;
            Placements.restore(p);
            Path bp = BlueprintLibrary.resolve(e.ref);
            BlueprintLibrary.load(bp, blueprint -> {
                p.blueprint = blueprint;
                p.status = Placement.Status.READY;
            }, why -> {
                p.status = why.equals("missing") ? Placement.Status.MISSING : Placement.Status.FAILED;
                p.problem = why;
            });
        }
    }

    public static void markDirty() {
        dirtyAtMs = System.currentTimeMillis();
    }

    /** Writes a pending change once it has been quiet for a moment. */
    public static void tick() {
        long at = dirtyAtMs;
        if (at != 0 && System.currentTimeMillis() - at >= SAVE_DELAY_MS) flush();
    }

    /** Writes now if anything changed since the last write. */
    public static void flush() {
        flush(false);
    }

    /** @param sync write on this thread (when the world is about to go away) instead of in the background */
    public static void flush(boolean sync) {
        if (dirtyAtMs == 0 || file == null) return;
        dirtyAtMs = 0;
        Path target = file;
        String json = toJson(new ArrayList<>(Placements.all()));
        if (sync) write(target, json);
        else Util.ioPool().execute(() -> write(target, json));
    }

    static void write(Path target, String json) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Cyanotype.LOG.error("Cannot save placements to {}", target, e);
        }
    }

    public static void detach() {
        file = null;
        dirtyAtMs = 0;
    }
}
