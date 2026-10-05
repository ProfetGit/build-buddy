package io.github.profetgit.cyanotype.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CandidatesTest {
    private static final BlockPos T = new BlockPos(5, 64, -3);

    @Test
    void everyClickLandsInTheTarget() {
        List<Candidates.Click> clicks = Candidates.around(T);
        assertEquals(30, clicks.size());
        for (Candidates.Click c : clicks) assertEquals(T, c.lands(), c.toString());
    }

    @Test
    void theMiddleOfEveryFaceComesFirst() {
        List<Candidates.Click> clicks = Candidates.around(T);
        for (int i = 0; i < 6; i++) assertTrue(clicks.get(i).u() == 0.5 && clicks.get(i).v() == 0.5, "click " + i);
        for (Direction d : Direction.values()) {
            boolean found = false;
            for (int i = 0; i < 6; i++) found |= clicks.get(i).face() == d.getOpposite();
            assertTrue(found, "face " + d);
        }
    }

    @Test
    void thePointLiesOnTheClickedFaceOfTheSupport() {
        for (Candidates.Click c : Candidates.around(T)) {
            Vec3 p = c.point();
            BlockPos s = c.support();
            Direction f = c.face();
            double[] lo = {s.getX(), s.getY(), s.getZ()};
            double[] v = {p.x, p.y, p.z};
            for (int a = 0; a < 3; a++) {
                assertTrue(v[a] >= lo[a] - 1e-9 && v[a] <= lo[a] + 1 + 1e-9, "inside the block's extent on axis " + a + " for " + c);
            }
            int axis = f.getAxis().ordinal();
            double plane = lo[axis] + (f.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
            assertEquals(plane, v[axis], 1e-9, "on the face plane for " + c);
        }
    }

    @Test
    void theSidesOfAWallCanBeClickedHighAndLowForTheHalfOfASlab() {
        // a click on the side of the block to the west, at a quarter and three quarters of its height
        double lowest = 9, highest = -9;
        for (Candidates.Click c : Candidates.around(T)) {
            if (c.face() != Direction.EAST) continue;
            double y = c.point().y - T.getY();
            lowest = Math.min(lowest, y);
            highest = Math.max(highest, y);
        }
        assertEquals(0.25, lowest, 1e-9);
        assertEquals(0.75, highest, 1e-9);
    }

    @Test
    void clicksOnTheBlockItselfCoverEveryFace() {
        List<Candidates.Click> self = Candidates.onSelf(T);
        assertEquals(30, self.size());
        for (Candidates.Click c : self) assertEquals(T, c.support());
    }
}
