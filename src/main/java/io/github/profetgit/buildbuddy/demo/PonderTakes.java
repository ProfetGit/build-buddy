package io.github.profetgit.buildbuddy.demo;

import static io.github.profetgit.buildbuddy.demo.Director.G;
import static io.github.profetgit.buildbuddy.demo.Director.act;
import static io.github.profetgit.buildbuddy.demo.Director.check;
import static io.github.profetgit.buildbuddy.demo.Director.waitTicks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.profetgit.buildbuddy.interaction.Interaction;
import io.github.profetgit.buildbuddy.interaction.Keys;
import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.placement.Orientation;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import io.github.profetgit.buildbuddy.ponder.Lessons;
import io.github.profetgit.buildbuddy.ponder.PonderRecorder;
import io.github.profetgit.buildbuddy.ponder.Scene;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The takes: stories performed in the real client through the real input paths while the recorder watches (PonderRecorder), so a
 * lesson can show what the mod does. Each take's raw log goes to {@code <demo out>/rec/<id>.rec.json}; {@code dev/ponder/recording.py}
 * turns it into lesson tracks. The stage of a take is a box of the flat demo world whose ground surface is the lesson's y = 2.
 */
final class PonderTakes {
    private PonderTakes() {
    }

    /** The stage box: lesson (0, 0, 0) is this world block; the grass top of the demo world is lesson y = 2. */
    static final int X0 = 24, Y0 = G - 1, Z0 = 24;

    /** The scenes named in the property (default: all takes). */
    static void takes() {
        String which = System.getProperty("buildbuddy.demo.take", "place");
        for (String t : which.split(",")) {
            switch (t) {
                case "place" -> place();
                case "layers" -> layers();
                case "edit" -> edit();
                default -> act(() -> check("take " + t, false, "unknown take"));
            }
        }
    }

    /** The player's position for the crosshair to be on the stage point (x, z) when looking straight down. */
    private static double[] above(double x, double z) {
        return new double[]{X0 + x, G + 1 + 6, Z0 + z};
    }

    private static void fly(double[] at) {
        Director.add(mc -> {
            Director.run(mc, String.format(java.util.Locale.ROOT, "tp %s %.3f %.3f %.3f 0 90", mc.player.getGameProfile().name(), at[0], at[1], at[2]));
            return true;
        });
    }

    /** Moves the player (and with it the crosshair) from one point to another over some ticks, easing in and out. */
    private static void glide(double[] from, double[] to, int ticks) {
        int[] i = {0};
        Director.add(mc -> {
            double k = Math.min(1, (double) i[0] / ticks);
            double e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
            Director.run(mc, String.format(java.util.Locale.ROOT, "tp %s %.3f %.3f %.3f 0 90", mc.player.getGameProfile().name(), from[0] + (to[0] - from[0]) * e, from[1] + (to[1] - from[1]) * e, from[2] + (to[2] - from[2]) * e));
            return ++i[0] > ticks;
        });
    }

    private static void mark(String name) {
        act(() -> PonderRecorder.mark(name));
    }


    /** A locked placement of the lesson's cottage in the stage, at a corner (lesson coordinates), drawn and checked before the take begins. */
    private static void put(Scene scene, int x, int z, Placement[] out) {
        act(() -> {
            out[0] = Director.locked(new Placement("Cottage", Samples.fromGroup(scene, "ghost", "Cottage"), "buildbuddy:cottage", Director.DIM, new BlockPos(X0 + x, G + 1, Z0 + z), Orientation.NONE));
            Placements.add(out[0]);
        });
        Director.until("take: the cottage is drawn and checked", 600, () -> GhostRenderer.verifierOf(out[0]) != null && GhostRenderer.settled() && GhostRenderer.drawn(out[0]));
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void scroll(double amount, boolean shift) {
        act(() -> {
            Interaction.testModifiers = shift ? 1 : -1;
            PlaceScenes.scroll(mc(), amount);
            Interaction.testModifiers = -1;
        });
    }

    /** Checks and writes the log of a take: the file is there and holds ghost frames. */
    private static JsonObject finish(String id, Placement[] made) {
        JsonObject[] out = new JsonObject[1];
        act(() -> {
            Path file = PonderRecorder.stop(mc());
            check("take " + id + ": the log was written", file != null && Files.isRegularFile(file), String.valueOf(file));
            try {
                if (file != null) out[0] = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            } catch (Exception e) {
                check("take " + id + ": the log reads back", false, e.toString());
            }
            if (out[0] != null) {
                JsonObject g = out[0].getAsJsonObject("ghosts").getAsJsonObject("Cottage");
                check("take " + id + ": the placement was followed", g != null && g.getAsJsonArray("frames").size() >= 5, g == null ? "no ghost" : g.getAsJsonArray("frames").size() + " frames");
            }
            if (made[0] != null) Placements.remove(made[0]);
            Placements.setMode(Placements.Mode.IDLE);
        });
        return out[0];
    }

    /**
     * The layers lesson: the tool starts on the lowest layer, scroll moves the window up and down, Shift and scroll makes it thicker and
     * thinner, a click ends the tool (the window stays) and in the tool a right click shows every layer.
     */
    static void layers() {
        Director.clean();
        Scene scene = Lessons.scene("layers");
        Placement[] made = new Placement[1];
        put(scene, 3, 3, made);
        fly(above(5.5, 14));
        waitTicks(10);
        act(() -> {
            PonderRecorder.start(mc(), "layers", new int[]{X0, Y0, Z0}, new int[]{11, 9, 11});
            PonderRecorder.mark("start");
        });
        waitTicks(32);
        mark("tool");
        act(() -> Interaction.startLayers(mc()));
        waitTicks(20);
        for (int i = 1; i <= 4; i++) {
            mark("up" + i);
            scroll(1, false);
            waitTicks(16);
        }
        waitTicks(4);
        mark("down1");
        scroll(-1, false);
        waitTicks(28);
        mark("thick1");
        scroll(1, true);
        waitTicks(16);
        mark("thick2");
        scroll(1, true);
        waitTicks(20);
        mark("thin1");
        scroll(-1, true);
        waitTicks(20);
        mark("down2");
        scroll(-1, false);
        waitTicks(30);
        mark("click");
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(3);
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(32);
        mark("again");
        act(() -> Interaction.startLayers(mc()));
        waitTicks(12);
        mark("right");
        act(() -> PlaceScenes.tap(mc().options.keyUse));
        waitTicks(30);
        mark("done");
        waitTicks(6);
        JsonObject o = finish("layers", made);
        act(() -> {
            if (o == null) return;
            JsonArray frames = o.getAsJsonObject("ghosts").getAsJsonObject("Cottage").getAsJsonArray("frames");
            // [t, x, y, z, rot, mirror, locked, visible, opacity, layerLo, layerHi, active]
            JsonArray last = frames.get(frames.size() - 1).getAsJsonArray();
            check("take layers: it ends with every layer shown again", last.get(9).getAsInt() == -1 && last.get(10).getAsInt() == -1, "window " + last.get(9) + " to " + last.get(10));
            boolean sawThick = false;
            for (int i = 0; i < frames.size(); i++) sawThick |= frames.get(i).getAsJsonArray().get(9).getAsInt() == 3 && frames.get(i).getAsJsonArray().get(10).getAsInt() == 5;
            check("take layers: it was seen three layers thick (4 to 6)", sawThick, "frames " + frames.size());
        });
    }

    /**
     * The edit lesson: the east arrow dragged three blocks, the ring dragged a quarter turn, the build carried by its body, and three undos.
     * The stage is the lesson's, the cottage stands where the lesson puts it.
     */
    static void edit() {
        Director.clean();
        Scene scene = Lessons.scene("edit");
        Placement[] made = new Placement[1];
        put(scene, 3, 4, made);
        // stand off to the south and high, as the handles scene does
        Director.add(m -> {
            Director.run(m, String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f 0 18", m.player.getGameProfile().name(), X0 + 6.0, G + 9.0, Z0 + 22.0));
            return true;
        });
        waitTicks(12);
        act(() -> {
            Placements.setMode(Placements.Mode.EDIT);
            PonderRecorder.start(mc(), "edit", new int[]{X0, Y0, Z0}, new int[]{12, 9, 12});
            PonderRecorder.mark("start");
        });
        waitTicks(30);
        // the east arrow: three blocks
        mark("grab_east");
        aimAt("move+x");
        waitTicks(5);
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(3);
        Vec3[] grab = new Vec3[1];
        act(() -> grab[0] = Interaction.handleAnchor("move+x"));
        mark("drag_east");
        for (int k = 1; k <= 3; k++) {
            int step = k;
            act(() -> PlaceScenes.aim(mc(), grab[0].x + step, grab[0].y, grab[0].z));
            waitTicks(8);
        }
        waitTicks(6);
        mark("drop_east");
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(30);
        // the ring: a quarter turn clockwise seen from above
        mark("grab_ring");
        aimAt("ring");
        waitTicks(5);
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(3);
        double[][] ring = new double[1][];
        act(() -> ring[0] = Interaction.ringGeometry());
        mark("drag_ring");
        for (int deg = 10; deg <= 100; deg += 10) {
            int d = deg;
            act(() -> {
                double a = Math.toRadians(d);
                double[] r = ring[0];
                PlaceScenes.aim(mc(), r[0] + Math.cos(a) * r[3], r[1], r[2] + Math.sin(a) * r[3]);
            });
            waitTicks(3);
        }
        waitTicks(6);
        mark("drop_ring");
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(30);
        // carry it by its body: the point under the crosshair stays under it while the crosshair goes five blocks west and two south
        mark("grab_body");
        Vec3[] body = new Vec3[1];
        act(() -> {
            Placement p = made[0];
            // low on the front face and to one side: the south arrow stands in the middle of it
            body[0] = new Vec3(p.origin.getX() + 1.0, p.origin.getY() + 1.5, p.origin.getZ() + p.sizeZ() + 0.1);
            PlaceScenes.aim(mc(), body[0].x, body[0].y, body[0].z);
        });
        waitTicks(6);
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(5);
        mark("carry");
        // the grabbed point follows the crosshair on the plane it was taken at (its own height), so aim along that plane
        for (int k = 1; k <= 10; k++) {
            int step = k;
            act(() -> PlaceScenes.aim(mc(), body[0].x - 5.0 * step / 10, body[0].y, body[0].z + 2.0 * step / 10));
            waitTicks(4);
        }
        waitTicks(8);
        mark("drop_body");
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(30);
        for (int i = 1; i <= 3; i++) {
            mark("undo" + i);
            PlaceScenes.ctrlTap(Keys.UNDO, false);
            waitTicks(18);
        }
        mark("done");
        waitTicks(10);
        JsonObject o = finish("edit", made);
        act(() -> {
            if (o == null) return;
            JsonArray frames = o.getAsJsonObject("ghosts").getAsJsonObject("Cottage").getAsJsonArray("frames");
            JsonArray first = frames.get(0).getAsJsonArray(), last = frames.get(frames.size() - 1).getAsJsonArray();
            check("take edit: undoing three times brings it back to the start", Math.abs(first.get(1).getAsDouble() - last.get(1).getAsDouble()) < 0.01 && Math.abs(first.get(3).getAsDouble() - last.get(3).getAsDouble()) < 0.01
                && first.get(4).getAsInt() == last.get(4).getAsInt(), "first " + first + ", last " + last);
            boolean sawTurn = false, sawEast = false;
            for (int i = 0; i < frames.size(); i++) {
                JsonArray f = frames.get(i).getAsJsonArray();
                sawTurn |= f.get(4).getAsInt() == 1;
                sawEast |= Math.abs(f.get(1).getAsDouble() - (first.get(1).getAsDouble() + 3)) < 0.01;
            }
            check("take edit: it was dragged three blocks east and turned a quarter", sawTurn && sawEast, "turn " + sawTurn + ", east " + sawEast);
        });
    }

    private static void aimAt(String handle) {
        act(() -> {
            Vec3 a = Interaction.handleAnchor(handle);
            if (a == null) {
                check("take: the handle " + handle + " exists", false, "no such handle");
                return;
            }
            PlaceScenes.aim(mc(), a.x, a.y, a.z);
        });
    }

    /**
     * The place lesson: a cottage ghost appears, follows the crosshair across the ground, is turned twice, lifted a block and let
     * down, mirrored, and locked with a click. Timings follow the lesson's own timeline (see dev/ponder/scenes.py, lesson place).
     */
    static void place() {
        Director.clean();
        Scene scene = Lessons.scene("place");
        Placement[] made = new Placement[1];
        double[] a = above(3.5, 8.5), b = above(7.5, 6.5);
        fly(a);
        waitTicks(10);
        act(() -> {
            check("take place: the lesson is there", scene != null, String.valueOf(scene));
            PonderRecorder.start(Minecraft.getInstance(), "place", new int[]{X0, Y0, Z0}, new int[]{11, 9, 11});
            PonderRecorder.mark("start");
        });
        waitTicks(10);
        act(() -> {
            Interaction.startPlacing("Cottage", Samples.fromGroup(scene, "ghost", "Cottage"), "buildbuddy:cottage");
            made[0] = Placements.active();
            PonderRecorder.mark("appear");
        });
        waitTicks(14);
        mark("glide_start");
        glide(a, b, 36);
        mark("glide_end");
        waitTicks(12);
        mark("turn1");
        act(() -> PlaceScenes.scroll(Minecraft.getInstance(), 1));
        waitTicks(34);
        mark("turn2");
        act(() -> PlaceScenes.scroll(Minecraft.getInstance(), 1));
        waitTicks(40);
        mark("lift_up");
        act(() -> {
            Interaction.testModifiers = 1;
            PlaceScenes.scroll(Minecraft.getInstance(), 1);
            Interaction.testModifiers = -1;
        });
        waitTicks(24);
        mark("lift_down");
        act(() -> {
            Interaction.testModifiers = 1;
            PlaceScenes.scroll(Minecraft.getInstance(), -1);
            Interaction.testModifiers = -1;
        });
        waitTicks(24);
        mark("mirror");
        act(() -> PlaceScenes.tap(Keys.MIRROR));
        waitTicks(28);
        mark("lock");
        act(() -> PlaceScenes.hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> PlaceScenes.release(Minecraft.getInstance().options.keyAttack));
        waitTicks(40);
        mark("done");
        waitTicks(10);
        act(() -> {
            Path file = PonderRecorder.stop(Minecraft.getInstance());
            check("take place: the log was written", file != null && Files.isRegularFile(file), String.valueOf(file));
            if (file == null) return;
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonObject g = o.getAsJsonObject("ghosts").getAsJsonObject("Cottage");
                JsonArray frames = g.getAsJsonArray("frames");
                check("take place: the ghost was followed frame by frame", frames.size() >= 30, frames.size() + " frames");
                JsonArray last = frames.get(frames.size() - 1).getAsJsonArray();
                // [t, x, y, z, rot, mirror, locked, visible, opacity, layerLo, layerHi, active]
                check("take place: it ends turned twice, mirrored and locked", last.get(4).getAsInt() == 2 && last.get(5).getAsInt() != 0 && last.get(6).getAsInt() == 1,
                    "rot " + last.get(4) + ", mirror " + last.get(5) + ", locked " + last.get(6));
                check("take place: it ends on the ground of the stage (y = 2)", Math.abs(last.get(2).getAsDouble() - 2.0) < 0.02, "y " + last.get(2));
                check("take place: the marks are there", o.getAsJsonObject("marks").size() >= 9, o.getAsJsonObject("marks").keySet().toString());
                check("take place: the scroll was heard four times", o.getAsJsonArray("scroll").size() == 4, o.getAsJsonArray("scroll").size() + " notches");
                check("take place: the crosshair was followed", o.getAsJsonArray("aim").size() >= 20, o.getAsJsonArray("aim").size() + " points");
            } catch (Exception e) {
                check("take place: the log reads back", false, e.toString());
            }
            if (made[0] != null) Placements.remove(made[0]);
        });
    }
}
