package io.github.profetgit.cyanotype.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One lesson: a stage, a few groups of blocks, and tracks over time (camera, group transforms, cells appearing, cursor, captions,
 * chips, overlays, panels). Immutable data with no game classes in it (block states are strings), so it loads, evaluates and is
 * tested without a running game; {@link Evaluator#at} turns it and a time into what is on screen. The file format is described in
 * PRD 7.12a; {@link SceneReader} reads it.
 */
public final class Scene {
    public static final int FORMAT = 1;
    /** The mod's ghost opacity, as a ghost group is drawn before its own alpha track applies. */
    public static final double GHOST_OPACITY = 0.6;

    public enum Mode { SOLID, GHOST }

    /** How a block comes or goes: nothing, falls in with a bounce, swells in, fades. */
    public enum Anim {
        NONE, DROP, POP, FADE;

        public static @Nullable Anim parse(String s) {
            return switch (s) {
                case "none" -> NONE;
                case "drop" -> DROP;
                case "pop" -> POP;
                case "fade" -> FADE;
                default -> null;
            };
        }
    }

    /** A cell appearing ({@code reveal}) or going ({@code !reveal}) at a time. */
    public record Ev(double t, boolean reveal, Anim anim, double dur) {
    }

    /** A block grid with its own transform: {@code cell[(y * sz + z) * sx + x]} is a palette number plus one (0 = air). */
    public static final class Group {
        public final String id;
        public final Mode mode;
        public final boolean startFull;
        /** The groups whose blocks this one matches against (a ghost: hidden where one of them holds the same block, wrong where it holds another). Empty = none. */
        public final List<String> match;
        public final int sx, sy, sz;
        public final int[] cell;
        public final double[] pos;
        /** The keyframed transform: pos (3), turn (1, quarter turns, positive = clockwise seen from above), mirror (1), alpha (1), visible (1), window (2: lowest and highest layer shown), scale (1). */
        public final Keyed keys;
        /** The reveals and clears of each cell in time order, or null for a cell that never changes. */
        public final Ev[][] events;

        Group(String id, Mode mode, boolean startFull, List<String> match, int sx, int sy, int sz, int[] cell, double[] pos, Keyed keys, Ev[][] events) {
            this.id = id;
            this.mode = mode;
            this.startFull = startFull;
            this.match = match;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.cell = cell;
            this.pos = pos;
            this.keys = keys;
            this.events = events;
        }

        public int index(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }

        public int volume() {
            return cell.length;
        }
    }

    /** Named tracks, one per field of a keyframe list ("pos", "turn", "yaw"...), each over the keys that name it. */
    public static final class Keyed {
        public static final Keyed EMPTY = new Keyed(Map.of());
        private final Map<String, Track> tracks;

        public Keyed(Map<String, Track> tracks) {
            this.tracks = tracks;
        }

        public @Nullable Track get(String field) {
            return tracks.get(field);
        }

        public boolean has(String field) {
            return tracks.containsKey(field);
        }

        public double num(String field, double t, double def) {
            Track tr = tracks.get(field);
            return tr == null ? def : tr.scalar(t);
        }

        public double[] vec(String field, double t, double[] def) {
            Track tr = tracks.get(field);
            return tr == null ? def : tr.at(t);
        }

        public Map<String, Track> all() {
            return Collections.unmodifiableMap(tracks);
        }
    }

    /** The static properties of an overlay or panel, with typed reads that fall back to a default. */
    public static final class Props {
        public static final Props EMPTY = new Props(new JsonObject());
        private final JsonObject o;

        public Props(JsonObject o) {
            this.o = o;
        }

        public boolean has(String k) {
            return o.has(k);
        }

        public String str(String k, String def) {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
        }

        public double num(String k, double def) {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : def;
        }

        public boolean bool(String k, boolean def) {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
        }

        public double @Nullable [] vec(String k) {
            JsonElement e = o.get(k);
            if (e == null || !e.isJsonArray()) return null;
            JsonArray a = e.getAsJsonArray();
            double[] out = new double[a.size()];
            for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
            return out;
        }

        public List<String> strings(String k) {
            JsonElement e = o.get(k);
            if (e == null || !e.isJsonArray()) return List.of();
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
            return out;
        }

        /** A list of points ([[x, y, z], ...]). */
        public List<double[]> points(String k) {
            JsonElement e = o.get(k);
            if (e == null || !e.isJsonArray()) return List.of();
            java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) {
                JsonArray a = x.getAsJsonArray();
                double[] p = new double[a.size()];
                for (int i = 0; i < p.length; i++) p[i] = a.get(i).getAsDouble();
                out.add(p);
            }
            return out;
        }

        /** A list of rows, each a list of strings ([["Scroll", "Turn it"], ...]). */
        public List<List<String>> rows(String k) {
            JsonElement e = o.get(k);
            if (e == null || !e.isJsonArray()) return List.of();
            java.util.ArrayList<List<String>> out = new java.util.ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) {
                java.util.ArrayList<String> row = new java.util.ArrayList<>();
                for (JsonElement y : x.getAsJsonArray()) row.add(y.getAsString());
                out.add(row);
            }
            return out;
        }

        public java.util.Set<String> keys() {
            return o.keySet();
        }

        /** This one with the properties of {@code over} laid on top. */
        public Props with(Props over) {
            if (over == EMPTY || over.o.size() == 0) return this;
            JsonObject m = o.deepCopy();
            for (Map.Entry<String, JsonElement> en : over.o.entrySet()) m.add(en.getKey(), en.getValue());
            return new Props(m);
        }
    }

    /** From a time on, these properties replace the item's own (a list whose counts change, a button that is focused). */
    public record State(double t, Props props) {
    }

    /** An overlay in the stage or a mock panel over it: a type, a lifetime, static properties, keyframed ones, and discrete states. */
    public record Item(String type, String id, double t0, double t1, double fade, Props props, Keyed keys, List<State> states) {
        public Props propsAt(double t) {
            Props p = props;
            for (State s : states) {
                if (s.t() <= t) p = p.with(s.props());
                else break;
            }
            return p;
        }

        /** How visible the item is at a time: faded in and out at its ends. */
        public double alphaAt(double t) {
            if (t < t0 || t > t1) return 0;
            double in = fade <= 0 ? 1 : Math.min(1, (t - t0) / fade), out = fade <= 0 ? 1 : Math.min(1, (t1 - t) / fade);
            return Math.max(0, Math.min(in, out));
        }
    }

    /** A sound the lesson plays when it passes {@code t}: one of the mod's interface sounds, at a volume and pitch. */
    public record SoundCue(double t, String name, float volume, float pitch) {
    }

    public record Caption(double t0, double t1, String title, String text) {
    }

    /** A set of cursor chips shown between two times; {@code lit} lights one row for a while: {row, from, to}. */
    public record Chips(double t0, double t1, List<List<String>> rows, List<double[]> lit) {
    }

    /** Where the mock cursor points: a point of the world, a spot of the picture (0 to 1 from its top left), or a spot of a mock panel (its type and a row, a card, a button). */
    public record Pt(int kind, double[] at, String panel, int row) {
        public static final int WORLD = 0, SCREEN = 1, PANEL = 2;
    }

    /** One cursor keyframe. */
    public record CursorKey(double t, Pt pt, boolean down, String shape, double alpha, Ease ease) {
    }

    public final String id, title, summary, action;
    public final List<String> tags;
    public final double duration;
    public final int[] size;
    public final double[] focus;
    /** The part of the picture the stage has to stay inside, as fractions: x0, y0, x1, y1 (a lesson with a panel on one side keeps the stage out from under it). */
    public final double[] frame;
    /** Palette number minus one -> block state string, and the one-character key the file gave it. */
    public final List<String> palette, paletteKeys;
    public final List<Group> groups;
    public final Keyed camera;
    public final List<CursorKey> cursor;
    public final List<Caption> captions;
    public final List<Chips> chips;
    public final List<Item> overlays, panels;
    public final List<SoundCue> sounds;

    Scene(String id, String title, String summary, String action, List<String> tags, double duration, int[] size, double[] focus, double[] frame, List<String> palette, List<String> paletteKeys, List<Group> groups, Keyed camera,
          List<CursorKey> cursor, List<Caption> captions, List<Chips> chips, List<Item> overlays, List<Item> panels, List<SoundCue> sounds) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.action = action;
        this.tags = tags;
        this.duration = duration;
        this.size = size;
        this.focus = focus;
        this.frame = frame;
        this.palette = palette;
        this.paletteKeys = paletteKeys;
        this.groups = groups;
        this.camera = camera;
        this.cursor = cursor;
        this.captions = captions;
        this.chips = chips;
        this.overlays = overlays;
        this.panels = panels;
        this.sounds = sounds;
    }

    public @Nullable Group group(String id) {
        for (Group g : groups) if (g.id.equals(id)) return g;
        return null;
    }

    /** The number of steps: one for each caption (a lesson without captions is one step). */
    public int steps() {
        return Math.max(1, captions.size());
    }

    /** Where step {@code i} (0-based) starts and ends. */
    public double stepStart(int i) {
        return captions.isEmpty() ? 0 : captions.get(Math.max(0, Math.min(i, captions.size() - 1))).t0();
    }

    public double stepEnd(int i) {
        return captions.isEmpty() ? duration : captions.get(Math.max(0, Math.min(i, captions.size() - 1))).t1();
    }

    /** The step a time falls in: the one whose caption is showing, or the last one that has started. */
    public int stepAt(double t) {
        int step = 0;
        for (int i = 0; i < captions.size(); i++) {
            if (captions.get(i).t0() <= t) step = i;
        }
        return step;
    }
}
