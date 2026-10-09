package io.github.profetgit.buildbuddy.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads a lesson file ({@code format: 1}, PRD 7.12a) into a {@link Scene}. Strict on purpose: an unknown field, a block key that
 * is not in the palette, a time outside the lesson or a ragged grid is an error that names the place ("overlays[2].dir: ..."),
 * because the files are written by a generator and a typo should fail loudly there, not show as a missing arrow in the game.
 */
public final class SceneReader {
    private SceneReader() {
    }

    public static Scene read(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(r));
        } catch (JsonParseException e) {
            throw new SceneException("", "not valid JSON: " + e.getMessage());
        }
    }

    public static Scene read(String json) {
        try {
            return parse(JsonParser.parseString(json));
        } catch (JsonParseException e) {
            throw new SceneException("", "not valid JSON: " + e.getMessage());
        }
    }

    private static final Set<String> TOP = Set.of("format", "id", "title", "summary", "tags", "action", "duration", "stage", "palette", "groups", "ops", "tracks", "captions", "chips", "overlays", "panels", "sounds");
    private static final Set<String> GROUP = Set.of("id", "mode", "pos", "layers", "start", "match");
    private static final Set<String> OP = Set.of("t", "group", "do", "cells", "layer", "layers", "box", "key", "all", "anim", "dur", "stagger", "order", "from");
    private static final Set<String> CAMERA_FIELDS = Set.of("t", "ease", "yaw", "pitch", "zoom", "focus");
    private static final Set<String> GROUP_FIELDS = Set.of("t", "ease", "pos", "turn", "mirror", "alpha", "visible", "window", "scale");
    private static final Set<String> CURSOR_FIELDS = Set.of("t", "ease", "at", "screen", "panel", "down", "shape", "alpha");
    private static final Set<String> ITEM = Set.of("type", "id", "t0", "t1", "fade", "keys", "states");
    /** Fields that hold a vector: how many numbers. */
    private static final Map<String, Integer> ARITY = Map.ofEntries(Map.entry("pos", 3), Map.entry("focus", 3), Map.entry("window", 2), Map.entry("at", 3), Map.entry("from", 3), Map.entry("to", 3), Map.entry("a", 3),
        Map.entry("b", 3), Map.entry("center", 3), Map.entry("screen", 2), Map.entry("cell", 3));
    /** Fields that switch rather than glide. */
    /** The interface sounds a lesson may play (Sfx). */
    static final Set<String> SOUND_NAMES = Set.of("ui_press", "ui_release", "wheel_tick", "lock", "snap", "complete", "error");
    private static final Set<String> STEPPED = Set.of("mirror", "visible", "down", "hot", "row");

    /** What each overlay type may have besides the common fields. */
    private static final Map<String, Set<String>> OVERLAY_TYPES = Map.of(
        "box", Set.of("from", "to", "color", "style", "brackets"),
        "arrow", Set.of("at", "dir", "len", "color", "hot", "off"),
        "disc", Set.of("center", "radius", "color", "alpha"),
        "ring", Set.of("center", "radius", "color", "angle", "hot"),
        "marker", Set.of("at", "color", "beam"),
        "tint", Set.of("group", "key", "cells", "color", "alpha", "pulse"),
        "label", Set.of("at", "text", "color", "anchor"),
        "dim", Set.of("a", "b", "text", "color"),
        "avatar", Set.of("at", "yaw", "color", "walk"),
        "path", Set.of("points", "color"));
    private static final Map<String, Set<String>> PANEL_TYPES = Map.of(
        "library", Set.of("rect", "cards", "hot", "title"),
        "save", Set.of("rect", "name", "typed", "tags", "button", "hot"),
        "list", Set.of("rect", "title", "rows", "hot", "note", "header", "buttons", "lit"),
        "warning", Set.of("rect", "title", "text", "buttons", "focus", "lit", "wait"),
        "bar", Set.of("rect", "label", "value", "done"),
        "stamp", Set.of("rect", "text", "color", "size"),
        "badge", Set.of("rect", "title", "right", "status", "color", "icon"));

    static Scene parse(JsonElement root) {
        JsonObject o = obj(root, "");
        strict(o, "", TOP);
        int format = (int) num(o, "format", "", true, 0);
        if (format != Scene.FORMAT) throw new SceneException("format", "this game reads lesson format " + Scene.FORMAT + ", the file is " + format);
        String id = str(o, "id", "", true, "");
        if (!id.matches("[a-z][a-z0-9-]*")) throw new SceneException("id", "use lower case letters, digits and dashes");
        String title = str(o, "title", "", true, ""), summary = str(o, "summary", "", true, ""), action = str(o, "action", "", false, "none");
        double duration = num(o, "duration", "", true, 0);
        if (duration <= 0 || duration > 120) throw new SceneException("duration", "a lesson is 0 to 120 seconds long");
        List<String> tags = new ArrayList<>();
        if (o.has("tags")) for (JsonElement e : arr(o.get("tags"), "tags")) tags.add(e.getAsString());

        int[] size = {16, 8, 16};
        double[] focus;
        double[] frame = {0, 0, 1, 1};
        if (o.has("stage")) {
            JsonObject st = obj(o.get("stage"), "stage");
            strict(st, "stage", Set.of("size", "focus", "frame"));
            double[] s = vec(st, "size", 3, "stage", true);
            for (int i = 0; i < 3; i++) {
                size[i] = (int) s[i];
                if (size[i] < 1 || size[i] > 64) throw new SceneException("stage.size", "each side is 1 to 64 blocks");
            }
            focus = st.has("focus") ? vec(st, "focus", 3, "stage", true) : new double[]{size[0] / 2.0, size[1] / 2.0, size[2] / 2.0};
            if (st.has("frame")) {
                frame = vec(st, "frame", 4, "stage", true);
                if (frame[0] < 0 || frame[1] < 0 || frame[2] > 1 || frame[3] > 1 || frame[2] - frame[0] < 0.2 || frame[3] - frame[1] < 0.2) throw new SceneException("stage.frame", "x0, y0, x1, y1 as fractions of the picture, at least 0.2 wide and tall");
            }
        } else {
            focus = new double[]{size[0] / 2.0, size[1] / 2.0, size[2] / 2.0};
        }

        // the palette: keys of one character
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<String> palette = new ArrayList<>();
        if (o.has("palette")) {
            JsonObject p = obj(o.get("palette"), "palette");
            for (Map.Entry<String, JsonElement> en : p.entrySet()) {
                if (en.getKey().length() != 1 || en.getKey().equals(".") || en.getKey().equals(" ")) throw new SceneException("palette." + en.getKey(), "a palette key is one character, and '.' and ' ' mean air");
                String spec = en.getValue().getAsString();
                if (spec.isBlank()) throw new SceneException("palette." + en.getKey(), "empty block");
                paletteIndex.put(en.getKey(), palette.size());
                palette.add(spec);
            }
        }

        // groups
        List<Scene.Group> groups = new ArrayList<>();
        Map<String, Integer> groupAt = new LinkedHashMap<>();
        JsonObject tracks = o.has("tracks") ? obj(o.get("tracks"), "tracks") : new JsonObject();
        strict(tracks, "tracks", Set.of("camera", "groups", "cursor"));
        JsonObject groupTracks = tracks.has("groups") ? obj(tracks.get("groups"), "tracks.groups") : new JsonObject();
        if (o.has("groups")) {
            JsonArray ga = arr(o.get("groups"), "groups");
            for (int i = 0; i < ga.size(); i++) {
                String path = "groups[" + i + "]";
                JsonObject g = obj(ga.get(i), path);
                strict(g, path, GROUP);
                String gid = str(g, "id", path, true, "");
                if (groupAt.containsKey(gid)) throw new SceneException(path + ".id", "two groups called '" + gid + "'");
                groupAt.put(gid, i);
                groups.add(readGroup(g, path, gid, paletteIndex, groupTracks, duration));
            }
        }
        for (String gid : groupTracks.keySet()) if (!groupAt.containsKey(gid)) throw new SceneException("tracks.groups." + gid, "there is no group called '" + gid + "'");
        // each match names other groups
        for (int i = 0; i < groups.size(); i++) {
            Scene.Group g = groups.get(i);
            for (String m : g.match) {
                if (!groupAt.containsKey(m)) throw new SceneException("groups[" + i + "].match", "there is no group called '" + m + "'");
                if (m.equals(g.id)) throw new SceneException("groups[" + i + "].match", "a group cannot match itself");
            }
        }

        // ops
        List<List<List<Scene.Ev>>> evLists = new ArrayList<>();
        for (Scene.Group g : groups) {
            List<List<Scene.Ev>> per = new ArrayList<>(g.volume());
            for (int c = 0; c < g.volume(); c++) per.add(null);
            evLists.add(per);
        }
        if (o.has("ops")) {
            JsonArray oa = arr(o.get("ops"), "ops");
            for (int i = 0; i < oa.size(); i++) readOp(obj(oa.get(i), "ops[" + i + "]"), "ops[" + i + "]", groups, groupAt, paletteIndex, evLists, duration);
        }
        List<Scene.Group> finalGroups = new ArrayList<>();
        for (int gi = 0; gi < groups.size(); gi++) {
            Scene.Group g = groups.get(gi);
            Scene.Ev[][] ev = new Scene.Ev[g.volume()][];
            for (int c = 0; c < ev.length; c++) {
                List<Scene.Ev> l = evLists.get(gi).get(c);
                if (l == null) continue;
                l.sort(Comparator.comparingDouble(Scene.Ev::t));
                ev[c] = l.toArray(new Scene.Ev[0]);
            }
            finalGroups.add(new Scene.Group(g.id, g.mode, g.startFull, g.match, g.sx, g.sy, g.sz, g.cell, g.pos, g.keys, ev));
        }

        Scene.Keyed camera = tracks.has("camera") ? keyed(arr(tracks.get("camera"), "tracks.camera"), "tracks.camera", CAMERA_FIELDS, duration) : Scene.Keyed.EMPTY;
        List<Scene.CursorKey> cursor = tracks.has("cursor") ? readCursor(arr(tracks.get("cursor"), "tracks.cursor"), duration) : List.of();

        List<Scene.Caption> captions = new ArrayList<>();
        if (o.has("captions")) {
            JsonArray ca = arr(o.get("captions"), "captions");
            double prevEnd = 0;
            for (int i = 0; i < ca.size(); i++) {
                String path = "captions[" + i + "]";
                JsonObject c = obj(ca.get(i), path);
                strict(c, path, Set.of("t0", "t1", "title", "text"));
                double t0 = num(c, "t0", path, true, 0), t1 = num(c, "t1", path, true, 0);
                window(path, t0, t1, duration);
                if (t0 < prevEnd - 1e-9) throw new SceneException(path, "starts before the caption before it ends");
                prevEnd = t1;
                captions.add(new Scene.Caption(t0, t1, str(c, "title", path, false, ""), str(c, "text", path, true, "")));
            }
        }

        List<Scene.Chips> chips = new ArrayList<>();
        if (o.has("chips")) {
            JsonArray ca = arr(o.get("chips"), "chips");
            for (int i = 0; i < ca.size(); i++) {
                String path = "chips[" + i + "]";
                JsonObject c = obj(ca.get(i), path);
                strict(c, path, Set.of("t0", "t1", "rows", "lit"));
                double t0 = num(c, "t0", path, true, 0), t1 = num(c, "t1", path, true, 0);
                window(path, t0, t1, duration);
                Scene.Props p = new Scene.Props(c);
                List<List<String>> rows = p.rows("rows");
                if (rows.isEmpty()) throw new SceneException(path + ".rows", "needs at least one [key, action] row");
                for (int r = 0; r < rows.size(); r++) {
                    if (rows.get(r).size() != 2) throw new SceneException(path + ".rows[" + r + "]", "a row is [key, action]");
                }
                List<double[]> lit = p.points("lit");
                for (double[] l : lit) {
                    if (l.length != 3 || l[0] < 0 || l[0] >= rows.size()) throw new SceneException(path + ".lit", "each is [row, from, to] with a row of this set");
                }
                chips.add(new Scene.Chips(t0, t1, rows, lit));
            }
        }

        List<Scene.SoundCue> sounds = new ArrayList<>();
        if (o.has("sounds")) {
            JsonArray sa = arr(o.get("sounds"), "sounds");
            for (int i = 0; i < sa.size(); i++) {
                String path = "sounds[" + i + "]";
                JsonObject c = obj(sa.get(i), path);
                strict(c, path, Set.of("t", "name", "volume", "pitch"));
                double t = num(c, "t", path, true, 0);
                if (t < 0 || t >= duration) throw new SceneException(path + ".t", "inside the lesson, 0 to " + duration);
                String name = str(c, "name", path, true, "");
                if (!SOUND_NAMES.contains(name)) throw new SceneException(path + ".name", "one of " + SOUND_NAMES);
                sounds.add(new Scene.SoundCue(t, name, (float) num(c, "volume", path, false, 1), (float) num(c, "pitch", path, false, 1)));
            }
            sounds.sort(Comparator.comparingDouble(Scene.SoundCue::t));
        }

        List<Scene.Item> overlays = readItems(o, "overlays", OVERLAY_TYPES, duration, groupAt);
        List<Scene.Item> panels = readItems(o, "panels", PANEL_TYPES, duration, groupAt);
        return new Scene(id, title, summary, action, List.copyOf(tags), duration, size, focus, frame, List.copyOf(palette), List.copyOf(paletteIndex.keySet()), List.copyOf(finalGroups), camera, List.copyOf(cursor), List.copyOf(captions), List.copyOf(chips), List.copyOf(overlays),
            List.copyOf(panels), List.copyOf(sounds));
    }

    // ---- groups

    private static Scene.Group readGroup(JsonObject g, String path, String gid, Map<String, Integer> palette, JsonObject groupTracks, double duration) {
        String modeName = str(g, "mode", path, false, "solid");
        Scene.Mode mode = switch (modeName) {
            case "solid" -> Scene.Mode.SOLID;
            case "ghost" -> Scene.Mode.GHOST;
            default -> throw new SceneException(path + ".mode", "'solid' or 'ghost', not '" + modeName + "'");
        };
        String start = str(g, "start", path, false, "full");
        if (!start.equals("full") && !start.equals("empty")) throw new SceneException(path + ".start", "'full' or 'empty'");
        double[] pos = g.has("pos") ? vec(g, "pos", 3, path, true) : new double[]{0, 0, 0};
        JsonArray layers = arr(g.get("layers"), path + ".layers");
        if (layers.isEmpty()) throw new SceneException(path + ".layers", "a group needs at least one layer");
        int sy = layers.size(), sz = -1, sx = -1;
        List<String[]> rows = new ArrayList<>();
        for (int y = 0; y < sy; y++) {
            JsonArray la = arr(layers.get(y), path + ".layers[" + y + "]");
            if (sz < 0) sz = la.size();
            if (la.size() != sz) throw new SceneException(path + ".layers[" + y + "]", "has " + la.size() + " rows, the first layer has " + sz);
            String[] r = new String[sz];
            for (int z = 0; z < sz; z++) {
                r[z] = la.get(z).getAsString();
                if (sx < 0) sx = r[z].length();
                if (r[z].length() != sx) throw new SceneException(path + ".layers[" + y + "][" + z + "]", "has " + r[z].length() + " characters, the first row has " + sx);
            }
            rows.add(r);
        }
        if (sx < 1 || sz < 1 || sx > 64 || sz > 64 || sy > 64) throw new SceneException(path + ".layers", "a group is 1 to 64 blocks on each side");
        int[] cell = new int[sx * sy * sz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                String row = rows.get(y)[z];
                for (int x = 0; x < sx; x++) {
                    char ch = row.charAt(x);
                    if (ch == '.' || ch == ' ') continue;
                    Integer pi = palette.get(String.valueOf(ch));
                    if (pi == null) throw new SceneException(path + ".layers[" + y + "][" + z + "]", "unknown block key '" + ch + "' at column " + x);
                    cell[(y * sz + z) * sx + x] = pi + 1;
                }
            }
        }
        Scene.Keyed keys = groupTracks.has(gid) ? keyed(arr(groupTracks.get(gid), "tracks.groups." + gid), "tracks.groups." + gid, GROUP_FIELDS, duration) : Scene.Keyed.EMPTY;
        List<String> match = new ArrayList<>();
        if (g.has("match")) {
            if (g.get("match").isJsonArray()) for (JsonElement e : g.getAsJsonArray("match")) match.add(e.getAsString());
            else match.add(str(g, "match", path, true, ""));
        }
        return new Scene.Group(gid, mode, start.equals("full"), List.copyOf(match), sx, sy, sz, cell, pos, keys, null);
    }

    private static void readOp(JsonObject op, String path, List<Scene.Group> groups, Map<String, Integer> groupAt, Map<String, Integer> palette, List<List<List<Scene.Ev>>> evLists, double duration) {
        strict(op, path, OP);
        double t = num(op, "t", path, true, 0);
        if (t < 0 || t > duration) throw new SceneException(path + ".t", "outside the lesson (0 to " + duration + ")");
        String gid = str(op, "group", path, true, "");
        Integer gi = groupAt.get(gid);
        if (gi == null) throw new SceneException(path + ".group", "there is no group called '" + gid + "'");
        Scene.Group g = groups.get(gi);
        String what = str(op, "do", path, true, "");
        boolean reveal = switch (what) {
            case "reveal" -> true;
            case "clear" -> false;
            default -> throw new SceneException(path + ".do", "'reveal' or 'clear', not '" + what + "'");
        };
        String animName = str(op, "anim", path, false, reveal ? "drop" : "fade");
        Scene.Anim anim = Scene.Anim.parse(animName);
        if (anim == null) throw new SceneException(path + ".anim", "'none', 'drop', 'pop' or 'fade'");
        double dur = num(op, "dur", path, false, anim == Scene.Anim.DROP ? 0.4 : anim == Scene.Anim.POP ? 0.25 : anim == Scene.Anim.FADE ? 0.3 : 0);
        if (dur < 0 || dur > 5) throw new SceneException(path + ".dur", "0 to 5 seconds");
        double stagger = num(op, "stagger", path, false, 0);
        if (stagger < 0 || stagger > 5) throw new SceneException(path + ".stagger", "0 to 5 seconds");
        String order = str(op, "order", path, false, "yzx");
        // the cells it names, in the order they go
        List<Integer> picked = new ArrayList<>();
        if (op.has("cells")) {
            for (double[] p : new Scene.Props(op).points("cells")) {
                if (p.length != 3) throw new SceneException(path + ".cells", "a cell is [x, y, z]");
                int x = (int) p[0], y = (int) p[1], z = (int) p[2];
                if (x < 0 || y < 0 || z < 0 || x >= g.sx || y >= g.sy || z >= g.sz) throw new SceneException(path + ".cells", "[" + x + ", " + y + ", " + z + "] is outside group '" + gid + "' (" + g.sx + " x " + g.sy + " x " + g.sz + ")");
                picked.add(g.index(x, y, z));
            }
        } else {
            for (int c = 0; c < g.volume(); c++) picked.add(c);
        }
        int lo = 0, hi = g.sy - 1;
        if (op.has("layer")) lo = hi = (int) num(op, "layer", path, true, 0);
        if (op.has("layers")) {
            double[] l = vec(op, "layers", 2, path, true);
            lo = (int) l[0];
            hi = (int) l[1];
        }
        double[] box = op.has("box") ? vec(op, "box", 6, path, true) : null;
        String key = op.has("key") ? str(op, "key", path, true, "") : null;
        int keyCell = -1;
        if (key != null) {
            Integer pi = palette.get(key);
            if (pi == null) throw new SceneException(path + ".key", "unknown block key '" + key + "'");
            keyCell = pi + 1;
        }
        boolean all = op.has("all") && op.get("all").getAsBoolean();
        if (!op.has("cells") && !op.has("layer") && !op.has("layers") && box == null && key == null && !all) throw new SceneException(path, "name what it applies to: cells, layer, layers, box, key or all");
        List<Integer> chosen = new ArrayList<>();
        for (int c : picked) {
            int x = c % g.sx, z = (c / g.sx) % g.sz, y = c / (g.sx * g.sz);
            if (y < lo || y > hi) continue;
            if (box != null && (x < box[0] || y < box[1] || z < box[2] || x > box[3] || y > box[4] || z > box[5])) continue;
            if (keyCell >= 0 && g.cell[c] != keyCell) continue;
            if (g.cell[c] == 0) continue;
            chosen.add(c);
        }
        if (chosen.isEmpty()) throw new SceneException(path, "selects no blocks of group '" + gid + "'");
        switch (order) {
            case "yzx" -> chosen.sort(Comparator.naturalOrder());
            case "given" -> {
            }
            case "near" -> {
                double[] from = vec(op, "from", 3, path, true);
                chosen.sort(Comparator.comparingDouble(c -> {
                    int x = c % g.sx, z = (c / g.sx) % g.sz, y = c / (g.sx * g.sz);
                    double dx = x + 0.5 - from[0], dy = y + 0.5 - from[1], dz = z + 0.5 - from[2];
                    return dx * dx + dy * dy + dz * dz;
                }));
            }
            default -> throw new SceneException(path + ".order", "'yzx', 'given' or 'near'");
        }
        List<List<Scene.Ev>> per = evLists.get(gi);
        for (int k = 0; k < chosen.size(); k++) {
            int c = chosen.get(k);
            double tt = t + k * stagger;
            if (tt > duration + 1e-9) throw new SceneException(path, "with its stagger the last block comes at " + String.format(java.util.Locale.ROOT, "%.2f", tt) + " s, after the lesson ends (" + duration + " s)");
            List<Scene.Ev> l = per.get(c);
            if (l == null) per.set(c, l = new ArrayList<>());
            l.add(new Scene.Ev(tt, reveal, anim, dur));
        }
    }

    // ---- tracks

    /** Keyframes -> one track per field. Fields a key does not name are not keyed there; stepped fields hold until their key. */
    static Scene.Keyed keyed(JsonArray list, String path, Set<String> allowed, double duration) {
        Map<String, List<double[]>> values = new LinkedHashMap<>();
        Map<String, List<Double>> times = new LinkedHashMap<>();
        Map<String, List<Ease>> eases = new LinkedHashMap<>();
        double last = -1;
        for (int i = 0; i < list.size(); i++) {
            String kp = path + "[" + i + "]";
            JsonObject k = obj(list.get(i), kp);
            strict(k, kp, allowed);
            double t = num(k, "t", kp, false, 0);
            if (t < 0 || t > duration + 1e-9) throw new SceneException(kp + ".t", "outside the lesson (0 to " + duration + ")");
            if (t < last - 1e-9) throw new SceneException(kp + ".t", "keys must be in time order");
            last = t;
            String easeName = str(k, "ease", kp, false, "");
            Ease ease = easeName.isEmpty() ? null : Ease.parse(easeName);
            if (!easeName.isEmpty() && ease == null) throw new SceneException(kp + ".ease", "'linear', 'in', 'out', 'inOut', 'spring' or 'step'");
            for (Map.Entry<String, JsonElement> en : k.entrySet()) {
                String f = en.getKey();
                if (f.equals("t") || f.equals("ease")) continue;
                int n = ARITY.getOrDefault(f, 1);
                double[] v = number(en.getValue(), n, kp + "." + f);
                List<double[]> vl = values.computeIfAbsent(f, x -> new ArrayList<>());
                if (!vl.isEmpty() && vl.get(0).length != v.length) throw new SceneException(kp + "." + f, "has " + v.length + " numbers, earlier keys have " + vl.get(0).length);
                vl.add(v);
                times.computeIfAbsent(f, x -> new ArrayList<>()).add(t);
                eases.computeIfAbsent(f, x -> new ArrayList<>()).add(STEPPED.contains(f) ? Ease.STEP : ease != null ? ease : Ease.IN_OUT);
            }
        }
        Map<String, Track> out = new LinkedHashMap<>();
        for (String f : values.keySet()) {
            List<Double> tl = times.get(f);
            double[] ts = new double[tl.size()];
            for (int i = 0; i < ts.length; i++) ts[i] = tl.get(i);
            out.put(f, new Track(ts, values.get(f).toArray(new double[0][]), eases.get(f).toArray(new Ease[0])));
        }
        return new Scene.Keyed(out);
    }

    private static List<Scene.CursorKey> readCursor(JsonArray list, double duration) {
        List<Scene.CursorKey> out = new ArrayList<>();
        boolean down = false;
        String shape = "arrow";
        double alpha = 1, last = -1;
        for (int i = 0; i < list.size(); i++) {
            String kp = "tracks.cursor[" + i + "]";
            JsonObject k = obj(list.get(i), kp);
            strict(k, kp, CURSOR_FIELDS);
            double t = num(k, "t", kp, false, 0);
            if (t < 0 || t > duration + 1e-9) throw new SceneException(kp + ".t", "outside the lesson");
            if (t < last - 1e-9) throw new SceneException(kp + ".t", "keys must be in time order");
            last = t;
            if (k.has("down")) down = k.get("down").getAsJsonPrimitive().isBoolean() ? k.get("down").getAsBoolean() : k.get("down").getAsDouble() != 0;
            if (k.has("shape")) shape = str(k, "shape", kp, true, "arrow");
            if (!Set.of("arrow", "hand", "eye").contains(shape)) throw new SceneException(kp + ".shape", "'arrow', 'hand' or 'eye'");
            if (k.has("alpha")) alpha = num(k, "alpha", kp, true, 1);
            int given = (k.has("at") ? 1 : 0) + (k.has("screen") ? 1 : 0) + (k.has("panel") ? 1 : 0);
            if (given > 1) throw new SceneException(kp, "point at one thing: 'at', 'screen' or 'panel'");
            Scene.Pt pt;
            if (k.has("at")) {
                pt = new Scene.Pt(Scene.Pt.WORLD, vec(k, "at", 3, kp, true), "", 0);
            } else if (k.has("screen")) {
                pt = new Scene.Pt(Scene.Pt.SCREEN, vec(k, "screen", 2, kp, true), "", 0);
            } else if (k.has("panel")) {
                JsonArray pa = arr(k.get("panel"), kp + ".panel");
                if (pa.size() != 2 || !pa.get(0).isJsonPrimitive() || !pa.get(1).isJsonPrimitive()) throw new SceneException(kp + ".panel", "expected [type, row]");
                if (!PANEL_TYPES.containsKey(pa.get(0).getAsString())) throw new SceneException(kp + ".panel", "unknown panel type '" + pa.get(0).getAsString() + "'");
                pt = new Scene.Pt(Scene.Pt.PANEL, new double[0], pa.get(0).getAsString(), pa.get(1).getAsInt());
            } else if (out.isEmpty()) {
                throw new SceneException(kp, "the first key needs 'at' (a world point), 'screen' (0 to 1) or 'panel' ([type, row])");
            } else {
                // a key that only changes the button or the shape stays where the one before it was
                Scene.CursorKey prev = out.get(out.size() - 1);
                out.add(new Scene.CursorKey(t, prev.pt(), down, shape, alpha, Ease.LINEAR));
                continue;
            }
            String easeName = str(k, "ease", kp, false, "inOut");
            Ease ease = Ease.parse(easeName);
            if (ease == null) throw new SceneException(kp + ".ease", "unknown ease '" + easeName + "'");
            out.add(new Scene.CursorKey(t, pt, down, shape, alpha, ease));
        }
        return out;
    }

    // ---- overlays and panels

    private static List<Scene.Item> readItems(JsonObject o, String field, Map<String, Set<String>> types, double duration, Map<String, Integer> groupAt) {
        List<Scene.Item> out = new ArrayList<>();
        if (!o.has(field)) return out;
        JsonArray a = arr(o.get(field), field);
        for (int i = 0; i < a.size(); i++) {
            String path = field + "[" + i + "]";
            JsonObject it = obj(a.get(i), path);
            String type = str(it, "type", path, true, "");
            Set<String> own = types.get(type);
            if (own == null) throw new SceneException(path + ".type", "unknown type '" + type + "' (" + String.join(", ", new java.util.TreeSet<>(types.keySet())) + ")");
            Set<String> allowed = new java.util.HashSet<>(ITEM);
            allowed.addAll(own);
            strict(it, path, allowed);
            double t0 = num(it, "t0", path, true, 0), t1 = num(it, "t1", path, true, 0);
            window(path, t0, t1, duration);
            JsonObject props = new JsonObject();
            for (Map.Entry<String, JsonElement> en : it.entrySet()) if (!ITEM.contains(en.getKey())) props.add(en.getKey(), en.getValue());
            Scene.Keyed keys = Scene.Keyed.EMPTY;
            if (it.has("keys")) {
                Set<String> keyFields = new java.util.HashSet<>(own);
                keyFields.add("t");
                keyFields.add("ease");
                keys = keyed(arr(it.get("keys"), path + ".keys"), path + ".keys", keyFields, duration);
            }
            List<Scene.State> states = new ArrayList<>();
            if (it.has("states")) {
                JsonArray sa = arr(it.get("states"), path + ".states");
                double prev = -1;
                for (int s = 0; s < sa.size(); s++) {
                    String sp = path + ".states[" + s + "]";
                    JsonObject so = obj(sa.get(s), sp);
                    Set<String> sAllowed = new java.util.HashSet<>(own);
                    sAllowed.add("t");
                    strict(so, sp, sAllowed);
                    double t = num(so, "t", sp, true, 0);
                    if (t < prev) throw new SceneException(sp + ".t", "states must be in time order");
                    prev = t;
                    JsonObject copy = so.deepCopy();
                    copy.remove("t");
                    states.add(new Scene.State(t, new Scene.Props(copy)));
                }
            }
            if (type.equals("tint") && !groupAt.containsKey(str(it, "group", path, true, ""))) throw new SceneException(path + ".group", "there is no group called '" + it.get("group").getAsString() + "'");
            String fadeS = it.has("fade") ? "fade" : "";
            double fade = fadeS.isEmpty() ? 0.25 : num(it, "fade", path, true, 0.25);
            out.add(new Scene.Item(type, str(it, "id", path, false, ""), t0, t1, fade, new Scene.Props(props), keys, List.copyOf(states)));
        }
        return out;
    }

    // ---- JSON helpers

    private static void window(String path, double t0, double t1, double duration) {
        if (t0 < 0 || t1 > duration + 1e-9 || t1 <= t0) throw new SceneException(path, "times t0 " + t0 + " and t1 " + t1 + " must be inside the lesson (0 to " + duration + ") with t0 before t1");
    }

    private static JsonObject obj(@Nullable JsonElement e, String path) {
        if (e == null || !e.isJsonObject()) throw new SceneException(path, "expected an object");
        return e.getAsJsonObject();
    }

    private static JsonArray arr(@Nullable JsonElement e, String path) {
        if (e == null || !e.isJsonArray()) throw new SceneException(path, "expected a list");
        return e.getAsJsonArray();
    }

    private static void strict(JsonObject o, String path, Set<String> allowed) {
        for (String k : o.keySet()) {
            if (!allowed.contains(k)) throw new SceneException(path.isEmpty() ? k : path + "." + k, "unknown field (known: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ")");
        }
    }

    private static String str(JsonObject o, String k, String path, boolean required, String def) {
        JsonElement e = o.get(k);
        if (e == null) {
            if (required) throw new SceneException(path.isEmpty() ? k : path + "." + k, "missing");
            return def;
        }
        if (!e.isJsonPrimitive()) throw new SceneException(path.isEmpty() ? k : path + "." + k, "expected text");
        return e.getAsString();
    }

    private static double num(JsonObject o, String k, String path, boolean required, double def) {
        JsonElement e = o.get(k);
        if (e == null) {
            if (required) throw new SceneException(path.isEmpty() ? k : path + "." + k, "missing");
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new SceneException(path.isEmpty() ? k : path + "." + k, "expected a number");
        return e.getAsDouble();
    }

    private static double[] vec(JsonObject o, String k, int n, String path, boolean required) {
        JsonElement e = o.get(k);
        String p = path.isEmpty() ? k : path + "." + k;
        if (e == null) {
            if (required) throw new SceneException(p, "missing");
            return new double[n];
        }
        return number(e, n, p);
    }

    private static double[] number(JsonElement e, int n, String path) {
        if (n == 1 && e.isJsonPrimitive()) {
            JsonElement p = e;
            if (p.getAsJsonPrimitive().isBoolean()) return new double[]{p.getAsBoolean() ? 1 : 0};
            if (!p.getAsJsonPrimitive().isNumber()) throw new SceneException(path, "expected a number");
            return new double[]{p.getAsDouble()};
        }
        if (!e.isJsonArray() || e.getAsJsonArray().size() != n) throw new SceneException(path, "expected " + n + " numbers");
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            JsonElement x = e.getAsJsonArray().get(i);
            if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber()) throw new SceneException(path + "[" + i + "]", "expected a number");
            out[i] = x.getAsDouble();
        }
        return out;
    }
}
