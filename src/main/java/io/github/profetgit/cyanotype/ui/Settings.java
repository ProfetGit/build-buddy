package io.github.profetgit.cyanotype.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's settings, in {@code config/cyanotype.json}. Every one has a default that works; the Settings screen is the
 * only place that needs to know the file exists. Changes apply at once and are written a moment later.
 */
public final class Settings {
    /** The defaults are the field initialisers. A field missing from the file keeps its default. */
    public static final class Data {
        // Appearance
        public boolean chips = true;
        public boolean reduceMotion = false;
        public float sounds = 1.0f;
        public int wheelHoldMs = 200;
        // Ghost
        public float opacity = 0.6f;
        public int range = 192;
        public boolean fade = true;
        public boolean verify = true;
        /** Where new builds are saved (and downloads and dropped files go): the game's schematics folder, which Litematica reads too; off = config/cyanotype/blueprints. */
        public boolean saveToSchematics = true;
        // Materials
        public boolean groupVariants = false;
        // Community: "ask" until the player answers the first-run notice, then "on" or "off"
        public String community = "ask";
        /** Address of the community site; empty = the built-in one (CommunityConfig.DEFAULT_BASE_URL). */
        public String communityUrl = "";
        // Auto-place
        public int autoRate = io.github.profetgit.cyanotype.auto.Rate.DEFAULT;
        public boolean autoTurn = false;
        // Lessons ("How it works"): shown the first time a tool is used; the ids already seen
        public boolean lessons = true;
        public java.util.List<String> lessonsSeen = new java.util.ArrayList<>();
        // Advanced
        public boolean showNames = true;
        /** The author last typed on the Save screen; empty = the player's name. */
        public String author = "";
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SAVE_DELAY_MS = 600;
    private static Data data = new Data();
    private static volatile long dirtyAt;

    private Settings() {
    }

    public static Data get() {
        return data;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("cyanotype.json");
    }

    public static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            apply();
            return;
        }
        try {
            Data read = GSON.fromJson(Files.readString(f), Data.class);
            if (read != null) data = read;
        } catch (IOException | JsonSyntaxException e) {
            Cyanotype.LOG.warn("Cannot read {} (using the defaults): {}", f, e.getMessage());
        }
        clamp();
        apply();
    }

    private static void clamp() {
        data.sounds = Math.max(0f, Math.min(1f, data.sounds));
        data.opacity = Math.max(0.05f, Math.min(1f, data.opacity));
        data.range = Math.max(32, Math.min(512, data.range));
        data.wheelHoldMs = Math.max(80, Math.min(600, data.wheelHoldMs));
        data.autoRate = io.github.profetgit.cyanotype.auto.Rate.clamp(data.autoRate);
        if (!"on".equals(data.community) && !"off".equals(data.community)) data.community = "ask";
        data.communityUrl = data.communityUrl == null ? "" : data.communityUrl.trim();
        if (data.lessonsSeen == null) data.lessonsSeen = new java.util.ArrayList<>();
    }

    /** Pushes the settings into the parts that use them. */
    public static void apply() {
        GhostRenderer.range = data.range;
        GhostRenderer.verifyEnabled = data.verify;
        GhostRenderer.fadeEnabled = data.fade;
    }

    /** Call after changing a field: applies it and schedules a save. */
    public static void changed() {
        clamp();
        apply();
        dirtyAt = System.currentTimeMillis();
    }

    /** Writes a pending change once it has been quiet for a moment. */
    public static void tick() {
        long at = dirtyAt;
        if (at != 0 && System.currentTimeMillis() - at >= SAVE_DELAY_MS) {
            dirtyAt = 0;
            save();
        }
    }

    /** Writes a pending change now (the Settings screen closing). */
    public static void flush() {
        if (dirtyAt != 0) {
            dirtyAt = 0;
            save();
        }
    }

    /** Back to the defaults. */
    public static void reset() {
        data = new Data();
        changed();
    }

    public static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Cyanotype.LOG.error("Cannot save {}", f, e);
        }
    }
}
