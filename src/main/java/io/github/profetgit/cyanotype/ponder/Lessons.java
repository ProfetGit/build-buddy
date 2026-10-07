package io.github.profetgit.cyanotype.ponder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.ui.LessonActions;
import io.github.profetgit.cyanotype.ui.Settings;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The lessons the mod ships: their list ({@code assets/cyanotype/ponders/index.json}), loading them (once, cached; a lesson that
 * cannot be read is logged with where in the file and skipped, never breaking the rest), which ones the player has seen, and
 * opening them: replayed from a ?, or the first time a tool is used.
 */
public final class Lessons {
    /** Where lesson files come from: the game's resources in play, a folder in tests. */
    public interface Source {
        @Nullable InputStream open(String path) throws IOException;
    }

    private static Source source = Lessons::fromResources;
    private static final Map<String, Optional<Scene>> CACHE = new HashMap<>();
    private static List<String> index;

    private Lessons() {
    }

    private static @Nullable InputStream fromResources(String path) throws IOException {
        var res = Minecraft.getInstance().getResourceManager().getResource(Identifier.fromNamespaceAndPath(Cyanotype.MOD_ID, "ponders/" + path));
        return res.isEmpty() ? null : res.get().open();
    }

    /** Tests: read lessons from somewhere else. */
    public static synchronized void useSource(Source s) {
        source = s;
        clearCache();
    }

    public static synchronized void clearCache() {
        CACHE.clear();
        index = null;
    }

    /** The ids of the lessons in the order they are listed. */
    public static synchronized List<String> ids() {
        if (index != null) return index;
        List<String> out = new ArrayList<>();
        try (InputStream in = source.open("index.json")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                    for (JsonElement e : o.getAsJsonArray("lessons")) out.add(e.getAsString());
                }
            } else {
                Cyanotype.LOG.warn("The lesson list ponders/index.json is missing");
            }
        } catch (IOException | RuntimeException e) {
            Cyanotype.LOG.warn("Cannot read the lesson list: {}", e.toString());
        }
        index = List.copyOf(out);
        return index;
    }

    /** A lesson by id, or null when there is none or it cannot be read. */
    public static synchronized @Nullable Scene scene(String id) {
        Optional<Scene> have = CACHE.get(id);
        if (have != null) return have.orElse(null);
        Scene made = null;
        try (InputStream in = source.open(id + ".json")) {
            if (in == null) Cyanotype.LOG.warn("Lesson '{}' has no file", id);
            else made = SceneReader.read(in);
            if (made != null && !made.id.equals(id)) {
                Cyanotype.LOG.warn("Lesson file {}.json says its id is '{}'", id, made.id);
                made = null;
            }
        } catch (SceneException e) {
            Cyanotype.LOG.warn("Lesson '{}' cannot be shown: {}", id, e.getMessage());
        } catch (IOException | JsonParseException e) {
            Cyanotype.LOG.warn("Lesson '{}' cannot be read: {}", id, e.toString());
        }
        CACHE.put(id, Optional.ofNullable(made));
        return made;
    }

    /** Every lesson that can be shown, in list order. */
    public static List<Scene> all() {
        List<Scene> out = new ArrayList<>();
        for (String id : ids()) {
            Scene s = scene(id);
            if (s != null) out.add(s);
        }
        return out;
    }

    // ---- seen

    public static boolean seen(String id) {
        return Settings.get().lessonsSeen.contains(id);
    }

    public static void markSeen(String id) {
        if (!Settings.get().lessonsSeen.contains(id)) {
            Settings.get().lessonsSeen.add(id);
            Settings.changed();
        }
    }

    public static void resetSeen() {
        Settings.get().lessonsSeen.clear();
        Settings.changed();
    }

    // ---- opening

    /** Shows a lesson again, from a ?: Try it starts its tool, Close starts nothing. Says so on the action bar when there is no such lesson. */
    public static boolean open(Minecraft mc, String id) {
        Scene s = scene(id);
        if (s == null) return false;
        markSeen(id);
        mc.gui.setScreen(new PonderScreen(s, PonderScreen.Mode.REPLAY, LessonActions.of(mc, s.action), LessonActions.why(mc, s.action)));
        return true;
    }

    /**
     * The first time a tool is used: plays its lesson, then starts the tool (Try it and Skip both do). When lessons are off, the
     * lesson was seen before, or it cannot be shown, the tool just starts.
     */
    public static void firstUse(Minecraft mc, String id, Runnable start) {
        if (!Settings.get().lessons || seen(id)) {
            start.run();
            return;
        }
        Scene s = scene(id);
        if (s == null) {
            start.run();
            return;
        }
        markSeen(id);
        mc.gui.setScreen(new PonderScreen(s, PonderScreen.Mode.FIRST_USE, start, ""));
    }
}
