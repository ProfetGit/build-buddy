package io.github.profetgit.buildbuddy.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.profetgit.buildbuddy.TestBootstrap;
import io.github.profetgit.buildbuddy.blueprint.PaletteEntry;
import io.github.profetgit.buildbuddy.blueprint.SchematicReader;
import io.github.profetgit.buildbuddy.ui.ChipIcons;
import io.github.profetgit.buildbuddy.ui.LessonActions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The lint over every lesson the mod ships (PRD 7.12a): each must load, use real blocks and real chip pictures, and loop without a jump. */
class ShippedLessonsTest {
    static final Path DIR = Path.of("src/main/resources/assets/buildbuddy/ponders");
    static final List<Scene> LESSONS = new ArrayList<>();
    static final List<String> IDS = new ArrayList<>();

    @BeforeAll
    static void load() throws IOException {
        TestBootstrap.init();
        JsonObject index = JsonParser.parseString(Files.readString(DIR.resolve("index.json"))).getAsJsonObject();
        index.getAsJsonArray("lessons").forEach(e -> IDS.add(e.getAsString()));
        for (String id : IDS) LESSONS.add(SceneReader.read(Files.readString(DIR.resolve(id + ".json"))));
    }

    @Test
    void theIndexListsEveryFileAndEveryEntryHasOne() throws IOException {
        Set<String> files = new HashSet<>();
        try (var s = Files.list(DIR)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".json") && !n.equals("index.json")) files.add(n.replace(".json", ""));
            });
        }
        assertEquals(files, new HashSet<>(IDS), "index.json and the lesson files agree");
        assertEquals(IDS.size(), new HashSet<>(IDS).size(), "no id twice");
        for (int i = 0; i < IDS.size(); i++) assertEquals(IDS.get(i), LESSONS.get(i).id);
        assertTrue(IDS.containsAll(List.of("place", "edit", "layers", "build", "auto", "materials", "pick", "box", "paste")), "the first set: " + IDS);
    }

    @Test
    void everyBlockOfEveryPaletteIsARealBlockWithRealProperties() {
        for (Scene s : LESSONS) {
            for (String spec : s.palette) {
                assertTrue(io.github.profetgit.buildbuddy.ponder.PonderLooks.known(spec), s.id + ": unknown block " + spec);
                CompoundTag given = SchematicReader.stateTag(spec);
                CompoundTag written = NbtUtils.writeBlockState(PaletteEntry.read(given).state());
                CompoundTag props = given.getCompoundOrEmpty("Properties");
                for (String k : props.keySet()) {
                    assertEquals(props.getStringOr(k, ""), written.getCompoundOrEmpty("properties").getStringOr(k, "?"), s.id + ": " + spec + " property " + k + " does not mean what it says");
                }
            }
        }
    }

    @Test
    void everyChipKeyIsAPictureNotRawText() {
        for (Scene s : LESSONS) {
            for (Scene.Chips c : s.chips) {
                for (List<String> row : c.rows()) {
                    for (ChipIcons.Part p : ChipIcons.parse(row.get(0))) {
                        // "Release" is the one key the game itself writes as words
                        assertFalse(p.kind() == ChipIcons.Kind.TEXT && !p.text().equals("Release"), s.id + ": the chip key '" + row.get(0) + "' has a part that would show as plain text: " + p);
                        if (p.kind() == ChipIcons.Kind.KEYCAP) assertTrue(p.text().length() <= 8, s.id + ": '" + p.text() + "' is too long for a key cap");
                    }
                    assertTrue(row.get(1).length() <= 40, s.id + ": the chip text '" + row.get(1) + "' is long");
                }
            }
        }
    }

    @Test
    void everyActionIsOneTheGameKnows() {
        for (Scene s : LESSONS) assertTrue(LessonActions.known(s.action), s.id + ": unknown action " + s.action);
    }

    @Test
    void everyLessonHasWordsAndSteps() {
        for (Scene s : LESSONS) {
            assertFalse(s.title.isBlank());
            assertTrue(s.summary.length() >= 20 && s.summary.length() <= 160, s.id + ": the summary is " + s.summary.length() + " characters");
            assertTrue(s.tags.size() >= 3, s.id + ": tags are what the Help search finds, give it at least three");
            assertTrue(s.steps() >= 3, s.id + ": a lesson has at least three steps");
            assertTrue(s.duration >= 8 && s.duration <= 30, s.id + ": 8 to 30 seconds, not " + s.duration);
            // the captions cover the lesson: no gap in which the screen falls back to the summary
            double at = 0;
            for (Scene.Caption c : s.captions) {
                assertEquals(at, c.t0(), 1e-6, s.id + ": a caption starts where the one before it ends");
                at = c.t1();
                assertTrue(c.text().length() <= 140, s.id + ": a caption of " + c.text().length() + " characters will not fit two lines: " + c.text());
                assertTrue(c.title().length() <= 32, s.id + ": the step title '" + c.title() + "' is long");
            }
            assertEquals(s.duration, at, 1e-6, s.id + ": the last caption runs to the end");
        }
    }

    @Test
    void theLastMomentLooksLikeTheFirstSoItLoopsWithoutAJump() {
        for (Scene s : LESSONS) {
            Snapshot a = Evaluator.at(s, 0), b = Evaluator.at(s, s.duration);
            assertEquals(a.groups.size(), b.groups.size());
            for (int g = 0; g < a.groups.size(); g++) {
                assertEquals(java.util.Arrays.toString(a.groups.get(g).state), java.util.Arrays.toString(b.groups.get(g).state), s.id + ": group " + a.groups.get(g).group.id + " holds different blocks at the end");
                assertEquals(a.groups.get(g).count() == 0, b.groups.get(g).count() == 0);
            }
            assertEquals(a.yaw, b.yaw, 1e-6, s.id + ": the camera comes back (yaw)");
            assertEquals(a.pitch, b.pitch, 1e-6, s.id + ": the camera comes back (pitch)");
            assertEquals(a.zoom, b.zoom, 1e-6, s.id + ": the camera comes back (zoom)");
            for (int i = 0; i < 3; i++) assertEquals(a.focus[i], b.focus[i], 1e-6, s.id + ": the camera comes back (focus)");
            assertTrue(a.overlays.isEmpty() && b.overlays.isEmpty(), s.id + ": nothing is left on the stage at either end: " + a.overlays.size() + " / " + b.overlays.size());
            assertTrue(a.panels.isEmpty() && b.panels.isEmpty(), s.id + ": no panel is showing at either end");
            assertTrue(a.cursor == null || a.cursor.alpha() < 0.02, s.id + ": the cursor is hidden at the start");
            assertTrue(b.cursor == null || b.cursor.alpha() < 0.02, s.id + ": the cursor is hidden at the end");
        }
    }

    @Test
    void everyLessonDrawsAtEveryHalfSecondWithoutAnError() {
        StageRaster raster = new StageRaster();
        for (Scene s : LESSONS) {
            for (double t = 0; t <= s.duration; t += 0.5) {
                int[] px = raster.render(s, Evaluator.at(s, t), PonderLooks.INSTANCE, 192, 108, 1.0);
                assertNotNull(px);
            }
        }
    }

    @Test
    void theGroundSlabIsNeverCutByTheFrameAtEitherLayout() {
        // the picture is about 1.8 : 1 on a big window and about 1.45 : 1 in the compact layout; the slab all lessons stand on must be whole in both
        List<String> cut = new ArrayList<>();
        for (Scene s : LESSONS) {
            Scene.Group ground = s.groups.stream().filter(g -> g.id.equals("ground")).findFirst().orElse(null);
            if (ground == null) continue;
            double worst = 0;
            for (double aspect : new double[]{1.45, 1.8}) {
                int h = 360, w = (int) Math.round(h * aspect);
                for (double t = 0; t <= s.duration; t += 0.5) {
                    Snapshot snap = Evaluator.at(s, t);
                    StageRaster.Camera cam = StageRaster.camera(s, snap, w, h);
                    for (int cx = 0; cx < 2; cx++) {
                        for (int cy = 0; cy < 2; cy++) {
                            for (int cz = 0; cz < 2; cz++) {
                                double[] p = cam.project(ground.pos[0] + cx * ground.sx, ground.pos[1] + cy * ground.sy, ground.pos[2] + cz * ground.sz);
                                double over = Math.max(Math.max(w * s.frame[0] + 1 - p[0], p[0] - (w * s.frame[2] - 1)), Math.max(h * s.frame[1] + 1 - p[1], p[1] - (h * s.frame[3] - 1)));
                                if (over > worst) worst = over;
                            }
                        }
                    }
                }
            }
            if (worst > 0) cut.add(s.id + " (" + Math.round(worst) + " px of 360 out)");
        }
        assertTrue(cut.isEmpty(), "the frame cuts the ground slab in: " + cut);
    }

    @Test
    void theBuildingLessonsBuildEverythingTheirGhostShows() {
        // by the end of the building lessons every block of the ghost has been matched at some moment: nothing is left red
        for (String id : List.of("build", "auto", "paste")) {
            Scene s = LESSONS.get(IDS.indexOf(id));
            boolean anyHidden = false;
            for (double t = 0; t <= s.duration; t += 0.25) {
                Snapshot snap = Evaluator.at(s, t);
                Snapshot.GroupView ghost = snap.groups.stream().filter(g -> g.group.id.equals("ghost")).findFirst().orElseThrow();
                int present = ghost.count();
                if (present < ghost.group.volume() - countAir(ghost.group)) anyHidden = true;
            }
            assertTrue(anyHidden, id + ": the ghost lets go of blocks as they are built");
        }
    }

    private static int countAir(Scene.Group g) {
        int n = 0;
        for (int c : g.cell) if (c == 0) n++;
        return n;
    }
}
