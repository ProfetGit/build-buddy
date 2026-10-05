package io.github.profetgit.cyanotype.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.TestBootstrap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import org.junit.jupiter.api.Test;

class ShapeFixerTest {
    static {
        TestBootstrap.init();
    }

    private static final BlockState STONE = Blocks.STONE.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState(), PANE = Blocks.GLASS_PANE.defaultBlockState();

    private static Region region(int sx, int sy, int sz, BiFunction<int[], Integer, BlockState> fill) {
        List<PaletteEntry> palette = new ArrayList<>(List.of(PaletteEntry.AIR));
        Map<BlockState, Integer> index = new HashMap<>();
        short[] blocks = new short[sx * sy * sz];
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            BlockState s = fill.apply(new int[]{x, y, z}, 0);
            if (s == null || s.isAir()) continue;
            int id = index.computeIfAbsent(s, k -> {
                palette.add(PaletteEntry.of(k));
                return palette.size() - 1;
            });
            blocks[(y * sz + z) * sx + x] = (short) id;
        }
        return new Region("main", 0, 0, 0, sx, sy, sz, palette.toArray(new PaletteEntry[0]), blocks, new ArrayList<>());
    }

    private static BlockState at(Region r, int x, int y, int z) {
        return r.at(x, y, z).state();
    }

    @Test
    void aPaneBetweenTwoBlocksIsAPaneNotAPost() {
        Region r = region(3, 1, 1, (c, k) -> c[0] == 1 ? PANE : STONE);
        BlockState before = at(r, 1, 0, 0);
        assertFalse(before.getValue(IronBarsBlock.EAST) || before.getValue(IronBarsBlock.WEST), "the plain default state is a post");
        Region fixed = ShapeFixer.fix(r);
        BlockState pane = at(fixed, 1, 0, 0);
        assertTrue(pane.getValue(IronBarsBlock.EAST) && pane.getValue(IronBarsBlock.WEST), "both sides have a block");
        assertFalse(pane.getValue(IronBarsBlock.NORTH) || pane.getValue(IronBarsBlock.SOUTH));
        assertEquals(STONE, at(fixed, 0, 0, 0), "other blocks are left alone");
    }

    @Test
    void aLonePaneStaysAPostAndANeighbourlessBlueprintIsReturnedAsIs() {
        Region r = region(3, 3, 3, (c, k) -> c[0] == 1 && c[1] == 1 && c[2] == 1 ? PANE : null);
        assertSame(r, ShapeFixer.fix(r), "nothing to change: the same region");
        Region plain = region(2, 2, 2, (c, k) -> STONE);
        assertSame(plain, ShapeFixer.fix(plain));
    }

    @Test
    void panesAndBarsMeetEachOtherAndSolidWallsButNotAir() {
        // a row along z: stone, pane, pane, bars, air; the end pane touches the bars on one side only
        Region r = region(1, 1, 5, (c, k) -> switch (c[2]) {
            case 0 -> STONE;
            case 1, 2 -> PANE;
            case 3 -> Blocks.IRON_BARS.defaultBlockState();
            default -> null;
        });
        Region f = ShapeFixer.fix(r);
        BlockState first = at(f, 0, 0, 1), second = at(f, 0, 0, 2), bars = at(f, 0, 0, 3);
        assertTrue(first.getValue(IronBarsBlock.NORTH) && first.getValue(IronBarsBlock.SOUTH));
        assertTrue(second.getValue(IronBarsBlock.NORTH) && second.getValue(IronBarsBlock.SOUTH), "a pane meets bars");
        assertTrue(bars.getValue(IronBarsBlock.NORTH), "bars meet the pane");
        assertFalse(bars.getValue(IronBarsBlock.SOUTH), "nothing beyond");
        assertFalse(first.getValue(IronBarsBlock.EAST) || first.getValue(IronBarsBlock.WEST));
    }

    @Test
    void fencesMeetSolidBlocks() {
        // (a fence meeting a fence goes by block tags, which a unit test does not load: the real-client house scene checks that)
        Region r = region(3, 1, 1, (c, k) -> c[0] == 0 || c[0] == 2 ? STONE : Blocks.OAK_FENCE.defaultBlockState());
        Region f = ShapeFixer.fix(r);
        assertTrue(at(f, 1, 0, 0).getValue(FenceBlock.WEST), "a fence meets the stone beside it");
        assertTrue(at(f, 1, 0, 0).getValue(FenceBlock.EAST));
        assertFalse(at(f, 1, 0, 0).getValue(FenceBlock.NORTH) || at(f, 1, 0, 0).getValue(FenceBlock.SOUTH));
    }

    @Test
    void wallsFollowTheirNeighbours() {
        Region r = region(3, 1, 1, (c, k) -> c[0] == 1 ? Blocks.COBBLESTONE_WALL.defaultBlockState() : STONE);
        BlockState wall = at(ShapeFixer.fix(r), 1, 0, 0);
        assertTrue(wall.getValue(WallBlock.EAST) != WallSide.NONE && wall.getValue(WallBlock.WEST) != WallSide.NONE, "a wall between two blocks connects to both");
        assertEquals(WallSide.NONE, wall.getValue(WallBlock.NORTH));
    }

    @Test
    void aStairWithAnotherAcrossItsFrontTurnsIntoACorner() {
        BlockState east = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.BOTTOM);
        BlockState north = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH).setValue(StairBlock.HALF, Half.BOTTOM);
        Region r = region(2, 1, 1, (c, k) -> c[0] == 0 ? east : north);
        Region f = ShapeFixer.fix(r);
        StairsShape shape = at(f, 0, 0, 0).getValue(StairBlock.SHAPE);
        assertTrue(shape == StairsShape.OUTER_LEFT || shape == StairsShape.OUTER_RIGHT, "got " + shape);
        assertSame(f, ShapeFixer.fix(f), "fixing again changes nothing");
    }

    @Test
    void differentNeighbourhoodsGetTheirOwnPaletteSlotsAndTheRestIsUntouched() {
        // two panes in one blueprint: one between stone (connected east-west), one alone
        Region r = region(7, 1, 1, (c, k) -> switch (c[0]) {
            case 0, 2 -> STONE;
            case 1, 5 -> PANE;
            default -> null;
        });
        Region f = ShapeFixer.fix(r);
        assertTrue(at(f, 1, 0, 0).getValue(IronBarsBlock.EAST));
        assertFalse(at(f, 5, 0, 0).getValue(IronBarsBlock.EAST) || at(f, 5, 0, 0).getValue(IronBarsBlock.WEST), "the lone one stays a post");
        assertEquals(r.sx, f.sx);
        assertEquals(r.blocks.length, f.blocks.length);
        assertTrue(f.palette.length > r.palette.length, "the connected variant is a new slot");
        assertSame(f, ShapeFixer.fix(f));
        // a blueprint wraps regions
        Blueprint bp = new Blueprint(Blueprint.Metadata.of("t"), List.of(r));
        assertNotSame(bp, ShapeFixer.fix(bp));
        assertEquals(bp.sizeX, ShapeFixer.fix(bp).sizeX);
    }

    @Test
    void waterloggedPanesAndTheirWaterSurvive() {
        BlockState wet = PANE.setValue(IronBarsBlock.WATERLOGGED, true);
        Region r = region(3, 1, 1, (c, k) -> c[0] == 1 ? wet : STONE);
        BlockState got = at(ShapeFixer.fix(r), 1, 0, 0);
        assertTrue(got.getValue(IronBarsBlock.WATERLOGGED));
        assertTrue(got.getValue(IronBarsBlock.EAST));
    }
}
