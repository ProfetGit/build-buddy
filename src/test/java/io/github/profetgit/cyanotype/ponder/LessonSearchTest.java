package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LessonSearchTest {
    private static Scene lesson(String id, String title, String summary, String... tags) {
        String t = String.join("\",\"", tags);
        return SceneReader.read("{\"format\":1,\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"summary\":\"" + summary + "\",\"tags\":[\"" + t + "\"],\"duration\":5}");
    }

    private static final List<Scene> ALL = List.of(
        lesson("place", "Place a blueprint", "Pick a blueprint, turn it, lift it and click to put it down.", "library", "ghost", "turn"),
        lesson("edit", "Move and turn a build", "Drag arrows or the ring, or carry the whole build.", "edit", "arrows", "ring", "undo"),
        lesson("layers", "Show a few layers", "Scroll a window of layers up and down the build.", "layers", "slice"),
        lesson("save", "Pick a build to save", "Click a build and a box fits itself round it.", "save", "box", "pick"));

    private static List<String> ids(String q) {
        return LessonSearch.filter(ALL, q).stream().map(s -> s.id).toList();
    }

    @Test
    void noWordsKeepsTheListAsItIs() {
        assertEquals(List.of("place", "edit", "layers", "save"), ids(""));
        assertEquals(List.of("place", "edit", "layers", "save"), ids("   "));
    }

    @Test
    void aWordMatchesPartOfATitleASummaryOrATag() {
        assertEquals(List.of("layers"), ids("slice"), "a tag");
        assertEquals(List.of("save"), ids("fits"), "a summary");
        assertEquals(List.of("layers"), ids("FEW LAY"), "a title, in any case");
        assertEquals(List.of("edit"), ids("arrow"), "a part of a word");
    }

    @Test
    void everyWordMustMatch() {
        assertEquals(List.of("edit"), ids("turn ring"));
        assertTrue(ids("turn pizza").isEmpty());
    }

    @Test
    void theTitleCountsMoreThanTheTagsAndTheTagsMoreThanTheSummary() {
        // "pick" is in the title and a tag of save, and only in the summary of place: save first
        assertEquals(List.of("save", "place"), ids("pick"));
        // "build" is in two titles (and their summaries) and only in the summary of layers; ties keep the list's order
        assertEquals(List.of("edit", "save", "layers"), ids("build"));
        // a tag beats a summary: "ring" is a tag and in the summary of edit
        assertEquals(List.of("edit"), ids("ring"));
    }

    @Test
    void anUnknownWordFindsNothing() {
        assertTrue(ids("zzzz").isEmpty());
    }
}
