package io.github.profetgit.buildbuddy.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetOrderTest {
    @Test
    void lowerLayersGoFirstWhateverTheDistance() {
        List<TargetOrder.Cand> l = new ArrayList<>(List.of(new TargetOrder.Cand(1, 3, 1.0), new TargetOrder.Cand(2, 0, 9.0), new TargetOrder.Cand(3, 1, 0.5)));
        l.sort(TargetOrder.ORDER);
        assertEquals(2, l.get(0).pos());
        assertEquals(3, l.get(1).pos());
        assertEquals(1, l.get(2).pos());
    }

    @Test
    void withinALayerTheNearestGoesFirstAndTiesAreStable() {
        List<TargetOrder.Cand> l = new ArrayList<>(List.of(new TargetOrder.Cand(7, 2, 4.0), new TargetOrder.Cand(5, 2, 1.0), new TargetOrder.Cand(6, 2, 1.0)));
        l.sort(TargetOrder.ORDER);
        assertEquals(5, l.get(0).pos());
        assertEquals(6, l.get(1).pos());
        assertEquals(7, l.get(2).pos());
    }
}
