package io.github.profetgit.buildbuddy.demo;

import static io.github.profetgit.buildbuddy.demo.Director.act;
import static io.github.profetgit.buildbuddy.demo.Director.check;
import static io.github.profetgit.buildbuddy.demo.Director.until;
import static io.github.profetgit.buildbuddy.demo.Director.waitTicks;
import static io.github.profetgit.buildbuddy.demo.PlaceScenes.aim;
import static io.github.profetgit.buildbuddy.demo.SaveScenes.mc;

import io.github.profetgit.buildbuddy.auto.AutoBuilder;
import io.github.profetgit.buildbuddy.auto.ServerRules;
import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.placement.Orientation;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import io.github.profetgit.buildbuddy.ui.Settings;
import io.github.profetgit.buildbuddy.verify.Verifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;

/**
 * Auto-placing against a real dedicated server (dev/server/mp.py): no integrated-server call anywhere, so the mod runs the
 * strict player-equivalent rules. The server side (op, time, rules, the block checks) is the Python harness's; the two sides
 * meet through files in the demo output folder: {@code go} (the server is ready), {@code sweep-done} then
 * {@code verified-sweep}, {@code assist-done} then {@code verified-assist}. Everything else, the wipe, the items and the
 * walking, is sent as the op'd player's own commands.
 */
final class ServerScenes {
    private ServerScenes() {
    }

    /** -Dbuildbuddy.demo.server=true: the scenes run on a dedicated server, so the director must not wait for an integrated one. */
    static final boolean REMOTE = Boolean.getBoolean("buildbuddy.demo.server");
    /** Where the player stands: the top of the flat world's grass, +1. */
    static final int Y0 = Integer.getInteger("buildbuddy.demo.server.y", -60);
    static final int OX = 30, OZ = 30;

    static Placement build;
    static final List<Integer> samples = new ArrayList<>();
    static final List<Long> sampleNs = new ArrayList<>();

    static void cmd(String... commands) {
        Director.add(mc -> {
            for (String c : commands) mc.player.connection.sendCommand(c);
            return true;
        });
    }

    static void tp(double x, double z, float yaw, float pitch) {
        cmd(String.format(Locale.ROOT, "tp @s %.2f %d %.2f %.1f %.1f", x, Y0, z, yaw, pitch));
    }

    static void sendNow(String command) {
        mc().player.connection.sendCommand(command);
    }

    static boolean connected() {
        var mc = mc();
        return mc.getConnection() != null && mc.getConnection().getConnection().isConnected() && mc.level != null && mc.player != null;
    }

    static void items() {
        cmd("clear @s");
        waitTicks(4);
        cmd("give @s minecraft:stone_bricks 64", "give @s minecraft:oak_planks 64", "give @s minecraft:oak_log 64", "give @s minecraft:glass_pane 64",
            "give @s minecraft:oak_door 8", "give @s minecraft:oak_slab 64", "give @s minecraft:oak_stairs 64", "give @s minecraft:lantern 8", "give @s minecraft:torch 16");
        waitTicks(10);
    }

    static void wipe() {
        cmd(String.format(Locale.ROOT, "fill 22 %d 22 44 %d 40 air", Y0, Y0 + 10));
        waitTicks(14);
    }

    static void signal(String name) {
        act(() -> {
            try {
                Files.writeString(Director.OUT.resolve(name), String.valueOf(System.currentTimeMillis()));
            } catch (IOException e) {
                check("server/signal " + name, false, e.toString());
            }
        });
    }

    static void waitFor(String name, int ticks) {
        until("server/the harness answered " + name, ticks, () -> Files.exists(Director.OUT.resolve(name)));
    }

    static byte status(int x, int y, int z) {
        return GhostRenderer.verifierOf(build).statusAt(OX + x, Y0 + y, OZ + z);
    }

    static String missingList() {
        StringBuilder sb = new StringBuilder();
        var v = GhostRenderer.verifierOf(build);
        int n = 0;
        for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++) for (int x = 0; x < 5; x++) {
            byte st = status(x, y, z);
            if ((st == Verifier.MISSING || st == Verifier.WRONG) && n++ < 10) sb.append(" [").append(x).append(',').append(y).append(',').append(z).append(' ').append(v.expectedAt(OX + x, Y0 + y, OZ + z).getBlock().getName().getString()).append(st == Verifier.WRONG ? " WRONG" : "").append(']');
        }
        return sb.toString();
    }

    static void rate(String what) {
        int maxTick = 0, max20 = 0, total = samples.isEmpty() ? 0 : samples.get(samples.size() - 1) - samples.get(0);
        for (int i = 1; i < samples.size(); i++) maxTick = Math.max(maxTick, samples.get(i) - samples.get(i - 1));
        for (int i = 20; i < samples.size(); i++) max20 = Math.max(max20, samples.get(i) - samples.get(i - 20));
        double maxSecond = 0;
        for (int i = 0, j = 0; i < samples.size(); i++) {
            while (sampleNs.get(i) - sampleNs.get(j) > 1_000_000_000L) j++;
            maxSecond = Math.max(maxSecond, samples.get(i) - samples.get(j));
        }
        int first = -1, last = 0;
        for (int i = 1; i < samples.size(); i++) if (!samples.get(i).equals(samples.get(i - 1))) {
            if (first < 0) first = i;
            last = i;
        }
        double active = first < 0 ? 0 : (last - first + 1) / 20.0;
        check(what + "/never above 20 a second", maxTick <= 1 && max20 <= 20,
            String.format(Locale.ROOT, "%d placed in %d ticks, most in one tick %d, most in any 20 ticks %d, most in any real second %.0f, %.1f/s over the %.1f s it was placing", total, samples.size(), maxTick, max20, maxSecond, active > 0 ? total / active : 0.0, active));
    }

    static void startBuild() {
        act(() -> {
            build = new Placement("server auto", Samples.autoTest(), "buildbuddy:auto-test.litematic", Director.DIM, new BlockPos(OX, Y0, OZ), Orientation.NONE);
            build.locked = true;
            Placements.add(build);
            AutoScenes.build = build;
            UiScenes.house = build;
        });
        until("server/the build is compared with the world", 400, () -> GhostRenderer.verifierOf(build) != null && GhostRenderer.verifierOf(build).settled());
    }

    static void serverAuto() {
        Director.clean();
        waitFor("go", 3000);
        act(() -> {
            Settings.get().autoRate = 20;
            Settings.get().autoTurn = true;
            AutoBuilder.testServer = null;
            var mc = mc();
            String key = AutoBuilder.serverKey(mc);
            check("server/it is a dedicated server, not the integrated one", mc.getSingleplayerServer() == null && mc.getCurrentServer() != null, "integrated " + mc.getSingleplayerServer() + ", server " + (mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip));
            check("server/the strict server rules apply (not an own world)", !AutoBuilder.ownWorld(mc) && key != null, "server key " + key);
            check("server/the server was allowed beforehand in servers.json", key != null && ServerRules.get().decision(key) == ServerRules.Decision.ALLOWED, key + " -> " + (key == null ? null : ServerRules.get().decision(key)));
        });
        cmd("gamemode survival @s");
        waitTicks(6);
        tp(32.5, 27.5, 0, 18);
        waitTicks(40);
        wipe();
        items();
        startBuild();

        // ---- Sweep, walking round the building the way AutoScenes.tour does; the view turns by itself
        act(() -> {
            samples.clear();
            sampleNs.clear();
            AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP);
            check("server/sweep switches on with no disclaimer", AutoBuilder.mode() == AutoBuilder.Mode.SWEEP && mc().gui.screen() == null, AutoBuilder.detail() + ", screen " + mc().gui.screen());
        });
        double[][] stops = {{32.5, Y0, 27.5, 0}, {32.5, Y0, 34.8, 180}, {27.4, Y0, 31.5, -90}, {37.2, Y0, 31.5, 90}, {32.5, Y0 + 3.5, 31.5, 0}};
        int[] t = {0, 0};
        long[] t0 = {System.nanoTime()};
        Director.add(mc -> {
            if (t[0] == 0) t0[0] = System.nanoTime();
            samples.add(AutoBuilder.placedCount());
            sampleNs.add(System.nanoTime());
            if (t[0] % 120 == 0) {
                double[] s = stops[(t[0] / 120) % stops.length];
                sendNow(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f 18", s[0], s[1], s[2], s[3]));
            }
            t[0]++;
            var c = GhostRenderer.verifierOf(build).counts();
            if (c.missing() == 0 && c.wrong() == 0 && c.correct() == build.blueprint.totalBlocks()) t[1]++;
            else t[1] = 0;
            return t[1] >= 30 || t[0] > 1800 || !AutoBuilder.on();
        });
        act(() -> {
            var c = GhostRenderer.verifierOf(build).counts();
            boolean still = AutoBuilder.on();
            int[] why = AutoBuilder.reasons();
            String badge = AutoBuilder.status();
            AutoBuilder.stop(mc(), "scene");
            check("server/sweep: every block is right on the client (stairs, door, panes, slabs, torch, lantern)", c.missing() == 0 && c.wrong() == 0 && c.correct() == build.blueprint.totalBlocks(),
                c + " of " + build.blueprint.totalBlocks() + ", " + AutoBuilder.detail() + ", ran " + t[0] + " ticks, auto-placing still on " + still + ", badge '" + badge + "', wrong facing " + why[0] + " no support " + why[1] + " out of reach " + why[2] + missingList());
            check("server/sweep: it stayed connected", connected(), "connected " + connected());
            rate("server/sweep");
            Director.results.add(String.format(Locale.ROOT, "{\"name\":\"server/sweep ticks\",\"pass\":true,\"detail\":\"%d ticks, %.1f s wall\"}", t[0], (System.nanoTime() - t0[0]) / 1e9));
        });
        signal("sweep-done");
        waitFor("verified-sweep", 1800);

        // ---- Assist: use held on each ghost cell in turn, standing north of the building, never turning
        cmd("gamemode survival @s");
        wipe();
        items();
        until("server/the wiped build is missing again", 300, () -> GhostRenderer.verifierOf(build).counts().correct() == 0);
        tp(32.5, 28.8, 0, 18);
        waitTicks(30);
        List<int[]> targets = new ArrayList<>();
        for (int z = 0; z <= 2; z++) for (int x = 0; x <= 4; x++) targets.add(new int[]{x, 0, z});
        for (int y = 1; y <= 3; y++) for (int x = 0; x <= 4; x++) if (!(x == 2 && y <= 2)) targets.add(new int[]{x, y, 0});
        act(() -> {
            samples.clear();
            sampleNs.clear();
            Settings.get().autoTurn = false;
            build.layerLo = 0;
            build.layerHi = 0;
            AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
            check("server/assist switches on with no disclaimer", AutoBuilder.mode() == AutoBuilder.Mode.ASSIST && mc().gui.screen() == null, AutoBuilder.detail() + ", screen " + mc().gui.screen());
        });
        int[] at = {0, 0};
        Director.add(mc -> {
            samples.add(AutoBuilder.placedCount());
            sampleNs.add(System.nanoTime());
            if (at[0] >= targets.size()) {
                PlaceScenes.release(mc.options.keyUse);
                return true;
            }
            int[] c = targets.get(at[0]);
            if (c[1] == 1 && build.layerHi == 0) build.layerHi = -1;
            if (status(c[0], c[1], c[2]) == Verifier.CORRECT || at[1] >= 20) {
                PlaceScenes.release(mc.options.keyUse);
                at[0]++;
                at[1] = 0;
                return false;
            }
            double ay = c[1] == 0 ? Y0 + 0.95 : Y0 + c[1] + 0.5;
            double az = OZ + c[2] + (c[1] == 0 ? 0.9 : 0.15);
            if (at[1] == 0) sendNow(String.format(Locale.ROOT, "tp @s %.2f %d 28.80 0 18", OX + c[0] + 0.5, Y0));
            aim(mc, OX + c[0] + 0.5, ay, az);
            if (at[1] == 4) PlaceScenes.hold(mc.options.keyUse);
            at[1]++;
            return false;
        });
        waitTicks(10);
        act(() -> {
            int right = 0;
            StringBuilder missing = new StringBuilder();
            for (int[] c : targets) {
                if (status(c[0], c[1], c[2]) == Verifier.CORRECT) right++;
                else missing.append(" [").append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(']');
            }
            var cnt = GhostRenderer.verifierOf(build).counts();
            AutoBuilder.stop(mc(), "scene");
            check("server/assist: every cell it was held on is right (floor, north wall, stairs; the door needs facing north, so Sweep covers it)", right == targets.size() && cnt.wrong() == 0, right + " of " + targets.size() + " right, wrong " + cnt.wrong() + missing + ", " + AutoBuilder.detail());
            check("server/assist: it stayed connected", connected(), "connected " + connected());
            rate("server/assist");
        });
        signal("assist-done");
        waitFor("verified-assist", 1800);
        act(() -> {
            AutoBuilder.stop(mc(), "scene");
            AutoBuilder.testServer = null;
            Placements.remove(build);
            Settings.get().autoRate = io.github.profetgit.buildbuddy.auto.Rate.DEFAULT;
            Settings.get().autoTurn = false;
        });
        waitTicks(10);
    }
}
