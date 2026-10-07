package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.profetgit.cyanotype.ui.BlockLook;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Prints how long a picture of a typical lesson takes (flat colours: no texture reads, the closest a test can get). */
class StageRasterPerfTest {
    @Test
    void aTypicalStageDrawsFast() {
        StringBuilder ground = new StringBuilder(), layers = new StringBuilder();
        String row = "g".repeat(15);
        String layer = "[" + ("\"" + row + "\",").repeat(14) + "\"" + row + "\"]";
        String house = "[[\"ppppppp\",\"ppppppp\",\"ppppppp\",\"ppppppp\",\"ppppppp\",\"ppppppp\",\"ppppppp\"],[\"pppppp.\",\"p.....p\",\"p.....p\",\"p.....p\",\"p.....p\",\"p.....p\",\"ppppppp\"],"
            + "[\"pppppp.\",\"p.....p\",\"p.....p\",\"p.....p\",\"p.....p\",\"p.....p\",\"ppppppp\"],[\"pppppp.\",\"p.....p\",\"p.....p\",\"p.....p\",\"p.....p\",\"p.....p\",\"ppppppp\"]]";
        Scene s = SceneReader.read("{\"format\":1,\"id\":\"t\",\"title\":\"T\",\"summary\":\"S\",\"duration\":10,\"stage\":{\"size\":[15,9,15],\"focus\":[7.5,2,7.5]},\"palette\":{\"g\":\"minecraft:green\",\"p\":\"minecraft:white\"},"
            + "\"groups\":[{\"id\":\"ground\",\"layers\":[" + layer + "," + layer + "]},{\"id\":\"house\",\"pos\":[4,2,4],\"layers\":" + house + "},{\"id\":\"ghost\",\"mode\":\"ghost\",\"pos\":[5,2,5],\"layers\":" + house + "}],"
            + "\"overlays\":[{\"type\":\"box\",\"t0\":0,\"t1\":9,\"from\":[4,2,4],\"to\":[11,6,11]},{\"type\":\"arrow\",\"t0\":0,\"t1\":9,\"at\":[8,3,8],\"dir\":\"+x\"}]}");
        StageRaster.Looks looks = spec -> BlockLook.Look.solid(spec.endsWith("green") ? 0xFF40A040 : 0xFFC8A060);
        StageRaster r = new StageRaster();
        int[][] sizes = {{640, 360}, {1280, 720}, {1920, 1080}};
        for (int[] sz : sizes) {
            long[] ns = new long[40];
            for (int i = 0; i < ns.length; i++) {
                Snapshot snap = Evaluator.at(s, i * 0.1);
                long t0 = System.nanoTime();
                r.render(s, snap, looks, sz[0], sz[1], 2.0);
                ns[i] = System.nanoTime() - t0;
            }
            Arrays.sort(ns);
            System.out.printf("PERF stage %dx%d: median %.2f ms, p90 %.2f ms%n", sz[0], sz[1], ns[20] / 1e6, ns[36] / 1e6);
            assertTrue(ns[20] < 120_000_000L, "a picture takes " + ns[20] / 1e6 + " ms");
        }
    }
}
