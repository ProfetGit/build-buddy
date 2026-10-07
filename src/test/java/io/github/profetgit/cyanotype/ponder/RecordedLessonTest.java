package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The round trip of a take: the raw log the recorder wrote in the real client (dev/ponder/rec/place.rec.json) against the lesson the
 * generator made from it (assets/.../ponders/place.json). The generator's maths (dev/ponder/recording.py) is checked here against an
 * independent copy written from the game's own definitions: wherever the log says the placement stood, turned and mirrored, the
 * lesson's group, evaluated at that moment, is at the same place in the world.
 */
class RecordedLessonTest {
    private static final Path REC = Path.of("dev/ponder/rec/place.rec.json"), LESSON = Path.of("src/main/resources/assets/cyanotype/ponders/place.json");

    /** Where a cell of the unturned blueprint goes in the stage under the game's own orientation: mirror first, then the rotation. */
    private static double[] gameWorld(double[] frame, int sx, int sz, int cx, int cz) {
        int rot = (int) frame[4], mirror = (int) frame[5];
        int x = cx, z = cz;
        if (mirror == 1) x = sx - 1 - x;
        else if (mirror == 2) z = sz - 1 - z;
        int tx = x, tz = z;
        switch (rot) {
            case 1 -> {
                tx = sz - 1 - z;
                tz = x;
            }
            case 2 -> {
                tx = sx - 1 - x;
                tz = sz - 1 - z;
            }
            case 3 -> {
                tx = z;
                tz = sx - 1 - x;
            }
            default -> {
            }
        }
        // the turned box starts at the frame's origin
        return new double[]{frame[1] + tx + 0.5, frame[3] + tz + 0.5};
    }

    @Test
    void theLessonStandsWhereTheRecordingSaysThePlacementStood() throws IOException {
        JsonObject rec = JsonParser.parseString(Files.readString(REC)).getAsJsonObject();
        checkStands(rec, SceneReader.read(Files.readString(LESSON)), 1.9 - rec.getAsJsonObject("marks").get("appear").getAsDouble(), 25);
    }

    @Test
    void theEditLessonStandsWhereTheEditTakeSaysItStood() throws IOException {
        JsonObject rec = JsonParser.parseString(Files.readString(Path.of("dev/ponder/rec/edit.rec.json"))).getAsJsonObject();
        checkStands(rec, SceneReader.read(Files.readString(Path.of("src/main/resources/assets/cyanotype/ponders/edit.json"))), 0.0, 40);
    }

    /** The layer window of the lesson at each moment is the one the take recorded. */
    @Test
    void theLayersLessonShowsTheLayersTheTakeShowed() throws IOException {
        JsonObject rec = JsonParser.parseString(Files.readString(Path.of("dev/ponder/rec/layers.rec.json"))).getAsJsonObject();
        Scene lesson = SceneReader.read(Files.readString(Path.of("src/main/resources/assets/cyanotype/ponders/layers.json")));
        JsonArray frames = rec.getAsJsonObject("ghosts").getAsJsonObject("Cottage").getAsJsonArray("frames");
        int checked = 0;
        for (int i = 0; i < frames.size(); i++) {
            JsonArray f = frames.get(i).getAsJsonArray();
            double t = f.get(0).getAsDouble() + 0.01;
            int lo = f.get(9).getAsInt() < 0 ? 0 : f.get(9).getAsInt(), hi = f.get(10).getAsInt() < 0 ? 5 : f.get(10).getAsInt();
            Snapshot.GroupView g = Evaluator.at(lesson, t).groups.stream().filter(v -> v.group.id.equals("ghost")).findFirst().orElseThrow();
            for (int y = 0; y < g.group.sy; y++) {
                int count = 0;
                for (int c = y * g.group.sx * g.group.sz; c < (y + 1) * g.group.sx * g.group.sz; c++) if (g.state[c] != Snapshot.ABSENT) count++;
                assertEquals(y >= lo && y <= hi, count > 0, "at " + t + " s layer " + y + " with the window " + lo + " to " + hi);
            }
            checked++;
        }
        assertTrue(checked >= 10, "frames compared: " + checked);
    }

    private static void checkStands(JsonObject rec, Scene lesson, double offset, int atLeast) {
        JsonObject g = rec.getAsJsonObject("ghosts").getAsJsonObject("Cottage");
        JsonArray size = g.getAsJsonObject("grid").getAsJsonArray("size");
        int sx = size.get(0).getAsInt(), sz = size.get(2).getAsInt();
        JsonArray frames = g.getAsJsonArray("frames");
        int checked = 0;
        double lastChange = 0;
        double[] prev = null;
        for (int i = 0; i < frames.size(); i++) {
            JsonArray fa = frames.get(i).getAsJsonArray();
            double[] f = new double[fa.size()];
            for (int k = 0; k < f.length; k++) f[k] = fa.get(k).getAsDouble();
            if (prev == null || prev[4] != f[4] || prev[5] != f[5]) lastChange = f[0];
            prev = f;
            // a turn glides over 0.4 s after the frame that changes it: look at a frame once the glide is over, and never at the first frames (before it appears)
            double next = i + 1 < frames.size() ? frames.get(i + 1).getAsJsonArray().get(0).getAsDouble() : f[0] + 1;
            double t = Math.max(f[0], lastChange + 0.45);
            if (t >= next - 0.005 || f[0] < 0.6) continue;
            Snapshot snap = Evaluator.at(lesson, t + offset);
            Snapshot.GroupView ghost = snap.groups.stream().filter(v -> v.group.id.equals("ghost")).findFirst().orElseThrow();
            double[] w = new double[3];
            // the corner cells and the middle of the unturned blueprint: where they are in the lesson against where the game put them
            for (int[] cell : new int[][]{{0, 0}, {sx - 1, 0}, {0, sz - 1}, {sx - 1, sz - 1}, {1, 3}}) {
                ghost.toWorld(cell[0] + 0.5, 0.5, cell[1] + 0.5, w);
                double[] want = gameWorld(f, sx, sz, cell[0], cell[1]);
                assertEquals(want[0], w[0], 0.05, "frame at " + f[0] + " s, cell " + cell[0] + "," + cell[1] + " (x), rot " + f[4] + " mirror " + f[5]);
                assertEquals(want[1], w[2], 0.05, "frame at " + f[0] + " s, cell " + cell[0] + "," + cell[1] + " (z), rot " + f[4] + " mirror " + f[5]);
            }
            // and its height
            ghost.toWorld(0.5, 0.0, 0.5, w);
            assertEquals(f[2], w[1], 0.05, "frame at " + f[0] + " s (y)");
            checked++;
        }
        assertTrue(checked >= atLeast, "enough frames were compared: " + checked);
    }

    @Test
    void theMarksThatTheLessonHangsItsWordsOnAreInTheLog() throws IOException {
        JsonObject marks = JsonParser.parseString(Files.readString(REC)).getAsJsonObject().getAsJsonObject("marks");
        for (String m : new String[]{"appear", "turn1", "turn2", "lift_up", "lift_down", "mirror", "lock", "done"}) assertTrue(marks.has(m), "mark " + m);
        double last = -1;
        for (String m : new String[]{"appear", "turn1", "turn2", "lift_up", "lift_down", "mirror", "lock", "done"}) {
            double t = marks.get(m).getAsDouble();
            assertTrue(t > last, m + " comes after the mark before it");
            last = t;
        }
    }

    @Test
    void theCaptionsFollowTheMarks() throws IOException {
        JsonObject marks = JsonParser.parseString(Files.readString(REC)).getAsJsonObject().getAsJsonObject("marks");
        Scene lesson = SceneReader.read(Files.readString(LESSON));
        double offset = 1.9 - marks.get("appear").getAsDouble();
        // each step begins just before the thing it is about happens
        assertTrue(lesson.captions.get(1).t0() < marks.get("turn1").getAsDouble() + offset);
        assertTrue(lesson.captions.get(2).t0() < marks.get("lift_up").getAsDouble() + offset);
        assertTrue(lesson.captions.get(3).t0() < marks.get("lock").getAsDouble() + offset);
        assertTrue(lesson.captions.get(1).t0() > marks.get("appear").getAsDouble() + offset);
    }
}
