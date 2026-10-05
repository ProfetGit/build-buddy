package io.github.profetgit.cyanotype.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

class OrientationTest {
    private static List<Orientation> all() {
        List<Orientation> out = new ArrayList<>();
        for (Rotation r : Rotation.values()) for (Mirror m : Mirror.values()) out.add(new Orientation(r, m));
        return out;
    }

    @Test
    void cellMapsAgreeWithTheTurnMatrix() {
        int sx = 3, sz = 5;
        for (Orientation o : all()) {
            int[] m = o.linear();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (int x : new int[]{0, sx - 1}) {
                for (int z : new int[]{0, sz - 1}) {
                    minX = Math.min(minX, m[0] * x + m[1] * z);
                    minZ = Math.min(minZ, m[2] * x + m[3] * z);
                }
            }
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    assertEquals(m[0] * x + m[1] * z - minX, o.mapX(x, z, sx, sz), o + " x of " + x + "," + z);
                    assertEquals(m[2] * x + m[3] * z - minZ, o.mapZ(x, z, sx, sz), o + " z of " + x + "," + z);
                }
            }
        }
    }

    @Test
    void mapsAreABijectionOntoTheTurnedBox() {
        int sx = 4, sz = 7;
        for (Orientation o : all()) {
            int w = o.sizeX(sx, sz), d = o.sizeZ(sx, sz);
            boolean[][] seen = new boolean[w][d];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    int ox = o.mapX(x, z, sx, sz), oz = o.mapZ(x, z, sx, sz);
                    assertTrue(ox >= 0 && oz >= 0 && ox < w && oz < d, o + " leaves its box");
                    assertFalse(seen[ox][oz], o + " maps two cells to one");
                    seen[ox][oz] = true;
                }
            }
        }
    }

    @Test
    void fourQuarterTurnsAreTheIdentity() {
        for (Orientation o : all()) {
            Orientation t = o;
            for (int i = 0; i < 4; i++) t = t.rotated(Rotation.CLOCKWISE_90);
            assertEquals(Orientation.fromLinear(o.linear()), Orientation.fromLinear(t.linear()));
        }
    }

    @Test
    void flippingTwiceIsTheIdentity() {
        for (Orientation o : all()) {
            for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
                Orientation twice = o.flipped(axis).flipped(axis);
                assertTrue(java.util.Arrays.equals(o.linear(), twice.linear()), o + " flipped twice across " + axis);
            }
        }
    }

    @Test
    void aFlipMirrorsTheTurnedBlueprintInTheWorld() {
        int sx = 3, sz = 5;
        for (Orientation o : all()) {
            for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
                Orientation f = o.flipped(axis);
                assertEquals(o.sizeX(sx, sz), f.sizeX(sx, sz), "a flip does not change the box");
                int w = o.sizeX(sx, sz), d = o.sizeZ(sx, sz);
                for (int x = 0; x < sx; x++) {
                    for (int z = 0; z < sz; z++) {
                        int ox = o.mapX(x, z, sx, sz), oz = o.mapZ(x, z, sx, sz);
                        int wantX = axis == Direction.Axis.X ? w - 1 - ox : ox;
                        int wantZ = axis == Direction.Axis.Z ? d - 1 - oz : oz;
                        assertEquals(wantX, f.mapX(x, z, sx, sz), o + " flipped " + axis + " x");
                        assertEquals(wantZ, f.mapZ(x, z, sx, sz), o + " flipped " + axis + " z");
                    }
                }
            }
        }
    }

    @Test
    void mirroredFlagTracksTheDeterminant() {
        assertFalse(Orientation.NONE.isMirrored());
        assertTrue(Orientation.NONE.flipped(Direction.Axis.X).isMirrored());
        assertFalse(Orientation.NONE.flipped(Direction.Axis.X).flipped(Direction.Axis.Z).isMirrored());
        assertTrue(new Orientation(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT).isMirrored());
    }
}
