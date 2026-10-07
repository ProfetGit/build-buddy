package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SceneReaderTest {
    /** A lesson with every kind of part, small. */
    static final String GOOD = """
        {"format":1,"id":"demo","title":"Demo","summary":"A demo.","tags":["a","b"],"action":"library","duration":10,
         "stage":{"size":[6,4,6],"focus":[3,1,3]},
         "palette":{"p":"minecraft:oak_planks","s":"minecraft:stone"},
         "groups":[
           {"id":"ground","mode":"solid","pos":[0,0,0],"layers":[["pppppp","pppppp"]]},
           {"id":"ghost","mode":"ghost","pos":[1,1,1],"start":"full","match":"real","layers":[["ppp","psp","ppp"],["p.p","...","p.p"]]},
           {"id":"real","mode":"solid","pos":[1,1,1],"start":"empty","layers":[["ppp","psp","ppp"],["p.p","...","p.p"]]}
         ],
         "ops":[{"t":2,"group":"real","do":"reveal","layer":0,"stagger":0.1,"anim":"drop"}],
         "tracks":{
           "camera":[{"t":0,"yaw":0.8,"pitch":0.5,"zoom":1,"focus":[3,1,3]},{"t":5,"yaw":1.2,"ease":"out"}],
           "groups":{"ghost":[{"t":0,"pos":[1,1,1],"mirror":0},{"t":4,"pos":[2,1,2],"ease":"inOut"},{"t":6,"mirror":1}]},
           "cursor":[{"t":0,"at":[1,2,1]},{"t":3,"at":[3,2,3],"down":true},{"t":3.5,"down":false}]
         },
         "captions":[{"t0":0,"t1":4,"text":"One"},{"t0":4,"t1":10,"title":"Two","text":"Second step"}],
         "chips":[{"t0":1,"t1":5,"rows":[["Scroll","Turn it"],["Click","Lock"]],"lit":[[0,2,3]]}],
         "overlays":[
           {"type":"box","t0":1,"t1":9,"from":[1,1,1],"to":[4,3,4],"color":"#7FE3FF","keys":[{"t":2,"to":[4,3,4]},{"t":4,"to":[5,4,5]}]},
           {"type":"arrow","t0":2,"t1":8,"at":[3,2,3],"dir":"+x","len":2}
         ],
         "panels":[{"type":"bar","t0":2,"t1":9,"label":"Progress","value":0.5,"states":[{"t":5,"value":0.9}]}]
        }
        """;

    private static String with(String from, String to) {
        assertTrue(GOOD.contains(from), "test string missing: " + from);
        return GOOD.replace(from, to);
    }

    /** Only the first place the text occurs (the ghost group comes before the real one). */
    private static String withFirst(String from, String to) {
        int at = GOOD.indexOf(from);
        assertTrue(at >= 0, "test string missing: " + from);
        return GOOD.substring(0, at) + to + GOOD.substring(at + from.length());
    }

    private static SceneException bad(String json) {
        return assertThrows(SceneException.class, () -> SceneReader.read(json));
    }

    @Test
    void readsAWholeLesson() {
        Scene s = SceneReader.read(GOOD);
        assertEquals("demo", s.id);
        assertEquals(10, s.duration, 1e-9);
        assertEquals(List3.of(6, 4, 6), List3.of(s.size[0], s.size[1], s.size[2]));
        assertEquals(2, s.palette.size());
        assertEquals(3, s.groups.size());
        Scene.Group ghost = s.group("ghost");
        assertNotNull(ghost);
        assertEquals(3, ghost.sx);
        assertEquals(2, ghost.sy);
        assertEquals(3, ghost.sz);
        assertEquals(java.util.List.of("real"), ghost.match);
        assertEquals(2, ghost.cell[ghost.index(1, 0, 1)], "the middle of the floor is the second palette entry");
        assertEquals(0, ghost.cell[ghost.index(1, 1, 1)]);
        assertEquals(2, s.steps());
        assertEquals("Second step", s.captions.get(1).text());
        assertEquals(2, s.overlays.size());
        assertEquals(1, s.panels.size());
        assertEquals(3, s.cursor.size());
        assertTrue(s.cursor.get(1).down());
        assertFalse(s.cursor.get(2).down());
        assertEquals(Scene.Pt.WORLD, s.cursor.get(2).pt().kind(), "a key with no position stays where the one before it was");
        assertEquals(3, s.cursor.get(2).pt().at()[0], 1e-9);
    }

    @Test
    void stepsFollowTheCaptions() {
        Scene s = SceneReader.read(GOOD);
        assertEquals(0, s.stepAt(0));
        assertEquals(0, s.stepAt(3.99));
        assertEquals(1, s.stepAt(4));
        assertEquals(1, s.stepAt(9.9));
        assertEquals(4, s.stepStart(1), 1e-9);
        assertEquals(10, s.stepEnd(1), 1e-9);
    }

    @Test
    void anUnknownBlockKeyNamesTheRowAndColumn() {
        SceneException e = bad(withFirst("[\"ppp\",\"psp\",\"ppp\"]", "[\"ppp\",\"pxp\",\"ppp\"]"));
        assertTrue(e.getMessage().contains("groups[1].layers[0][1]"), e.getMessage());
        assertTrue(e.getMessage().contains("'x'"), e.getMessage());
    }

    @Test
    void aRaggedGridIsRefused() {
        SceneException e = bad(withFirst("[\"p.p\",\"...\",\"p.p\"]", "[\"p.p\",\"..\",\"p.p\"]"));
        assertTrue(e.getMessage().contains("characters"), e.getMessage());
    }

    @Test
    void anUnknownFieldIsAnErrorWithItsPath() {
        SceneException e = bad(with("\"color\":\"#7FE3FF\"", "\"colour\":\"#7FE3FF\""));
        assertTrue(e.getMessage().startsWith("overlays[0].colour"), e.getMessage());
    }

    @Test
    void anUnknownOverlayTypeListsTheKnownOnes() {
        SceneException e = bad(with("\"type\":\"arrow\"", "\"type\":\"spiral\""));
        assertTrue(e.getMessage().contains("overlays[1].type") && e.getMessage().contains("arrow"), e.getMessage());
    }

    @Test
    void timesOutsideTheLessonAreRefused() {
        assertTrue(bad(with("\"t0\":1,\"t1\":9,\"from\"", "\"t0\":1,\"t1\":19,\"from\"")).getMessage().contains("overlays[0]"));
        assertTrue(bad(with("{\"t\":5,\"yaw\":1.2,\"ease\":\"out\"}", "{\"t\":50,\"yaw\":1.2}")).getMessage().contains("tracks.camera[1].t"));
        assertTrue(bad(with("\"ops\":[{\"t\":2,", "\"ops\":[{\"t\":22,")).getMessage().contains("ops[0].t"));
    }

    @Test
    void aStaggerThatRunsPastTheEndIsRefused() {
        assertTrue(bad(with("\"stagger\":0.1", "\"stagger\":4")).getMessage().contains("after the lesson ends"));
    }

    @Test
    void keysMustBeInTimeOrder() {
        assertTrue(bad(with("{\"t\":3,\"at\":[3,2,3],\"down\":true},{\"t\":3.5", "{\"t\":3.7,\"at\":[3,2,3],\"down\":true},{\"t\":3.5")).getMessage().contains("time order"));
    }

    @Test
    void aMatchMustNameAnotherGroup() {
        assertTrue(bad(with("\"match\":\"real\"", "\"match\":\"nope\"")).getMessage().contains("groups[1].match"));
        assertTrue(bad(with("\"match\":\"real\"", "\"match\":\"ghost\"")).getMessage().contains("itself"));
    }

    @Test
    void anOpThatSelectsNothingIsAnError() {
        assertTrue(bad(with("\"layer\":0,\"stagger\"", "\"layer\":7,\"stagger\"")).getMessage().contains("selects no blocks"));
    }

    @Test
    void theFormatIsChecked() {
        assertTrue(bad(with("\"format\":1", "\"format\":2")).getMessage().contains("format"));
        assertTrue(bad("not json").getMessage().contains("JSON"));
        assertTrue(bad("[]").getMessage().contains("object"));
    }

    @Test
    void aChipRowNeedsAKeyAndAnAction() {
        assertTrue(bad(with("[\"Click\",\"Lock\"]", "[\"Click\"]")).getMessage().contains("chips[0].rows[1]"));
        assertTrue(bad(with("\"lit\":[[0,2,3]]", "\"lit\":[[5,2,3]]")).getMessage().contains("lit"));
    }

    @Test
    void captionsMayNotOverlap() {
        assertTrue(bad(with("{\"t0\":4,\"t1\":10,\"title\"", "{\"t0\":3,\"t1\":10,\"title\"")).getMessage().contains("captions[1]"));
    }

    @Test
    void tintsNameAGroup() {
        String tint = "{\"type\":\"tint\",\"t0\":1,\"t1\":2,\"group\":\"ghosty\",\"key\":\"p\",\"color\":\"#FFC857\"}";
        assertTrue(bad(with("\"overlays\":[", "\"overlays\":[" + tint + ",")).getMessage().contains("ghosty"));
    }

    /** A tiny helper so the assertion above reads as one value. */
    private record List3(int a, int b, int c) {
        static List3 of(int a, int b, int c) {
            return new List3(a, b, c);
        }
    }
}
