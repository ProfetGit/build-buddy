package io.github.profetgit.cyanotype.ponder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.Region;
import io.github.profetgit.cyanotype.demo.Director;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Keys;
import io.github.profetgit.cyanotype.interaction.SelectionBox;
import io.github.profetgit.cyanotype.interaction.Selecting;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.verify.Verifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The dev recorder (PRD 7.12a): watches the real client at the game's own rate (20 times a second) while a take is performed and
 * writes what it saw as a raw log, so a lesson shows what the mod really does. It records, in coordinates relative to a stage box:
 * the blocks of the box at the start and every change after, every placement as a ghost (its blueprint grid and where it is drawn,
 * turned and mirrored at each moment), the block the crosshair is on, the mouse buttons, scrolling and the mod's keys with the
 * modifiers, the verifier's progress, the box of the Save tool, and named marks. {@code dev/ponder/recording.py} turns the log into
 * lesson tracks; captions, camera and overlays are written by hand on top, anchored to the marks.
 *
 * <p>Off unless a take is running ({@link #active}): the hooks in the mod cost one static boolean.
 */
public final class PonderRecorder {
    public static volatile boolean active;

    private static final Gson GSON = new GsonBuilder().create();
    private static String id = "take";
    private static int[] origin = new int[3], size = new int[3];
    private static int ticks;
    private static final Map<String, String> palette = new LinkedHashMap<>();
    private static final Map<String, String> keyOf = new LinkedHashMap<>();
    private static final List<String> worldRows = new ArrayList<>();
    private static JsonArray world = new JsonArray(), blocks = new JsonArray(), aim = new JsonArray(), input = new JsonArray(), scroll = new JsonArray(), progress = new JsonArray(), box = new JsonArray();
    private static final JsonObject marks = new JsonObject();
    private static final Map<String, Ghost> ghosts = new LinkedHashMap<>();
    private static final Map<String, Boolean> down = new LinkedHashMap<>();
    private static double[] lastAim;
    private static double lastProgress = -1;
    private static int[] lastBox;
    private static final List<Long> pendingBlocks = new ArrayList<>();

    private PonderRecorder() {
    }

    /** What is recorded about one placement: its blueprint as a grid of palette keys, and its frames. */
    private static final class Ghost {
        final JsonObject grid;
        final JsonArray frames = new JsonArray();
        double[] last;

        Ghost(JsonObject grid) {
            this.grid = grid;
        }
    }

    // ---- control

    /** Where recordings go: the demo's output folder when a demo runs, else the game folder (the command that starts a take needs -Dcyanotype.dev=true). */
    public static Path dir() {
        return Director.ACTIVE ? Path.of(System.getProperty("cyanotype.demo")).resolve("rec") : FabricLoader.getInstance().getGameDir().resolve("ponder-rec");
    }

    /** Starts a take. The stage box is where the lesson happens: blocks inside it are recorded; give it with its size, or null for a box of 16 round the player. */
    public static void start(Minecraft mc, String takeId, int @Nullable [] box0, int @Nullable [] boxSize) {
        id = takeId;
        if (box0 == null) {
            BlockPos at = mc.player.blockPosition();
            origin = new int[]{at.getX() - 8, at.getY() - 2, at.getZ() - 8};
            size = new int[]{16, 12, 16};
        } else {
            origin = box0.clone();
            size = boxSize.clone();
        }
        ticks = 0;
        palette.clear();
        keyOf.clear();
        world = new JsonArray();
        blocks = new JsonArray();
        aim = new JsonArray();
        input = new JsonArray();
        scroll = new JsonArray();
        progress = new JsonArray();
        box = new JsonArray();
        for (String k : new ArrayList<>(marks.keySet())) marks.remove(k);
        ghosts.clear();
        down.clear();
        lastAim = null;
        lastProgress = -1;
        lastBox = null;
        pendingBlocks.clear();
        snapshotWorld(mc);
        active = true;
        Cyanotype.LOG.info("Recording '{}' at {},{},{} size {}x{}x{}", id, origin[0], origin[1], origin[2], size[0], size[1], size[2]);
    }

    /** Names this moment: lesson tracks written by hand hang on marks, so a new take keeps them in place. */
    public static void mark(String name) {
        if (active) marks.addProperty(name, round(ticks / 20.0));
    }

    /** Ends the take and writes the log; returns where. */
    public static @Nullable Path stop(Minecraft mc) {
        if (!active) return null;
        tick(mc);
        active = false;
        for (Long p : pendingBlocks) block(mc, p);
        pendingBlocks.clear();
        JsonObject out = new JsonObject();
        out.addProperty("format", 1);
        out.addProperty("kind", "recording");
        out.addProperty("id", id);
        out.addProperty("rate", 20);
        out.addProperty("duration", round(ticks / 20.0));
        JsonObject stage = new JsonObject();
        stage.add("origin", GSON.toJsonTree(origin));
        stage.add("size", GSON.toJsonTree(size));
        out.add("stage", stage);
        JsonObject pal = new JsonObject();
        for (Map.Entry<String, String> e : palette.entrySet()) pal.addProperty(e.getKey(), e.getValue());
        out.add("palette", pal);
        out.add("world", world);
        out.add("blocks", blocks);
        JsonObject gs = new JsonObject();
        for (Map.Entry<String, Ghost> e : ghosts.entrySet()) {
            JsonObject g = new JsonObject();
            g.add("grid", e.getValue().grid);
            g.add("frames", e.getValue().frames);
            gs.add(e.getKey(), g);
        }
        out.add("ghosts", gs);
        out.add("aim", aim);
        out.add("input", input);
        out.add("scroll", scroll);
        out.add("progress", progress);
        out.add("box", box);
        out.add("marks", marks);
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            Path file = dir.resolve(id + ".rec.json");
            Files.writeString(file, GSON.toJson(out));
            Cyanotype.LOG.info("Recorded {} ticks into {}", ticks, file);
            return file;
        } catch (IOException e) {
            Cyanotype.LOG.error("Cannot write the recording", e);
            return null;
        }
    }

    public static int ticks() {
        return ticks;
    }

    // ---- hooks

    /** Called once per client tick while recording. */
    public static void tick(Minecraft mc) {
        if (!active || mc.level == null || mc.player == null) return;
        double t = ticks / 20.0;
        for (Long p : pendingBlocks) block(mc, p);
        pendingBlocks.clear();
        sampleGhosts(t);
        sampleAim(mc, t);
        sampleInput(mc, t);
        sampleProgress(t);
        sampleBox(t);
        ticks++;
    }

    /** A block of the world changed (any, the hook does not know the stage): noted and looked at on the next tick, when its state is final. */
    public static void onBlock(long pos) {
        if (!active) return;
        BlockPos p = BlockPos.of(pos);
        if (inside(p.getX(), p.getY(), p.getZ())) pendingBlocks.add(pos);
    }

    public static void onScroll(double amount) {
        if (!active) return;
        JsonArray e = new JsonArray();
        e.add(round(ticks / 20.0));
        e.add(amount);
        scroll.add(e);
    }

    // ---- what is recorded

    private static boolean inside(int x, int y, int z) {
        return x >= origin[0] && y >= origin[1] && z >= origin[2] && x < origin[0] + size[0] && y < origin[1] + size[1] && z < origin[2] + size[2];
    }

    /** One character for each block state the take meets (the lesson format wants single characters). */
    private static final String KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#$%&*+=?@";

    private static String keyFor(BlockState s) {
        if (s.isAir()) return ".";
        String spec = spec(s);
        String have = keyOf.get(spec);
        if (have != null) return have;
        if (keyOf.size() >= KEYS.length()) throw new IllegalStateException("a take meets more than " + KEYS.length() + " different blocks");
        String key = String.valueOf(KEYS.charAt(keyOf.size()));
        keyOf.put(spec, key);
        palette.put(key, spec);
        return key;
    }

    /** "minecraft:oak_stairs[facing=east,half=bottom]": every property written out, in the order the game lists them. */
    static String spec(BlockState s) {
        CompoundTag tag = NbtUtils.writeBlockState(s);
        StringBuilder sb = new StringBuilder(tag.getStringOr("id", tag.getStringOr("Name", "minecraft:air")));
        CompoundTag props = tag.getCompoundOrEmpty("properties");
        if (!props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (String k : props.keySet()) {
                if (!first) sb.append(',');
                sb.append(k).append('=').append(props.getStringOr(k, ""));
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static void snapshotWorld(Minecraft mc) {
        for (int y = 0; y < size[1]; y++) {
            JsonArray layer = new JsonArray();
            for (int z = 0; z < size[2]; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < size[0]; x++) row.append(keyFor(mc.level.getBlockState(new BlockPos(origin[0] + x, origin[1] + y, origin[2] + z))));
                layer.add(row.toString());
            }
            world.add(layer);
        }
    }

    private static void block(Minecraft mc, long pos) {
        BlockPos p = BlockPos.of(pos);
        JsonArray e = new JsonArray();
        e.add(round(Math.max(0, ticks - 1) / 20.0));
        e.add(p.getX() - origin[0]);
        e.add(p.getY() - origin[1]);
        e.add(p.getZ() - origin[2]);
        e.add(keyFor(mc.level.getBlockState(p)));
        blocks.add(e);
    }

    private static void sampleGhosts(double t) {
        for (Placement p : Placements.all()) {
            Blueprint bp = p.blueprint;
            if (bp == null || !p.ready()) continue;
            Ghost g = ghosts.computeIfAbsent(p.name, n -> new Ghost(grid(bp)));
            int rot = switch (p.orientation.rotation()) {
                case NONE -> 0;
                case CLOCKWISE_90 -> 1;
                case CLOCKWISE_180 -> 2;
                case COUNTERCLOCKWISE_90 -> 3;
            };
            int mirror = switch (p.orientation.mirror()) {
                case NONE -> 0;
                case FRONT_BACK -> 1;
                case LEFT_RIGHT -> 2;
            };
            double[] f = {p.vx - origin[0], GhostRenderer.visualY(p) - origin[1], p.vz - origin[2], rot, mirror, p.locked ? 1 : 0, p.visible && !GhostRenderer.hidden ? 1 : 0, p.opacity,
                p.layerLo, p.layerHi, Placements.active() == p ? 1 : 0};
            if (g.last != null && same(g.last, f)) continue;
            g.last = f;
            JsonArray a = new JsonArray();
            a.add(round(t));
            for (double v : f) a.add(round(v));
            g.frames.add(a);
        }
    }

    /** A blueprint as layers of rows of palette keys, as it was saved (not turned). */
    private static JsonObject grid(Blueprint bp) {
        int sx = bp.sizeX, sy = bp.sizeY, sz = bp.sizeZ;
        char[][][] cells = new char[sy][sz][sx];
        for (char[][] l : cells) for (char[] r : l) java.util.Arrays.fill(r, '.');
        for (Region r : bp.regions) {
            String[] keys = new String[r.palette.length];
            for (int i = 0; i < keys.length; i++) {
                PaletteEntry e = r.palette[i];
                keys[i] = e.isAir() ? "." : keyFor(e.state());
            }
            for (int y = 0; y < r.sy; y++) {
                for (int z = 0; z < r.sz; z++) {
                    for (int x = 0; x < r.sx; x++) {
                        int pi = r.blocks[(y * r.sz + z) * r.sx + x] & 0xFFFF;
                        cells[r.y - bp.minY + y][r.z - bp.minZ + z][r.x - bp.minX + x] = keys[pi].charAt(0);
                    }
                }
            }
        }
        JsonArray layers = new JsonArray();
        for (char[][] l : cells) {
            JsonArray rows = new JsonArray();
            for (char[] r : l) rows.add(new String(r));
            layers.add(rows);
        }
        JsonObject o = new JsonObject();
        o.add("layers", layers);
        o.add("size", GSON.toJsonTree(new int[]{sx, sy, sz}));
        return o;
    }

    private static void sampleAim(Minecraft mc, double t) {
        Vec3 eye = mc.player.getEyePosition(), look = mc.player.getLookAngle();
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(64)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        Vec3 l = hit.getLocation();
        double[] a = {l.x - origin[0], l.y - origin[1], l.z - origin[2], hit.getDirection().ordinal()};
        if (lastAim != null && same(lastAim, a)) return;
        lastAim = a;
        JsonArray e = new JsonArray();
        e.add(round(t));
        for (double v : a) e.add(round(v));
        aim.add(e);
    }

    private static void sampleInput(Minecraft mc, double t) {
        edge(t, "attack", mc.options.keyAttack.isDown());
        edge(t, "use", mc.options.keyUse.isDown());
        edge(t, "shift", Interaction.shiftDown(mc));
        edge(t, "ctrl", Interaction.ctrlDown(mc));
        for (KeyMapping k : new KeyMapping[]{Keys.MAIN, Keys.TOGGLE, Keys.MIRROR, Keys.PASTE, Keys.UNDO, Keys.REDO, Keys.REMOVE, Keys.HELP}) edge(t, k.getName().replace("key.cyanotype.", ""), k.isDown());
    }

    private static void edge(double t, String name, boolean now) {
        Boolean was = down.get(name);
        if (was != null && was == now) return;
        if (was == null && !now) {
            down.put(name, false);
            return;
        }
        down.put(name, now);
        JsonArray e = new JsonArray();
        e.add(round(t));
        e.add(name);
        e.add(now ? 1 : 0);
        input.add(e);
    }

    private static void sampleProgress(double t) {
        Placement p = Placements.active();
        if (p == null) return;
        Verifier v = GhostRenderer.verifierOf(p);
        if (v == null) return;
        double pr = v.progress();
        if (Math.abs(pr - lastProgress) < 0.004) return;
        lastProgress = pr;
        JsonArray e = new JsonArray();
        e.add(round(t));
        e.add(round(pr));
        progress.add(e);
    }

    private static void sampleBox(double t) {
        SelectionBox b = Selecting.box();
        int[] now = b == null ? null : new int[]{b.x0() - origin[0], b.y0() - origin[1], b.z0() - origin[2], b.x1() - origin[0] + 1, b.y1() - origin[1] + 1, b.z1() - origin[2] + 1};
        if (java.util.Arrays.equals(now, lastBox)) return;
        lastBox = now;
        JsonArray e = new JsonArray();
        e.add(round(t));
        if (now != null) for (int v : now) e.add(v);
        box.add(e);
    }

    private static boolean same(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 1e-4) return false;
        return true;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
