package io.github.profetgit.buildbuddy.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.profetgit.buildbuddy.ui.ChipIcons.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChipIconsTest {
    private static List<Kind> kinds(String key) {
        return ChipIcons.parse(key).stream().map(ChipIcons.Part::kind).toList();
    }

    @Test
    void mouseActionsBecomeAMouse() {
        assertEquals(List.of(Kind.MOUSE_LEFT), kinds("Click"));
        assertEquals(List.of(Kind.MOUSE_RIGHT), kinds("Right click"));
        assertEquals(List.of(Kind.MOUSE_WHEEL), kinds("Scroll"));
        assertEquals(List.of(Kind.MOUSE_LEFT), kinds("Click flip"));
    }

    @Test
    void dragTwiceAndHoldKeepAMarkAfterTheMouse() {
        assertEquals("drag", ChipIcons.parse("Drag arrow").get(0).mark());
        assertEquals("drag", ChipIcons.parse("Drag").get(0).mark());
        assertEquals("x2", ChipIcons.parse("Click twice").get(0).mark());
        assertEquals(Kind.MOUSE_RIGHT, ChipIcons.parse("Hold use").get(0).kind());
        assertEquals("hold", ChipIcons.parse("Hold use").get(0).mark());
    }

    @Test
    void modifiersAreKeyCapsJoinedWithAPlus() {
        assertEquals(List.of(Kind.KEYCAP, Kind.PLUS, Kind.MOUSE_LEFT), kinds("Shift+Click"));
        assertEquals(List.of(Kind.KEYCAP, Kind.PLUS, Kind.MOUSE_WHEEL), kinds("Ctrl+Scroll"));
        assertEquals(List.of(Kind.KEYCAP, Kind.PLUS, Kind.KEYCAP, Kind.PLUS, Kind.MOUSE_WHEEL), kinds("Ctrl+Shift+Scroll"));
        assertEquals("Shift", ChipIcons.parse("Shift+Click").get(0).text());
    }

    @Test
    void keyNamesAreCapsAndTwoKeysKeepTheirSlash() {
        assertEquals(List.of(Kind.KEYCAP), kinds("V"));
        assertEquals(List.of(Kind.KEYCAP), kinds("Delete"));
        assertEquals(List.of(Kind.KEYCAP, Kind.PLUS, Kind.KEYCAP, Kind.SLASH, Kind.KEYCAP), kinds("Ctrl+Z / Y"));
    }

    @Test
    void sentencesAndOddWordsStayText() {
        assertEquals(List.of(Kind.TEXT), kinds("Aim at a build"));
        assertEquals(List.of(Kind.TEXT), kinds("Release"));
        assertEquals(List.of(Kind.TEXT), kinds("Water"));
    }

    @Test
    void emptyIsNothing() {
        assertEquals(List.of(), kinds(""));
    }
}
