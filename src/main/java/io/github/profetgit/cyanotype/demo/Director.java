package io.github.profetgit.cyanotype.demo;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.LitematicReader;
import io.github.profetgit.cyanotype.blueprint.LitematicWriter;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.command.DevCommands;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Dev-only scene director; does nothing unless the JVM runs with -Dcyanotype.demo=<dir> (dev/demo/run.sh). In the flat
 * demo world (grass at y -10) it builds blueprints, writes and reads them as .litematic, places them, films stills to
 * <dir> and times frames with the ghost in view, out of view and hidden. Results go to <dir>/results.json.
 */
public final class Director {
    private static final String DIR = System.getProperty("cyanotype.demo");
    public static final boolean ACTIVE = DIR != null;
    static final Path OUT = Path.of(ACTIVE ? DIR : ".");
    static final String[] SCENES = System.getProperty("cyanotype.demo.scenes", "house").split(",");
    static final int FRAMES = Integer.getInteger("cyanotype.demo.measure", 300);
    static final ExecutorService WRITER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "cyanotype-demo-writer");
        t.setDaemon(true);
        return t;
    });
    /** Grass surface of the demo world; things stand at G + 1. */
    static final int G = -10;

    interface Step {
        /** Runs every tick until it returns true. */
        boolean run(Minecraft mc);
    }

    static final List<Step> steps = new ArrayList<>();
    static int tick = -1, current = -1;
    static boolean done, stopped, built;
    static final AtomicInteger pending = new AtomicInteger();
    static long drainUntil;
    static final List<String> results = new ArrayList<>();
    static final List<String> perf = new ArrayList<>();
    static final List<String> shots = new ArrayList<>();

    // frame timing, filled by onFrame while a measurement is open
    static boolean measuring;
    static long lastFrameNs;
    static final List<Long> frameNs = new ArrayList<>();

    private Director() {
    }

    public static void onTick(Minecraft mc) {
        if (done) {
            if (!stopped && (pending.get() == 0 || System.currentTimeMillis() > drainUntil)) {
                stopped = true;
                System.out.println("[cydemo] done" + (pending.get() > 0 ? ", " + pending.get() + " frames not written" : ""));
                mc.stop();
            }
            return;
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        tick++;
        if (tick < 10) return;
        if (!built) {
            built = true;
            build();
        }
        if (current < 0 || steps.get(current).run(mc)) {
            current++;
            if (current >= steps.size()) {
                finish();
            }
        }
    }

    public static void onFrame(Minecraft mc) {
        if (!ACTIVE) return;
        long now = System.nanoTime();
        if (measuring) frameNs.add(now - lastFrameNs);
        lastFrameNs = now;
        if (pendingShot != null) {
            String name = pendingShot;
            pendingShot = null;
            Path out = OUT.resolve(name + ".png");
            pending.incrementAndGet();
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage img) -> WRITER.execute(() -> {
                try (img) {
                    Files.createDirectories(OUT);
                    img.writeToFile(out);
                } catch (Exception e) {
                    System.out.println("[cydemo] write failed " + out + ": " + e);
                } finally {
                    pending.decrementAndGet();
                }
            }));
        }
    }

    static String pendingShot;

    // ---- script helpers

    static void add(Step s) {
        steps.add(s);
    }

    static void act(Runnable r) {
        add(mc -> {
            r.run();
            return true;
        });
    }

    static void waitTicks(int n) {
        int[] left = {n};
        add(mc -> --left[0] <= 0);
    }

    /** Waits for a condition, failing the run's check after a timeout (the script goes on either way). */
    static void until(String what, int timeoutTicks, BooleanSupplier cond) {
        int[] t = {0};
        add(mc -> {
            if (cond.getAsBoolean()) {
                check(what, true, "after " + t[0] + " ticks");
                return true;
            }
            if (++t[0] >= timeoutTicks) {
                check(what, false, "not reached in " + timeoutTicks + " ticks");
                return true;
            }
            return false;
        });
    }

    static void cmd(String... commands) {
        add(mc -> {
            run(mc, commands);
            return true;
        });
    }

    static void run(Minecraft mc, String... commands) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            for (String c : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
        });
    }

    static void shot(String name) {
        int[] state = {0};
        add(mc -> {
            if (state[0] == 0) {
                pendingShot = name;
                shots.add(name);
                state[0] = 1;
                return false;
            }
            return pendingShot == null;
        });
    }

    static void camera(double x, double y, double z, float yaw, float pitch) {
        add(mc -> {
            run(mc, String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f %.1f", mc.player.getGameProfile().name(), x, y, z, yaw, pitch));
            return true;
        });
    }

    static void check(String name, boolean pass, String detail) {
        results.add(String.format("{\"name\":\"%s\",\"pass\":%b,\"detail\":\"%s\"}", name, pass, detail.replace("\"", "'")));
        System.out.println("[cydemo] " + (pass ? "PASS " : "FAIL ") + name + "  " + detail);
    }

    // ---- scenes

    static void build() {
        ModTestHook.audit();
        add(mc -> {
            String p = mc.player.getGameProfile().name();
            run(mc, "gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false", "gamerule spawn_monsters false",
                "gamerule send_command_feedback false", "time set 6000", "weather clear", "gamemode spectator " + p, "kill @e[type=!player]");
            hideHud(mc, true);
            // the lessons that play on first use would open over every other scene: only the lesson scenes switch them on
            io.github.profetgit.cyanotype.ui.Settings.get().lessons = false;
            mc.options.fov().set(Integer.getInteger("cyanotype.demo.fov", 70));
            return true;
        });
        waitTicks(40);
        for (String scene : SCENES) {
            switch (scene) {
                case "house" -> houseScene();
                case "shaderswap" -> ShaderScenes.swap();
                case "paste-perf" -> PasteScenes.perf();
                case "perf" -> perfScenes();
                case "commands" -> commandScene();
                case "place" -> PlaceScenes.place();
                case "handles" -> PlaceScenes.handles();
                case "safety" -> PlaceScenes.safety();
                case "hints" -> PlaceScenes.hints();
                case "verify" -> VerifyScenes.verify();
                case "verify-perf" -> VerifyScenes.perf();
                case "hud" -> VerifyScenes.hud();
                case "wheel" -> UiScenes.wheel();
                case "wheel-counts" -> UiScenes.wheelCounts();
                case "remove" -> UiScenes.remove();
                case "paste" -> PasteScenes.paste();
                case "undo" -> UiScenes.undo();
                case "layer-caps" -> UiScenes.layerCaps();
                case "shapes" -> UiScenes.shapes();
                case "library" -> UiScenes.library();
                case "community" -> CommunityScenes.community();
                case "save" -> SaveScenes.save();
                case "pick" -> PickScenes.pick();
                case "auto" -> AutoScenes.auto();
                case "nudge" -> NudgeScenes.nudge();
                case "mega" -> NudgeScenes.mega();
                case "carry" -> NudgeScenes.carry();
                case "materials" -> MaterialsScenes.materials();
                case "settings" -> SettingsScenes.settings();
                case "ponder-stills" -> PonderScenes.stills();
                case "ponder-flow" -> PonderScenes.flow();
                case "ponder-take" -> PonderTakes.takes();
                case "look" -> PlaceScenes.look();
                case "persist" -> PlaceScenes.persist();
                case "persist-leave" -> PlaceScenes.persistLeave();
                case "persist-return" -> PlaceScenes.persistReturn();
                default -> {
                    String s = scene;
                    act(() -> check("scene " + s, false, "unknown scene"));
                }
            }
        }
    }

    static final String DIM = "minecraft:overworld";
    static Placement house;

    static Placement locked(Placement p) {
        p.locked = true;
        return p;
    }

    /** Persistence restores the last run's placements at world join; scenes that count ghosts start without them. */
    static void clean() {
        act(() -> {
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
        });
        waitTicks(4);
    }

    static void houseScene() {
        clean();
        act(() -> {
            try {
                Blueprint bp = Samples.house();
                Path file = OUT.resolve("house.litematic");
                Files.createDirectories(OUT);
                LitematicWriter.write(bp, file);
                Blueprint back = LitematicReader.read(file);
                check("house/file round trip (with the connections blocks get in place)", same(io.github.profetgit.cyanotype.blueprint.ShapeFixer.fix(bp), back), bp.totalBlocks() + " blocks, " + Files.size(file) + " bytes on disk");
                // the sample house was made with plain panes and fences; once read they have the shape they have in place
                boolean pane = false, fence = false, paneBefore = false;
                for (io.github.profetgit.cyanotype.blueprint.PaletteEntry e : back.regions.get(0).palette) {
                    var st = e.state();
                    if (st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock && (st.getValue(net.minecraft.world.level.block.IronBarsBlock.EAST) || st.getValue(net.minecraft.world.level.block.IronBarsBlock.WEST)
                        || st.getValue(net.minecraft.world.level.block.IronBarsBlock.NORTH) || st.getValue(net.minecraft.world.level.block.IronBarsBlock.SOUTH))) pane = true;
                    if (st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock && (st.getValue(net.minecraft.world.level.block.FenceBlock.EAST) || st.getValue(net.minecraft.world.level.block.FenceBlock.WEST))) fence = true;
                }
                for (io.github.profetgit.cyanotype.blueprint.PaletteEntry e : bp.regions.get(0).palette) {
                    var st = e.state();
                    if (st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock && (st.getValue(net.minecraft.world.level.block.IronBarsBlock.EAST) || st.getValue(net.minecraft.world.level.block.IronBarsBlock.NORTH)
                        || st.getValue(net.minecraft.world.level.block.IronBarsBlock.WEST) || st.getValue(net.minecraft.world.level.block.IronBarsBlock.SOUTH))) paneBefore = true;
                }
                check("house/panes between blocks are panes, not posts", pane && !paneBefore, "connected panes after " + pane + ", before " + paneBefore);
                check("house/fences meet the fence beside them", fence, "connected fence " + fence);
                house = locked(new Placement("house", back, "cyanotype:house.litematic", DIM, new BlockPos(0, G + 1, 6), Orientation.NONE));
                Placements.add(house);
            } catch (IOException e) {
                check("house/file round trip", false, e.toString());
            }
        });
        until("house/ghost baked", 600, GhostRenderer::settled);
        act(() -> check("house/sections drawn", GhostRenderer.Stats.uploaded > 0, "sections " + GhostRenderer.Stats.sections + ", uploaded " + GhostRenderer.Stats.uploaded));
        // the player flies in spectator mode: a ring of views around the house, which stands at x 0..10, z 6..18
        double[][] views = {
            {5, G + 4, -10, 0, 12}, {-12, G + 6, 12, -90, 15}, {5, G + 14, 12, 0, 55}, {24, G + 3, 12, 90, 5}, {5, G + 3, 12, 180, 0},
        };
        shootViews("house", views);
        act(() -> {
            house.set(house.origin, new Orientation(Rotation.CLOCKWISE_90, Mirror.NONE));
        });
        until("house/rotated rebaked", 600, GhostRenderer::settled);
        shootViews("house_cw90", new double[][]{views[0], views[1], views[2]});
        act(() -> {
            house.set(house.origin, new Orientation(Rotation.NONE, Mirror.LEFT_RIGHT));
        });
        until("house/mirrored rebaked", 600, GhostRenderer::settled);
        shootViews("house_mirror", new double[][]{views[0], views[1]});
        act(() -> Placements.remove(house));
        waitTicks(4);
        act(() -> check("house/hidden", GhostRenderer.ghosts().isEmpty(), "ghosts left " + GhostRenderer.ghosts().size()));
    }

    /** The chat commands, run through the same handler the chat screen calls. */
    static void commandScene() {
        clean();
        double[][] views = {{5, G + 4, -10, 0, 12}, {5, G + 4, -10, 0, 12}};
        act(() -> DevCommands.run("/cyanotype sample"));
        waitTicks(10);
        act(() -> {
            DevCommands.run("/cyanotype place 0 " + (G + 1) + " 6");
            DevCommands.run("/cyanotype opacity 35");
        });
        camera(views[0][0], views[0][1], views[0][2], 0, 12);
        until("commands/placed", 600, GhostRenderer::settled);
        act(() -> check("commands/one ghost", GhostRenderer.ghosts().size() == 1, "ghosts " + GhostRenderer.ghosts().size()));
        waitTicks(10);
        shot("cmd_placed");
        act(() -> DevCommands.run("/cyanotype rotate"));
        until("commands/rotated", 600, GhostRenderer::settled);
        waitTicks(10);
        shot("cmd_rotated");
        act(() -> {
            DevCommands.run("/cyanotype mirror");
            DevCommands.run("/cyanotype move 4 " + (G + 1) + " 8");
            DevCommands.run("/cyanotype info");
        });
        until("commands/moved", 600, GhostRenderer::settled);
        act(() -> DevCommands.run("/cyanotype clear"));
        act(() -> check("commands/cleared", GhostRenderer.ghosts().isEmpty(), "ghosts " + GhostRenderer.ghosts().size()));
    }

    static void shootViews(String prefix, double[][] views) {
        for (int i = 0; i < views.length; i++) {
            double[] v = views[i];
            camera(v[0], v[1], v[2], (float) v[3], (float) v[4]);
            waitTicks(14);
            shot(prefix + "_" + i);
        }
    }

    static void perfScenes() {
        String[] kinds = System.getProperty("cyanotype.demo.perf", "solid:80,shell:160:2,noise:50,noise:100").split(",");
        for (String kind : kinds) {
            String[] p = kind.split(":");
            Blueprint[] bp = new Blueprint[1];
            Placement[] pl = new Placement[1];
            int side = Integer.parseInt(p[1]);
            act(() -> {
                bp[0] = switch (p[0]) {
                    case "solid" -> Samples.solid(side);
                    case "shell" -> Samples.shell(side, p.length > 2 ? Integer.parseInt(p[2]) : 1);
                    default -> Samples.noise(side, 0.5);
                };
                pl[0] = locked(new Placement(kind, bp[0], "cyanotype:" + kind + ".litematic", DIM, new BlockPos(-side / 2, G + 1, 20), Orientation.NONE));
                System.out.println("[cydemo] perf " + kind + ": " + bp[0].totalBlocks() + " blocks");
            });
            String label = kind.replace(':', '_');
            // baseline, ghost not shown
            camera(0, G + 1 + side * 0.4, 20 - side * 0.9, 0, 8);
            waitTicks(30);
            measure(label + " baseline");
            act(() -> {
                long t0 = System.nanoTime();
                Placements.add(pl[0]);
                GhostRenderer.Stats.bakeNanos.reset();
                GhostRenderer.Stats.bakedSections.reset();
                GhostRenderer.Stats.bytesUploaded.reset();
                bakeStart[0] = t0;
            });
            until(label + " baked", 20 * 600, GhostRenderer::settled);
            act(() -> {
                long ms = (System.nanoTime() - bakeStart[0]) / 1_000_000;
                perf.add(String.format(Locale.ROOT, "{\"build\":\"%s\",\"blocks\":%d,\"bakeWallMs\":%d,\"sections\":%d,\"bakedSections\":%d,\"bakeCpuMs\":%d,\"uploadedMiB\":%.1f}",
                    kind, bp[0].totalBlocks(), ms, GhostRenderer.Stats.sections, GhostRenderer.Stats.bakedSections.sum(), GhostRenderer.Stats.bakeNanos.sum() / 1_000_000, GhostRenderer.Stats.bytesUploaded.sum() / 1048576.0));
                check(label + " bake", true, ms + " ms wall, " + GhostRenderer.Stats.bakedSections.sum() + " sections, " + String.format(Locale.ROOT, "%.1f MiB", GhostRenderer.Stats.bytesUploaded.sum() / 1048576.0));
            });
            waitTicks(20);
            if (EDIT) act(() -> Placements.setMode(Placements.Mode.EDIT));
            waitTicks(10);
            measure(label + (EDIT ? " in view, editing" : " in view"));
            shot("perf_" + label);
            act(() -> Placements.setMode(Placements.Mode.IDLE));
            camera(0, G + 1 + side * 0.4, 20 - side * 0.9, 180, 8);
            waitTicks(30);
            measure(label + " out of view");
            act(() -> Placements.remove(pl[0]));
            waitTicks(20);
        }
    }

    static final long[] bakeStart = new long[1];
    static final boolean EDIT = Boolean.getBoolean("cyanotype.demo.perf.edit");

    /** Times FRAMES frames and records the average frame time and the ghost's own render-thread cost per frame. */
    static void measure(String label) {
        int[] phase = {0};
        long[] cpu0 = {0}, frames0 = {0};
        add(mc -> {
            if (phase[0] == 0) {
                frameNs.clear();
                cpu0[0] = GhostRenderer.Stats.cpuNanos;
                frames0[0] = GhostRenderer.Stats.frames;
                measuring = true;
                phase[0] = 1;
                return false;
            }
            if (frameNs.size() < FRAMES) return false;
            measuring = false;
            List<Long> sorted = new ArrayList<>(frameNs);
            sorted.sort(null);
            double avg = frameNs.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
            double p99 = sorted.get((int) (sorted.size() * 0.99)) / 1e6;
            double ghostMs = GhostRenderer.Stats.frames > frames0[0] ? (GhostRenderer.Stats.cpuNanos - cpu0[0]) / (double) (GhostRenderer.Stats.frames - frames0[0]) / 1e6 : 0;
            perf.add(String.format(Locale.ROOT, "{\"measure\":\"%s\",\"frames\":%d,\"avgFrameMs\":%.3f,\"p99FrameMs\":%.3f,\"ghostCpuMs\":%.3f,\"drawnSections\":%d,\"quadsDrawn\":%d}",
                label, frameNs.size(), avg, p99, ghostMs, GhostRenderer.Stats.drawn, GhostRenderer.Stats.quadsDrawn));
            check(label, true, String.format(Locale.ROOT, "avg %.2f ms (%.0f fps), p99 %.2f ms, ghost cpu %.3f ms/frame, %d sections / %d quads drawn",
                avg, 1000 / avg, p99, ghostMs, GhostRenderer.Stats.drawn, GhostRenderer.Stats.quadsDrawn));
            return true;
        });
    }

    static boolean same(Blueprint a, Blueprint b) {
        if (a.regions.size() != b.regions.size()) return false;
        for (int i = 0; i < a.regions.size(); i++) {
            Region x = a.regions.get(i), y = b.regions.get(i);
            if (x.sx != y.sx || x.sy != y.sy || x.sz != y.sz) return false;
            for (int j = 0; j < x.volume(); j++) {
                int lx = j % x.sx, lz = (j / x.sx) % x.sz, ly = j / (x.sx * x.sz);
                if (x.at(lx, ly, lz).state() != y.at(lx, ly, lz).state()) return false;
            }
        }
        return true;
    }

    static void finish() {
        done = true;
        try {
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("results.json"), "{\"loader\":\"fabric\",\"results\":[\n" + String.join(",\n", results) + "\n],\"perf\":[\n" + String.join(",\n", perf) + "\n]}\n");
        } catch (IOException e) {
            System.out.println("[cydemo] results write failed: " + e);
        }
        drainUntil = System.currentTimeMillis() + 60_000;
    }

    static void hideHud(Minecraft mc, boolean hidden) {
        try {
            Field f = mc.gui.hud.getClass().getDeclaredField("isHidden");
            f.setAccessible(true);
            f.setBoolean(mc.gui.hud, hidden);
        } catch (ReflectiveOperationException e) {
            System.out.println("[cydemo] cannot hide HUD: " + e);
        }
    }
}
