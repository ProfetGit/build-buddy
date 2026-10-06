package io.github.profetgit.cyanotype.interaction;

import io.github.profetgit.cyanotype.pick.FaceMesher;
import io.github.profetgit.cyanotype.pick.Kind;
import io.github.profetgit.cyanotype.pick.PickSet;
import io.github.profetgit.cyanotype.pick.Picker;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.placement.Placements.Mode;
import io.github.profetgit.cyanotype.ui.Chips;
import io.github.profetgit.cyanotype.ui.SaveScreen;
import io.github.profetgit.cyanotype.ui.Sfx;
import io.github.profetgit.cyanotype.ui.Ui;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Util;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Smart Pick tool (PRD 7.11): click any part of a build and the whole build lights up; click it again to save it.
 * Scroll changes how wide a gap the picker jumps, Shift+click adds another part, Ctrl+click takes a part out (Alt+click
 * would be taken by the window manager on KDE), right click starts over. The work is {@link PickSet}'s, a few
 * milliseconds a tick; this class is the input, the hints and the drawing. Static state like {@link Selecting}.
 */
public final class Picking {
    public enum Stage {
        /** Nothing picked: the crosshair shows what a click would pick. */
        AIM,
        /** A pick is on show (it may be working on a better one behind it). */
        RESULT
    }

    private static final double RANGE = 64;
    private static final int CYAN = 0xFF7FE3FF, AMBER = 0xFFFFC857;
    private static final int DRAW_CAP = 2500, MESH_CAP = 8000, OUTLINE_UNDER = 1500, SLICE_NS = 3_000_000;

    private static PickSet set;
    private static Stage stage = Stage.AIM;
    private static Vec3 camPos = Vec3.ZERO, camLook = new Vec3(0, 0, 1);
    private static double scrollAcc;

    /**
     * How much Ctrl+click takes out and Shift+click puts in: a whole part (what the picker found), or a cube of 5, 3 or
     * 1 block around the block aimed at, for the precise edits a part is too coarse for. Ctrl+scroll or Shift+scroll changes it.
     */
    static final String[] DETAIL_NAMES = {"a part", "5x5x5 blocks", "3x3x3 blocks", "one block"};
    static final int[] DETAIL_HALF = {-1, 2, 1, 0};
    private static int detail;

    // a click on terrain asks for a second click on the same block before it picks it
    private static long forceCell = Long.MIN_VALUE;

    // what the crosshair is on, worked out when it moves to another block
    private static long hoverCell = Long.MIN_VALUE;
    private static Kind hoverKind = Kind.AIR;
    private static long hoverHop = Long.MIN_VALUE;

    // the drawing of the pick, made on a worker thread whenever it changes
    private static volatile Mesh mesh;
    private static int meshVersion = -1;
    private static boolean meshing;
    private static int epoch;
    private static float[] sortedDist;
    private static float thresholdSq = Float.MAX_VALUE;
    private static int thresholdFrames;

    private record Mesh(int version, FaceMesher.Quads quads, int[] bounds, int extraParts) {
    }

    private Picking() {
    }

    public static boolean active() {
        return Placements.mode() == Mode.PICK;
    }

    public static Stage stage() {
        return stage;
    }

    /** The picked cells, or empty. */
    public static LongOpenHashSet picked() {
        return set == null ? new LongOpenHashSet() : set.picked();
    }

    public static boolean working() {
        return set != null && set.working();
    }

    /** Which cut size Ctrl+click and Shift+click use (0 = a whole part), for the demo. */
    public static int detail() {
        return detail;
    }

    public static int reach() {
        return set == null ? Picker.DEFAULT_REACH : set.reachWanted();
    }

    public static PickSet pickSet() {
        return set;
    }

    public static void start(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        Interaction.reveal();
        Interaction.cancelPlacing();
        Interaction.endDragSafely();
        Selecting.reset();
        reset();
        set = new PickSet(new LevelSource(mc.level), Picker.DEFAULT_LIMIT);
        Placements.setMode(Mode.PICK);
        Sfx.play(Sfx.OPEN);
    }

    public static void cancel(Minecraft mc) {
        reset();
        Placements.setMode(Mode.IDLE);
        Sfx.play(Sfx.CLOSE);
        Interaction.say(mc, "Smart Pick cancelled");
    }

    /** Leaves the tool after a save. */
    public static void finish() {
        reset();
        Placements.setMode(Mode.IDLE);
    }

    public static void reset() {
        if (set != null) set.clear();
        set = null;
        stage = Stage.AIM;
        scrollAcc = 0;
        detail = 0;
        forceCell = Long.MIN_VALUE;
        hoverCell = Long.MIN_VALUE;
        mesh = null;
        meshVersion = -1;
        meshing = false;
        epoch++;
        thresholdSq = Float.MAX_VALUE;
    }

    // ---- input

    private static @Nullable BlockPos aimCell(Minecraft mc) {
        BlockHitResult hit = mc.level.clip(new ClipContext(camPos, camPos.add(camLook.scale(RANGE)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static void problem(Minecraft mc, String text) {
        Sfx.play(Sfx.ERROR);
        Interaction.say(mc, text);
    }

    /** A left click. */
    public static void onAttack(Minecraft mc) {
        if (set == null) return;
        boolean shift = Interaction.shift(mc), ctrl = Interaction.ctrl(mc);
        BlockPos cell = aimCell(mc);
        if (set.working() && stage == Stage.RESULT) {
            problem(mc, "Still looking at the build...");
            return;
        }
        if (set.working()) return;
        if (cell == null) {
            problem(mc, "Aim at a block of the build.");
            return;
        }
        long key = cell.asLong();
        if (stage == Stage.RESULT) {
            if (ctrl) {
                if (detail == 0) takeOut(mc, cell);
                else cube(mc, cell, false);
                return;
            }
            if (shift) {
                if (detail == 0) addPart(mc, cell);
                else cube(mc, cell, true);
                return;
            }
            if (set.picked().contains(key)) {
                openSave(mc);
                return;
            }
        }
        pickAt(mc, cell);
    }

    /** What a block is, in words, for "that looks like ..., not a build". */
    private static String describe(Kind k) {
        return switch (k) {
            case TERRAIN -> "the ground";
            case VEGETATION -> "a plant";
            case LOG -> "a tree";
            case FLUID -> "water";
            default -> "empty";
        };
    }

    /** Starts a pick at a block: a built one, or the built one right beside it; terrain only when asked twice. */
    private static void pickAt(Minecraft mc, BlockPos cell) {
        var field = new LevelSource(mc.level);
        Kind kind = Picker.kindOf(field, cell.getX(), cell.getY(), cell.getZ());
        BlockPos seed = null;
        Block force = null;
        if (kind == Kind.BUILT) {
            seed = cell;
        } else if (kind == Kind.FLUID) {
            problem(mc, "That is water. Aim at a block of the build.");
            return;
        } else {
            BlockPos hop = Picker.nearestBuilt(field, cell, 2);
            if (hop != null) {
                seed = hop;
            } else if (forceCell == cell.asLong()) {
                seed = cell;
                force = mc.level.getBlockState(cell).getBlock();
            } else {
                forceCell = cell.asLong();
                problem(mc, "That looks like " + describe(kind) + ", not a build. Click again to pick it anyway.");
                return;
            }
        }
        forceCell = Long.MIN_VALUE;
        set.start(seed.asLong(), force);
        Sfx.play(Sfx.PRESS, 1.1f);
    }

    private static void addPart(Minecraft mc, BlockPos cell) {
        var field = new LevelSource(mc.level);
        BlockPos target = cell;
        Kind kind = Picker.kindOf(field, cell.getX(), cell.getY(), cell.getZ());
        if (kind != Kind.BUILT) {
            target = Picker.nearestBuilt(field, cell, 2);
            if (target == null) {
                problem(mc, "Shift+click a block of the part you want to add.");
                return;
            }
        }
        long key = target.asLong();
        if (set.picked().contains(key)) {
            problem(mc, "That is already picked.");
            return;
        }
        int before = set.picked().size();
        set.addPart(key, null);
        Sfx.play(Sfx.PRESS, 1.25f);
        if (!set.working()) Interaction.say(mc, "Added a part: " + String.format(Locale.ROOT, "%,d", set.picked().size() - before) + " blocks.");
    }

    /** The precise edit: takes the picked blocks of a cube around the block aimed at out, or puts the reached ones back. */
    private static void cube(Minecraft mc, BlockPos cell, boolean put) {
        int n = put ? set.addCube(cell.asLong(), DETAIL_HALF[detail]) : set.removeCube(cell.asLong(), DETAIL_HALF[detail]);
        if (n == 0) {
            problem(mc, put ? "Nothing to put back there. Only blocks the pick has seen can come in; Shift+click a part for a new one."
                : "No picked blocks there. Aim at the pick.");
            return;
        }
        if (put) {
            Sfx.play(Sfx.PRESS, 1.25f);
        } else if (set.picked().isEmpty()) {
            set.clear();
            stage = Stage.AIM;
            Sfx.play(Sfx.CLOSE);
            Interaction.say(mc, "Nothing is left. Aim at a build and click.");
            return;
        } else {
            Sfx.play(Sfx.RELEASE, 0.9f);
        }
        Interaction.say(mc, (put ? "Put back " : "Took out ") + String.format(Locale.ROOT, "%,d", n) + (n == 1 ? " block." : " blocks.") + (put ? " Ctrl+click takes blocks out." : " Shift+click puts them back."));
    }

    private static void takeOut(Minecraft mc, BlockPos cell) {
        long key = cell.asLong();
        if (!set.picked().contains(key)) {
            var field = new LevelSource(mc.level);
            BlockPos hop = Picker.nearestBuilt(field, cell, 1);
            if (hop == null || !set.picked().contains(hop.asLong())) {
                problem(mc, "Ctrl+click a block of the pick to take its part out.");
                return;
            }
            key = hop.asLong();
        }
        int before = set.picked().size();
        set.removePart(key);
        if (set.picked().isEmpty()) {
            set.clear();
            stage = Stage.AIM;
            Sfx.play(Sfx.CLOSE);
            Interaction.say(mc, "Nothing is left. Aim at a build and click.");
            return;
        }
        Sfx.play(Sfx.RELEASE, 0.9f);
        Interaction.say(mc, "Took a part out: " + String.format(Locale.ROOT, "%,d", before - set.picked().size()) + " blocks. Shift+click it to put it back.");
    }

    /** A right click: start over, or leave when there is nothing to start over from. */
    public static void onUse(Minecraft mc) {
        if (set == null) return;
        if (stage == Stage.RESULT || set.working()) {
            set.clear();
            stage = Stage.AIM;
            mesh = null;
            meshVersion = -1;
            Sfx.play(Sfx.CLOSE, 1.2f);
            return;
        }
        cancel(mc);
    }

    /** Scroll: how wide a gap the picker jumps, or with Ctrl or Shift held how much a click cuts. Always the tool's, so the hotbar does not move. */
    public static boolean onScroll(double amount) {
        if (set == null || stage != Stage.RESULT) return true;
        scrollAcc += amount;
        int n = (int) scrollAcc;
        scrollAcc -= n;
        if (n == 0) return true;
        Minecraft mc = Minecraft.getInstance();
        if (Interaction.ctrl(mc) || Interaction.shift(mc)) {
            // up = finer
            int target = Math.max(0, Math.min(DETAIL_NAMES.length - 1, detail + n));
            if (target != detail) {
                detail = target;
                Sfx.play(Sfx.SNAP, 0.9f + 0.1f * target);
                Interaction.say(mc, "Cuts " + DETAIL_NAMES[detail]);
            }
            return true;
        }
        int target = Math.max(0, Math.min(Picker.MAX_REACH, set.reachWanted() + n));
        if (target != set.reachWanted()) {
            set.setReach(target);
            Sfx.play(Sfx.SNAP, 0.9f + 0.15f * target);
        }
        return true;
    }

    private static void openSave(Minecraft mc) {
        int[] b = set.bounds();
        if (b == null) return;
        LongOpenHashSet ground = Picker.groundUnder(new LevelSource(mc.level), set.picked(), 3);
        SelectionBox box = new SelectionBox(b[0], b[1], b[2], b[3], b[4], b[5]);
        if (box.volume() > io.github.profetgit.cyanotype.blueprint.Capture.MAX_VOLUME) {
            problem(mc, "This build is too wide to save in one piece. Take a part out, or pick a smaller one.");
            return;
        }
        Sfx.play(Sfx.PRESS, 1.2f);
        Mesh m = mesh;
        int parts = m != null && meshCurrent() ? m.extraParts : Math.max(0, set.partsAvailable() - 1);
        mc.gui.setScreen(new SaveScreen(box, Picking::finish, new SaveScreen.Pick(set.picked(), ground, parts, set.touchedUnloaded())));
    }

    // ---- per tick

    public static void tick(Minecraft mc) {
        if (!active()) return;
        if (mc.level == null || mc.player == null || set == null) {
            reset();
            Placements.setMode(Mode.IDLE);
            return;
        }
        if (!set.working()) return;
        long t0 = System.nanoTime();
        set.step(SLICE_NS);
        long stepped = System.nanoTime() - t0;
        worstStepNs = Math.max(worstStepNs, stepped);
        if (set.working()) {
            worstSliceNs = Math.max(worstSliceNs, stepped);
            return;
        }
        String notice = set.takeNotice();
        if (!notice.isBlank()) problem(mc, notice);
        if (set.picked().isEmpty()) {
            set.clear();
            stage = Stage.AIM;
            mesh = null;
            meshVersion = -1;
            return;
        }
        boolean first = stage == Stage.AIM;
        stage = Stage.RESULT;
        if (first) {
            Sfx.play(Sfx.LOCK);
            int[] b = set.bounds();
            Interaction.say(mc, "Picked " + String.format(Locale.ROOT, "%,d", set.picked().size()) + " blocks (" + (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1) + "). Click it to save.");
        }
        worstSliceNs = Math.max(worstSliceNs, System.nanoTime() - t0);
    }

    // ---- per frame

    public static void frame(Minecraft mc, Vec3 camera, Vec3 look) {
        camPos = camera;
        camLook = look;
        if (set == null) return;
        String cancel = Ui.keyName(Keys.MAIN);
        if (set.working()) {
            Interaction.say(mc, "Looking at the build... " + Math.round(set.progress() * 100) + "%");
            if (stage == Stage.RESULT) drawPick(camera);
            Interaction.chips(mc, new Chips.Chip("Right click", "Stop"), new Chips.Chip(cancel, "Cancel"));
            return;
        }
        BlockPos cell = aimCell(mc);
        updateHover(mc, cell);
        if (stage == Stage.AIM) {
            aimHint(mc, cell, cancel);
            return;
        }
        drawPick(camera);
        brushPreview(mc, cell);
        resultHint(mc, cell, cancel);
    }

    private static void updateHover(Minecraft mc, @Nullable BlockPos cell) {
        long key = cell == null ? Long.MIN_VALUE : cell.asLong();
        if (key == hoverCell) return;
        hoverCell = key;
        hoverHop = Long.MIN_VALUE;
        hoverKind = Kind.AIR;
        if (cell == null) return;
        var field = new LevelSource(mc.level);
        hoverKind = Picker.kindOf(field, cell.getX(), cell.getY(), cell.getZ());
        if (hoverKind != Kind.BUILT && hoverKind != Kind.FLUID) {
            BlockPos hop = Picker.nearestBuilt(field, cell, 2);
            if (hop != null) hoverHop = hop.asLong();
        }
    }

    private static void aimHint(Minecraft mc, @Nullable BlockPos cell, String cancel) {
        // the same two rows whatever is aimed at: only the words of the first follow it
        if (cell == null) {
            Interaction.chips(mc, new Chips.Chip("Click", "Aim at a build first"), new Chips.Chip(cancel, "Cancel"));
            return;
        }
        boolean buildable = hoverKind == Kind.BUILT || hoverHop != Long.MIN_VALUE;
        long shown = hoverKind == Kind.BUILT ? hoverCell : hoverHop;
        if (buildable) {
            Gizmos.cuboid(new AABB(BlockPos.of(shown)).inflate(0.004), GizmoStyle.strokeAndFill(0xCC000000 | (CYAN & 0xFFFFFF), 3.0f, 0x287FE3FF)).setAlwaysOnTop();
            Interaction.chips(mc, new Chips.Chip("Click", "Pick this build"), new Chips.Chip(cancel, "Cancel"));
        } else if (hoverKind == Kind.FLUID) {
            Gizmos.cuboid(new AABB(cell).inflate(0.004), GizmoStyle.stroke(0xCCFF6B6B, 2.4f)).setAlwaysOnTop();
            Interaction.chips(mc, new Chips.Chip("Click", "Water is not a build"), new Chips.Chip(cancel, "Cancel"));
        } else {
            Gizmos.cuboid(new AABB(cell).inflate(0.004), GizmoStyle.stroke(0xCCFFC857, 2.4f)).setAlwaysOnTop();
            Interaction.chips(mc, new Chips.Chip("Click", "Twice to pick " + describe(hoverKind)), new Chips.Chip(cancel, "Cancel"));
        }
    }

    /** With Ctrl (out) or Shift (in) held and a cube size chosen: the cube a click would cut, red for out and green for in. */
    private static void brushPreview(Minecraft mc, @Nullable BlockPos cell) {
        if (cell == null || detail == 0) return;
        boolean ctrl = Interaction.ctrl(mc), shift = Interaction.shift(mc);
        if (!ctrl && !shift) return;
        int h = DETAIL_HALF[detail];
        AABB box = new AABB(cell.getX() - h, cell.getY() - h, cell.getZ() - h, cell.getX() + h + 1, cell.getY() + h + 1, cell.getZ() + h + 1).inflate(0.01);
        int c = ctrl ? 0xFFFF6B6B : 0xFF7BE495;
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(0xCC000000 | (c & 0xFFFFFF), 2.6f, 0x24000000 | (c & 0xFFFFFF))).setAlwaysOnTop();
    }

    private static void resultHint(Minecraft mc, @Nullable BlockPos cell, String cancel) {
        String first = "Pick another build here";
        if (cell != null) {
            long key = cell.asLong();
            if (set.picked().contains(key)) first = "Save this build";
            else if (set.isContext(key)) first = "Pick that part instead";
        }
        // always the same seven rows: holding Ctrl or Shift must not rebuild the list, the cut size is in the words
        String cut = DETAIL_NAMES[detail];
        Interaction.chips(mc, new Chips.Chip("Click", first), new Chips.Chip("Shift+Click", "Add " + cut), new Chips.Chip("Ctrl+Click", "Take out " + cut),
            new Chips.Chip("Ctrl+Scroll", "Cut size: " + cut), new Chips.Chip("Scroll", "Reach " + set.reachWanted()), new Chips.Chip("Right click", "Start over"), new Chips.Chip(cancel, "Cancel"));
    }

    // ---- drawing the pick

    private static void drawPick(Vec3 camera) {
        if (meshVersion != set.version() && !meshing) startMesh();
        Mesh m = mesh;
        if (m == null) return;
        drawQuads(m.quads, camera);
        int[] b = m.bounds;
        if (b == null) return;
        AABB box = new AABB(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1);
        Handles.outline(box, CYAN, false);
        Vec3 c = box.getCenter();
        double dist = camera.distanceTo(c);
        float scale = Handles.labelScale(dist, 0.8, 0.055);
        double y = box.maxY + 0.7 + 0.05 * dist + 0.5 * scale;
        String text = String.format(Locale.ROOT, "%,d", set.picked().size()) + " blocks   " + (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1);
        Handles.label(new Vec3(c.x, y, c.z), text, scale, 0xFFFFFFFF, CYAN);
        float small = Handles.labelScale(dist, 0.55, 0.036);
        double ly = y - 0.9 * scale;
        int others = m.extraParts;
        if (others > 0) {
            Handles.label(new Vec3(c.x, ly, c.z), others + (others == 1 ? " more part nearby" : " more parts nearby") + ": Shift+click to add", small, 0xFFFFE9A8, 0x99FFC857);
            ly -= 0.7 * scale;
        }
        if (set.touchedUnloaded()) Handles.label(new Vec3(c.x, ly, c.z), "Part of it is in chunks that are not loaded", small, 0xFFFFE9A8, 0xCCFFC857);
    }

    private static void startMesh() {
        meshing = true;
        final int version = set.version();
        final LongOpenHashSet picked = set.picked(), doubtful = set.doubtful();
        final List<Picker.Result> floods = List.copyOf(set.floods());
        final int parts = set.partsAvailable();
        final int myEpoch = epoch;
        Util.backgroundExecutor().execute(() -> {
            LongOpenHashSet context = new LongOpenHashSet();
            for (Picker.Result r : floods) for (long c : r.cells) if (!picked.contains(c)) context.add(c);
            FaceMesher.Quads q = FaceMesher.build(picked, doubtful, context, MESH_CAP);
            int[] b = null;
            if (!picked.isEmpty()) {
                int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
                for (long c : picked) {
                    int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
                    if (x < x0) x0 = x;
                    if (y < y0) y0 = y;
                    if (z < z0) z0 = z;
                    if (x > x1) x1 = x;
                    if (y > y1) y1 = y;
                    if (z > z1) z1 = z;
                }
                b = new int[]{x0, y0, z0, x1, y1, z1};
            }
            int inPick = 0;
            for (Picker.Result r : floods) {
                boolean[] seen = new boolean[r.partCount()];
                for (long c : picked) {
                    int p = r.partOf(c);
                    if (p >= 0 && !seen[p]) {
                        seen[p] = true;
                        inPick++;
                    }
                }
            }
            Mesh m = new Mesh(version, q, b, Math.max(0, parts - inPick));
            Minecraft.getInstance().execute(() -> {
                if (myEpoch != epoch) return;
                mesh = m;
                meshVersion = version;
                meshing = false;
                thresholdFrames = 0;
            });
        });
    }

    private static void drawQuads(FaceMesher.Quads q, Vec3 camera) {
        if (q.count == 0) return;
        float[] c = q.corners;
        boolean limited = q.count > DRAW_CAP;
        if (limited && (++thresholdFrames > 20 || sortedDist == null || sortedDist.length != q.count || thresholdFrames == 1)) {
            thresholdFrames = 1;
            if (sortedDist == null || sortedDist.length != q.count) sortedDist = new float[q.count];
            for (int i = 0; i < q.count; i++) sortedDist[i] = distSq(c, i, camera);
            float[] copy = Arrays.copyOf(sortedDist, q.count);
            Arrays.sort(copy);
            thresholdSq = copy[DRAW_CAP - 1];
        }
        boolean strokes = q.count <= OUTLINE_UNDER;
        for (int i = 0; i < q.count; i++) {
            if (limited && distSq(c, i, camera) > thresholdSq) continue;
            int o = i * 12;
            Vec3 a = new Vec3(c[o], c[o + 1], c[o + 2]), b = new Vec3(c[o + 3], c[o + 4], c[o + 5]), d = new Vec3(c[o + 6], c[o + 7], c[o + 8]), e = new Vec3(c[o + 9], c[o + 10], c[o + 11]);
            GizmoStyle style = switch (q.kind[i]) {
                case FaceMesher.PICKED -> strokes ? GizmoStyle.strokeAndFill(0xAA7FE3FF, 1.4f, 0x5A7FE3FF) : GizmoStyle.fill(0x5A7FE3FF);
                case FaceMesher.DOUBTFUL -> strokes ? GizmoStyle.strokeAndFill(0xBBFFC857, 1.4f, 0x66FFC857) : GizmoStyle.fill(0x66FFC857);
                default -> strokes ? GizmoStyle.strokeAndFill(0x66FFFFFF, 1.0f, 0x1FFFFFFF) : GizmoStyle.fill(0x1FFFFFFF);
            };
            Gizmos.rect(a, b, d, e, style);
        }
    }

    private static float distSq(float[] c, int i, Vec3 cam) {
        int o = i * 12;
        double x = (c[o] + c[o + 6]) * 0.5 - cam.x, y = (c[o + 1] + c[o + 7]) * 0.5 - cam.y, z = (c[o + 2] + c[o + 8]) * 0.5 - cam.z;
        return (float) (x * x + y * y + z * z);
    }

    // ---- for the demo

    /** Dev demo: the longest a tick of the tool has taken on the client thread. */
    public static volatile long worstSliceNs, worstStepNs;

    /** Dev demo: how many rectangles the pick is drawn with right now. */
    public static int drawnQuads() {
        Mesh m = mesh;
        return m == null ? 0 : m.quads.count;
    }

    public static boolean meshCurrent() {
        return set != null && meshVersion == set.version() && !meshing;
    }
}
