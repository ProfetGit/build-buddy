package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.ui.BlockLook;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StageRasterTest {
    private static final int W = 320, H = 240;
    private static final Map<String, Integer> COLORS = Map.of("minecraft:red", 0xFFD02020, "minecraft:blue", 0xFF2030D0, "minecraft:green", 0xFF20C040, "minecraft:white", 0xFFFFFFFF);
    private static final StageRaster.Looks LOOKS = spec -> BlockLook.Look.solid(COLORS.getOrDefault(spec, 0xFF808080));

    private static Scene scene(String body) {
        return SceneReader.read("{\"format\":1,\"id\":\"t\",\"title\":\"T\",\"summary\":\"S\",\"duration\":10,\"stage\":{\"size\":[8,6,8],\"focus\":[4,1,4]},"
            + "\"palette\":{\"r\":\"minecraft:red\",\"b\":\"minecraft:blue\",\"g\":\"minecraft:green\",\"w\":\"minecraft:white\"}," + body + "}");
    }

    private static int[] draw(Scene s, double t, int w, int h) {
        return new StageRaster().render(s, Evaluator.at(s, t), LOOKS, w, h, 1.0);
    }

    private static int r(int c) {
        return (c >> 16) & 255;
    }

    private static int g(int c) {
        return (c >> 8) & 255;
    }

    private static int b(int c) {
        return c & 255;
    }

    private static int at(int[] px, int w, double x, double y) {
        return px[(int) y * w + (int) x];
    }

    @Test
    void anEmptyStageIsTheBlueprintPaper() {
        Scene s = scene("\"groups\":[]");
        int[] px = draw(s, 0, W, H);
        int top = at(px, W, 3, 1), bottom = at(px, W, 3, H - 2);
        assertEquals(StageRaster.BACKGROUND_TOP >> 16 & 255, r(top), 6);
        assertEquals(StageRaster.BACKGROUND_BOTTOM >> 8 & 255, g(bottom), 6);
        assertNotEquals(top, bottom, "the paper is a gradient");
    }

    @Test
    void theFocusPointIsTheMiddleOfThePicture() {
        Scene s = scene("\"groups\":[]");
        Snapshot snap = Evaluator.at(s, 0);
        StageRaster.Camera cam = StageRaster.camera(s, snap, W, H);
        double[] p = cam.project(4, 1, 4);
        assertEquals(W / 2.0, p[0], 1e-9);
        assertEquals(H / 2.0, p[1], 1e-9);
    }

    @Test
    void aBlockShowsItsTopInItsOwnColourShaded() {
        Scene s = scene("\"groups\":[{\"id\":\"a\",\"pos\":[3,1,3],\"layers\":[[\"r\"]]}]");
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 0), W, H);
        // the middle of the top face is the point (3.5, 2, 3.5)
        double[] p = cam.project(3.5, 2.0, 3.5);
        int c = at(draw(s, 0, W, H), W, p[0], p[1]);
        // the top is lit 0.92 and flat looks vary a few per cent from block to block
        assertTrue(r(c) > 0xD0 * 0.85 && r(c) < 0xD0 * 1.0, "red channel " + r(c));
        assertTrue(g(c) < 60 && b(c) < 60, "still red: " + Integer.toHexString(c));
    }

    @Test
    void theSidesAreDarkerThanTheTop() {
        Scene s = scene("\"groups\":[{\"id\":\"a\",\"pos\":[3,1,3],\"layers\":[[\"w\"]]}],\"tracks\":{\"camera\":[{\"t\":0,\"yaw\":0,\"pitch\":0.6}]}");
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 0), W, H);
        int[] px = draw(s, 0, W, H);
        double[] top = cam.project(3.5, 2.0, 3.5), front = cam.project(3.5, 1.5, 4.0);
        int ct = at(px, W, top[0], top[1]), cf = at(px, W, front[0], front[1]);
        assertTrue(r(ct) > r(cf), "top " + r(ct) + " front " + r(cf));
    }

    @Test
    void aNearBlockHidesAFarOne() {
        // seen from the side at eye level: the camera is at +z, so the block at z = 5 stands in front of the one at z = 3
        Scene s = scene("\"groups\":[{\"id\":\"far\",\"pos\":[3,1,3],\"layers\":[[\"b\"]]},{\"id\":\"near\",\"pos\":[3,1,5],\"layers\":[[\"r\"]]}],"
            + "\"tracks\":{\"camera\":[{\"t\":0,\"yaw\":0,\"pitch\":0}]}");
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 0), W, H);
        double[] p = cam.project(3.5, 1.5, 6.0);
        int c = at(draw(s, 0, W, H), W, p[0], p[1]);
        assertTrue(r(c) > 100 && b(c) < 60, "the red one in front: " + Integer.toHexString(c));
        // and with them swapped in depth, the blue one shows
        Scene swapped = scene("\"groups\":[{\"id\":\"far\",\"pos\":[3,1,5],\"layers\":[[\"b\"]]},{\"id\":\"near\",\"pos\":[3,1,3],\"layers\":[[\"r\"]]}],"
            + "\"tracks\":{\"camera\":[{\"t\":0,\"yaw\":0,\"pitch\":0}]}");
        double[] q = StageRaster.camera(swapped, Evaluator.at(swapped, 0), W, H).project(3.5, 1.5, 6.0);
        int d = at(draw(swapped, 0, W, H), W, q[0], q[1]);
        assertTrue(b(d) > 100 && r(d) < 60, "the blue one in front: " + Integer.toHexString(d));
    }

    @Test
    void aGhostIsSeeThroughAndTintedCyan() {
        Scene s = scene("\"groups\":[{\"id\":\"a\",\"mode\":\"ghost\",\"pos\":[3,1,3],\"layers\":[[\"w\"]]}]");
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 0), W, H);
        double[] p = cam.project(3.5, 2.0, 3.5);
        int[] px = draw(s, 0, W, H);
        int c = at(px, W, p[0], p[1]);
        // 60 % of a bluish white over the dark paper: lighter than the paper, not as bright as white, blue above red
        int paper = StageRaster.mix(StageRaster.BACKGROUND_TOP, StageRaster.BACKGROUND_BOTTOM, p[1] / H);
        assertTrue(r(c) > r(paper) + 50 && r(c) < 235, "red " + r(c) + " over paper " + r(paper));
        assertTrue(b(c) > r(c), "tinted toward blue: " + Integer.toHexString(c));
    }

    @Test
    void aGhostDoesNotShowThroughASolidBlockInFrontOfIt() {
        Scene s = scene("\"groups\":[{\"id\":\"ghost\",\"mode\":\"ghost\",\"pos\":[3,1,3],\"layers\":[[\"w\"]]},{\"id\":\"near\",\"pos\":[3,1,5],\"layers\":[[\"r\"]]}],"
            + "\"tracks\":{\"camera\":[{\"t\":0,\"yaw\":0,\"pitch\":0}]}");
        double[] p = StageRaster.camera(s, Evaluator.at(s, 0), W, H).project(3.5, 1.5, 6.0);
        int c = at(draw(s, 0, W, H), W, p[0], p[1]);
        assertTrue(r(c) > 100 && b(c) < 60, "the solid block in front stays red: " + Integer.toHexString(c));
    }

    @Test
    void aWrongGhostCellIsRed() {
        Scene s = scene("\"groups\":[{\"id\":\"ghost\",\"mode\":\"ghost\",\"match\":\"real\",\"pos\":[3,1,3],\"layers\":[[\"w\"]]},{\"id\":\"real\",\"start\":\"empty\",\"pos\":[3,1,3],\"layers\":[[\"b\"]]}],"
            + "\"ops\":[{\"t\":1,\"group\":\"real\",\"do\":\"reveal\",\"all\":true,\"anim\":\"none\"}]");
        // the real block is blue where white belongs: the ghost is red over it. The camera looks at the top.
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 2), W, H);
        double[] p = cam.project(3.5, 2.0, 3.5);
        int c = at(draw(s, 2, W, H), W, p[0], p[1]);
        assertTrue(r(c) > g(c) + 30, "reddish over the blue block: " + Integer.toHexString(c));
        // before it is built the ghost is the usual bluish white
        int before = at(draw(s, 0.5, W, H), W, p[0], p[1]);
        assertTrue(g(before) >= r(before) - 10 && b(before) >= r(before), "bluish before: " + Integer.toHexString(before));
    }

    @Test
    void aBuiltGhostCellLetsGoSoTheRealBlockShowsClean() {
        Scene s = scene("\"groups\":[{\"id\":\"ghost\",\"mode\":\"ghost\",\"match\":\"real\",\"pos\":[3,1,3],\"layers\":[[\"b\"]]},{\"id\":\"real\",\"start\":\"empty\",\"pos\":[3,1,3],\"layers\":[[\"b\"]]}],"
            + "\"ops\":[{\"t\":1,\"group\":\"real\",\"do\":\"reveal\",\"all\":true,\"anim\":\"none\"}]");
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 2), W, H);
        double[] p = cam.project(3.5, 2.0, 3.5);
        int c = at(draw(s, 2, W, H), W, p[0], p[1]);
        assertTrue(b(c) > 150 && r(c) < 80, "pure blue, no ghost over it: " + Integer.toHexString(c));
    }

    @Test
    void aDroppingBlockStartsHigherThanItsPlace() {
        Scene s = scene("\"groups\":[{\"id\":\"a\",\"start\":\"empty\",\"pos\":[3,1,3],\"layers\":[[\"r\"]]}],\"ops\":[{\"t\":1,\"group\":\"a\",\"do\":\"reveal\",\"all\":true,\"anim\":\"drop\",\"dur\":1}],"
            + "\"tracks\":{\"camera\":[{\"t\":0,\"yaw\":0,\"pitch\":0}]}");
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 0), W, H);
        double[] rest = cam.project(3.5, 1.5, 4.0);
        // just after the start it is up in the air: nothing yet where it will stand (the paper), and it lands there at the end
        int early = at(draw(s, 1.25, W, H), W, rest[0], rest[1]);
        int late = at(draw(s, 2.5, W, H), W, rest[0], rest[1]);
        assertTrue(r(late) > 100 && g(late) < 50, "landed: " + Integer.toHexString(late));
        assertTrue(r(early) < 100, "still in the air: " + Integer.toHexString(early));
    }

    @Test
    void aTintOverlayTurnsTheBlocksGold() {
        Scene s = scene("\"groups\":[{\"id\":\"a\",\"pos\":[3,1,3],\"layers\":[[\"b\"]]}],\"overlays\":[{\"type\":\"tint\",\"t0\":1,\"t1\":5,\"group\":\"a\",\"key\":\"b\",\"color\":\"#FFC857\",\"alpha\":0.6,\"fade\":0}]");
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 0), W, H);
        double[] p = cam.project(3.5, 2.0, 3.5);
        int plain = at(draw(s, 0.5, W, H), W, p[0], p[1]), gold = at(draw(s, 3, W, H), W, p[0], p[1]);
        assertTrue(b(plain) > r(plain), "blue before");
        assertTrue(r(gold) > b(gold) - 20 && g(gold) > g(plain) + 40, "gold after: " + Integer.toHexString(gold));
    }

    @Test
    void aMarkerAndAnArrowDrawSomething() {
        Scene s = scene("\"groups\":[],\"overlays\":[{\"type\":\"marker\",\"t0\":0,\"t1\":9,\"at\":[3,1,3],\"fade\":0},{\"type\":\"arrow\",\"t0\":0,\"t1\":9,\"at\":[5,1,5],\"dir\":\"+x\",\"fade\":0}]");
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 1), W, H);
        int[] px = draw(s, 1, W, H);
        double[] m = cam.project(3.5, 1.5, 3.5), a = cam.project(5.8, 1, 5);
        int paperM = StageRaster.mix(StageRaster.BACKGROUND_TOP, StageRaster.BACKGROUND_BOTTOM, m[1] / H), paperA = StageRaster.mix(StageRaster.BACKGROUND_TOP, StageRaster.BACKGROUND_BOTTOM, a[1] / H);
        assertNotEquals(paperM, at(px, W, m[0], m[1]), "the marker's glow");
        assertNotEquals(paperA, at(px, W, a[0], a[1]), "the arrow's shaft");
    }

    @Test
    void aLabelOverlayGivesTextToDrawOverThePicture() {
        Scene s = scene("\"groups\":[],\"overlays\":[{\"type\":\"label\",\"t0\":0,\"t1\":9,\"at\":[4,3,4],\"text\":\"+3 east\",\"fade\":0},{\"type\":\"dim\",\"t0\":0,\"t1\":9,\"a\":[1,1,1],\"b\":[4,1,1],\"text\":\"3\",\"fade\":0}]");
        StageRaster r = new StageRaster();
        r.render(s, Evaluator.at(s, 1), LOOKS, W, H, 1.0);
        assertEquals(2, r.labels().size());
        assertEquals("+3 east", r.labels().get(0).text());
        assertEquals("3", r.labels().get(1).text());
    }

    @Test
    void theSameSnapshotDrawsTheSamePicture() {
        Scene s = scene("\"groups\":[{\"id\":\"a\",\"pos\":[2,1,2],\"layers\":[[\"rg\",\"bw\"],[\"w.\",\"..\"]]},{\"id\":\"ghost\",\"mode\":\"ghost\",\"pos\":[4,1,4],\"layers\":[[\"rg\",\"bw\"]]}]");
        int[] one = draw(s, 2, W, H), two = draw(s, 2, W, H);
        assertTrue(java.util.Arrays.equals(one, two), "drawing is deterministic (bands run in parallel but never share a row)");
    }

    @Test
    void aRotatingGroupKeepsDrawingWhenTurnedPartWay() {
        Scene s = scene("\"groups\":[{\"id\":\"a\",\"pos\":[2,1,2],\"layers\":[[\"rrr\",\"rrr\",\"rrr\"]]}],\"tracks\":{\"groups\":{\"a\":[{\"t\":0,\"turn\":0},{\"t\":4,\"turn\":1}]}}");
        int[] half = draw(s, 2, W, H);
        StageRaster.Camera cam = StageRaster.camera(s, Evaluator.at(s, 2), W, H);
        // the middle of the 3x3 top at height 2 is still the middle whatever the turn
        double[] p = cam.project(3.5, 2.0, 3.5);
        int c = at(half, W, p[0], p[1]);
        assertTrue(r(c) > 150 && g(c) < 60, "red: " + Integer.toHexString(c));
    }
}
