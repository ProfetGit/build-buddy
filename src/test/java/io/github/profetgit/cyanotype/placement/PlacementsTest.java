package io.github.profetgit.cyanotype.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlacementsTest {
    @BeforeEach
    void fresh() {
        Placements.clear();
    }

    private static Placement make(String name, int x, int y, int z) {
        return new Placement(name, null, "cyanotype:" + name + ".litematic", "minecraft:overworld", new BlockPos(x, y, z), Orientation.NONE);
    }

    @Test
    void saveFileRoundTrips() {
        Placement a = make("house", 10, 64, -20);
        a.orientation = new Orientation(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT);
        a.opacity = 0.35f;
        a.locked = true;
        a.accent = Placement.ACCENTS[2];
        Placement b = make("tower", -1, -5, 7);
        b.dimension = "minecraft:the_nether";
        b.visible = false;
        String json = PlacementStore.toJson(List.of(a, b));
        List<PlacementStore.Entry> back = PlacementStore.parse(json);
        assertEquals(2, back.size());
        PlacementStore.Entry e = back.get(0);
        assertEquals("house", e.name());
        assertEquals("cyanotype:house.litematic", e.ref());
        assertEquals(new BlockPos(10, 64, -20), e.origin());
        assertEquals(new Orientation(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT), e.orientation());
        assertEquals(0.35f, e.opacity(), 1e-6);
        assertTrue(e.locked());
        assertTrue(e.visible());
        assertEquals(Placement.ACCENTS[2], e.accent());
        assertEquals("minecraft:the_nether", back.get(1).dimension());
        assertFalse(back.get(1).visible());
    }

    @Test
    void damagedSavesAreSurvivedNotTrusted() {
        assertTrue(PlacementStore.parse("not json {{").isEmpty());
        assertTrue(PlacementStore.parse("{}").isEmpty());
        assertTrue(PlacementStore.parse("[]").isEmpty());
        // one broken entry does not take the good one with it
        String json = "{\"version\":1,\"placements\":[{\"name\":\"bad\"},{\"name\":\"ok\",\"file\":\"cyanotype:ok.litematic\",\"origin\":[1,2,3],\"rotation\":\"none\",\"mirror\":\"none\"}]}";
        List<PlacementStore.Entry> out = PlacementStore.parse(json);
        assertEquals(1, out.size());
        assertEquals("ok", out.get(0).name());
        // missing optional fields get defaults; an opacity out of range is pulled back in
        assertEquals("minecraft:overworld", out.get(0).dimension());
        assertTrue(out.get(0).visible());
        PlacementStore.Entry wild = PlacementStore.parse(json.replace("\"mirror\":\"none\"}", "\"mirror\":\"none\",\"opacity\":7}")).get(0);
        assertEquals(1f, wild.opacity(), 0f);
    }

    @Test
    void namesStayUniqueAndColoursTakeTurns() {
        Placement a = make("house", 0, 0, 0), b = make("house", 5, 0, 0), c = make("house", 9, 0, 0);
        Placements.add(a);
        Placements.add(b);
        Placements.add(c);
        assertEquals("house", a.name);
        assertEquals("house 2", b.name);
        assertEquals("house 3", c.name);
        assertEquals(Placement.ACCENTS[0], a.accent);
        assertEquals(Placement.ACCENTS[1], b.accent);
        assertEquals(c, Placements.active());
        assertEquals(b, Placements.find("house 2"));
        assertEquals(a, Placements.find("1"));
        assertNull(Placements.find("9"));
    }

    @Test
    void undoPutsBackTheLastMoveAndSkipsRemovedPlacements() {
        Placement a = make("a", 0, 0, 0), b = make("b", 0, 0, 0);
        Placements.add(a);
        Placements.add(b);
        Placements.remember(a);
        a.set(new BlockPos(3, 0, 0), Orientation.NONE);
        Placements.remember(b);
        b.set(new BlockPos(0, 0, 4), new Orientation(Rotation.CLOCKWISE_180, Mirror.NONE));
        assertTrue(Placements.canUndo());
        Placements.Change c = Placements.undo();
        assertEquals(b, c.placement());
        assertEquals(Placements.Kind.MOVE, c.kind());
        assertEquals(new BlockPos(0, 0, 0), b.origin);
        assertEquals(Orientation.NONE, b.orientation);
        Placements.remove(a);
        assertFalse(Placements.canUndo(), "a's snapshot went with it");
        assertNull(Placements.undo());
    }

    @Test
    void redoDoesAgainWhatUndoRevertedAndANewChangeEndsIt() {
        Placement a = make("a", 0, 0, 0);
        Placements.add(a);
        Placements.remember(a);
        a.set(new BlockPos(5, 0, 0), Orientation.NONE);
        Placements.remember(a);
        a.set(new BlockPos(5, 0, 7), new Orientation(Rotation.CLOCKWISE_90, Mirror.NONE));
        assertFalse(Placements.canRedo());
        Placements.undo();
        assertEquals(new BlockPos(5, 0, 0), a.origin);
        assertTrue(Placements.canRedo());
        Placements.undo();
        assertEquals(new BlockPos(0, 0, 0), a.origin);
        Placements.redo();
        assertEquals(new BlockPos(5, 0, 0), a.origin);
        Placements.redo();
        assertEquals(new BlockPos(5, 0, 7), a.origin);
        assertEquals(new Orientation(Rotation.CLOCKWISE_90, Mirror.NONE), a.orientation);
        assertFalse(Placements.canRedo());
        assertNull(Placements.redo());
        // undo, then a different change: what was undone is gone
        Placements.undo();
        Placements.remember(a);
        a.set(new BlockPos(1, 1, 1), Orientation.NONE);
        assertFalse(Placements.canRedo(), "a new change ends the redo history");
        assertNull(Placements.redo());
    }

    @Test
    void aRemovalCanBeUndoneAndRedoneInPlace() {
        Placement a = make("a", 0, 0, 0), b = make("b", 1, 0, 0), c = make("c", 2, 0, 0);
        Placements.add(a);
        Placements.add(b);
        Placements.add(c);
        Placements.remember(b);
        b.set(new BlockPos(9, 0, 0), Orientation.NONE);
        Placements.select(b);
        Placements.setMode(Placements.Mode.EDIT);
        Placements.removeUndoable(b);
        assertEquals(List.of(a, c), Placements.all());
        assertNull(Placements.active());
        assertEquals(Placements.Mode.IDLE, Placements.mode());
        Placements.Change back = Placements.undo();
        assertEquals(Placements.Kind.RESTORE, back.kind());
        assertEquals(List.of(a, b, c), Placements.all(), "it comes back where it was in the list");
        assertEquals(b, Placements.active());
        assertEquals(new BlockPos(9, 0, 0), b.origin);
        // the move before the removal is still on the stack
        Placements.undo();
        assertEquals(new BlockPos(1, 0, 0), b.origin);
        Placements.redo();
        assertEquals(new BlockPos(9, 0, 0), b.origin);
        Placements.Change gone = Placements.redo();
        assertEquals(Placements.Kind.DELETE, gone.kind());
        assertEquals(List.of(a, c), Placements.all());
        Placements.undo();
        assertEquals(List.of(a, b, c), Placements.all());
    }

    @Test
    void forgettingTheLastSnapshotLeavesNothingToUndo() {
        Placement a = make("a", 0, 0, 0);
        Placements.add(a);
        Placements.remember(a);
        Placements.forgetLast();
        assertFalse(Placements.canUndo());
    }

    @Test
    void removingTheActivePlacementLeavesEditMode() {
        Placement a = make("a", 0, 0, 0);
        Placements.add(a);
        Placements.setMode(Placements.Mode.EDIT);
        Placements.remove(a);
        assertNull(Placements.active());
        assertEquals(Placements.Mode.IDLE, Placements.mode());
        assertNotNull(Placements.all());
    }

    @Test
    void settingTheSameSpotDoesNotCountAsMoving() {
        Placement a = make("a", 1, 2, 3);
        long before = a.lastMoveNs;
        a.set(new BlockPos(1, 2, 3), Orientation.NONE);
        assertEquals(before, a.lastMoveNs);
        a.set(new BlockPos(1, 2, 4), Orientation.NONE);
        assertTrue(a.lastMoveNs >= before);
        assertEquals(new BlockPos(1, 2, 4), a.origin);
    }
}
