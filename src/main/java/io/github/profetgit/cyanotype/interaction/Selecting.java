package io.github.profetgit.cyanotype.interaction;

import io.github.profetgit.cyanotype.blueprint.Capture;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.placement.Placements.Mode;
import io.github.profetgit.cyanotype.ui.Chips;
import io.github.profetgit.cyanotype.ui.SaveScreen;
import io.github.profetgit.cyanotype.ui.Sfx;
import io.github.profetgit.cyanotype.ui.Ui;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Save area tool (PRD 7.7): click a block for the first corner, click another for the opposite one (scroll raises or
 * lowers that corner when the view only meets air), then drag the arrows on the box's faces until it holds the build,
 * and a right click opens the Save screen. Everything snaps to whole blocks. State is static like the rest of
 * {@link Interaction}, which hands the clicks, scroll and frames in while the mode is {@code SELECT}.
 */
public final class Selecting {
    public enum Stage {
        /** Waiting for the first corner. */
        FIRST,
        /** The first corner is set; the box follows the crosshair. */
        SECOND,
        /** Both corners are set; the faces can be dragged. */
        ADJUST
    }

    private static final double RANGE = 64, AIR_DISTANCE = 10;
    private static final int CYAN = 0xFF7FE3FF;

    private static Stage stage = Stage.FIRST;
    private static BlockPos first;
    private static int lift;
    private static double scrollAcc;
    private static SelectionBox box;
    /** Set when the box came from Smart Pick: what the Save screen needs to leave the neighbouring buildings out. */
    private static SaveScreen.@Nullable Pick fromPick;
    private static Handles handles;
    private static Handles.Handle hover;
    /** The side the scroll wheel is moving, and when it last moved: the same side for a whole run of notches, then the one you face again. */
    private static Direction scrollFace = Direction.NORTH;
    private static long scrollNs;
    /** The box as it was when the current run of scroll notches began: what the highlight compares with. */
    private static SelectionBox scrollStart;
    /** Where the last frame was seen from: a click picks what the frame showed. */
    private static Vec3 camPos = Vec3.ZERO, camLook = new Vec3(0, 0, 1);

    // a drag of one face
    private static Handles.Handle dragHandle;
    private static SelectionBox dragStart;
    private static Vec3 dragCenter;
    private static double dragT0;
    private static boolean dragArmed;
    private static int dragSteps;

    private Selecting() {
    }

    public static boolean active() {
        return Placements.mode() == Mode.SELECT;
    }

    public static Stage stage() {
        return stage;
    }

    /** The box once both corners are set, else null. */
    public static @Nullable SelectionBox box() {
        return stage == Stage.ADJUST ? box : null;
    }

    /** Starts over: the first corner is next. */
    public static void start(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        Interaction.reveal();
        Interaction.cancelPlacing();
        Interaction.endDragSafely();
        Picking.reset();
        reset();
        Placements.setMode(Mode.SELECT);
        Sfx.play(Sfx.OPEN);
    }

    /** Smart Pick found a build: a box fitted round it, already in the stage where its sides can be dragged. */
    public static void startFromPick(Minecraft mc, SelectionBox fitted, SaveScreen.Pick pick) {
        Interaction.reveal();
        Picking.reset();
        reset();
        box = fitted;
        fromPick = pick;
        stage = Stage.ADJUST;
        Placements.setMode(Mode.SELECT);
        Sfx.play(Sfx.LOCK);
        Interaction.say(mc, "Picked " + String.format(Locale.ROOT, "%,d", pick.blocks()) + " blocks (" + fitted.sizeText() + "). Drag a side to change the box, right click to save.");
    }

    /** Leaves the tool and forgets the box. */
    public static void cancel(Minecraft mc) {
        reset();
        Placements.setMode(Mode.IDLE);
        Sfx.play(Sfx.CLOSE);
        Interaction.say(mc, "Selection cancelled");
    }

    /** Leaves the tool after a save. */
    public static void finish() {
        reset();
        Placements.setMode(Mode.IDLE);
    }

    public static void reset() {
        stage = Stage.FIRST;
        first = null;
        box = null;
        fromPick = null;
        lift = 0;
        scrollAcc = 0;
        handles = null;
        hover = null;
        dragHandle = null;
        dragStart = null;
        scrollStart = null;
        BoxHighlight.clear();
        scrollNs = 0;
    }

    // ---- input

    /** Scroll belongs to the tool: it raises or lowers the second corner while it follows the crosshair. */
    public static boolean onScroll(double amount) {
        if (stage == Stage.ADJUST && box != null && dragHandle == null) {
            // the side you are looking at grows with scroll up and shrinks with scroll down; Shift = 5 blocks at a time
            scrollAcc += amount;
            int n = (int) scrollAcc;
            scrollAcc -= n;
            if (n != 0) growFacing(Minecraft.getInstance(), n * (Interaction.shiftDown(Minecraft.getInstance()) ? 5 : 1));
        }
        if (stage == Stage.SECOND) {
            scrollAcc += amount;
            int n = (int) scrollAcc;
            scrollAcc -= n;
            if (n != 0) {
                lift += n;
                Sfx.play(Sfx.SNAP, 1.0f + 0.03f * Math.max(-10, Math.min(10, lift)));
            }
        }
        return true;
    }

    /** Moves the side the player is facing: out for a positive count, in for a negative one. The same side for a run of notches. */
    private static void growFacing(Minecraft mc, int steps) {
        long now = System.nanoTime();
        if (now - scrollNs > 1_200_000_000L) {
            scrollFace = Direction.getApproximateNearest(camLook.x, camLook.y, camLook.z);
            scrollStart = box;
        }
        SelectionBox moved = box.moved(scrollFace, steps);
        scrollNs = now;
        if (moved.equals(box)) {
            Sfx.play(Sfx.ERROR, 0.6f);
            return;
        }
        if (moved.volume() > Capture.MAX_VOLUME) {
            Sfx.play(Sfx.ERROR);
            Interaction.say(mc, "That box is too big (at most " + String.format(Locale.ROOT, "%,d", Capture.MAX_VOLUME) + " blocks)");
            return;
        }
        box = moved;
        dragSteps = steps;
        Sfx.play(Sfx.SNAP, 1.0f + 0.04f * Math.min(12, Math.abs(steps)) * Math.signum(steps));
    }

    /** A left click. */
    public static void onAttack(Minecraft mc) {
        switch (stage) {
            case FIRST -> {
                first = aim(mc, camPos, camLook, 0);
                lift = 0;
                scrollAcc = 0;
                stage = Stage.SECOND;
                Sfx.play(Sfx.PRESS, 1.1f);
            }
            case SECOND -> {
                BlockPos second = aim(mc, camPos, camLook, lift);
                SelectionBox b = SelectionBox.of(first.getX(), first.getY(), first.getZ(), second.getX(), second.getY(), second.getZ());
                if (b.volume() > Capture.MAX_VOLUME) {
                    Sfx.play(Sfx.ERROR);
                    Interaction.say(mc, "That box is too big (at most " + String.format(Locale.ROOT, "%,d", Capture.MAX_VOLUME) + " blocks). Pick a closer corner.");
                    return;
                }
                box = b;
                stage = Stage.ADJUST;
                Sfx.play(Sfx.LOCK);
                Interaction.say(mc, "Drag the arrows until the box holds your build. Right click to save.");
            }
            case ADJUST -> {
                if (hover == null) return;
                dragHandle = hover;
                dragStart = box;
                dragArmed = false;
                dragSteps = 0;
            }
        }
    }

    /** A right click: one step back, or on to the Save screen once the box is set. */
    public static void onUse(Minecraft mc) {
        switch (stage) {
            case FIRST -> cancel(mc);
            case SECOND -> {
                stage = Stage.FIRST;
                first = null;
                Sfx.play(Sfx.CLOSE, 1.2f);
            }
            case ADJUST -> {
                endDrag();
                mc.gui.setScreen(new SaveScreen(box, Selecting::finish, fromPick));
            }
        }
    }

    public static boolean dragging() {
        return dragHandle != null;
    }

    public static boolean hovering() {
        return hover != null;
    }

    public static void endDrag() {
        dragHandle = null;
        dragStart = null;
    }

    // ---- per frame

    private static BlockPos aim(Minecraft mc, Vec3 camera, Vec3 look, int liftBy) {
        BlockHitResult hit = mc.level.clip(new ClipContext(camera, camera.add(look.scale(RANGE)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        BlockPos cell = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : BlockPos.containing(camera.add(look.scale(AIR_DISTANCE)));
        return cell.above(liftBy);
    }

    public static void frame(Minecraft mc, Vec3 camera, Vec3 look) {
        hover = null;
        handles = null;
        camPos = camera;
        camLook = look;
        String cancel = Ui.keyName(Keys.MAIN);
        switch (stage) {
            case FIRST -> {
                BlockPos cell = aim(mc, camera, look, 0);
                cell(cell, 0.9);
                Interaction.chips(mc, new Chips.Chip("Click", "First corner"), new Chips.Chip(cancel, "Cancel"), Interaction.helpChip());
            }
            case SECOND -> {
                BlockPos cell = aim(mc, camera, look, lift);
                SelectionBox b = SelectionBox.of(first.getX(), first.getY(), first.getZ(), cell.getX(), cell.getY(), cell.getZ());
                drawBox(b, camera, true, null, false);
                Interaction.chips(mc, new Chips.Chip("Click", "Second corner"), new Chips.Chip("Scroll", "Raise / lower it"), new Chips.Chip("Right click", "Back"), new Chips.Chip(cancel, "Cancel"), Interaction.helpChip());
            }
            case ADJUST -> adjust(mc, camera, look, cancel);
        }
    }

    private static void adjust(Minecraft mc, Vec3 camera, Vec3 look, String cancel) {
        if (dragHandle != null) updateDrag(camera, look);
        handles = new Handles(box.x0(), box.y0(), box.z0(), box.sizeX(), box.sizeY(), box.sizeZ(), camera, look, true);
        long now = System.nanoTime();
        boolean moving = dragHandle != null || now - scrollNs < 1_200_000_000L;
        SelectionBox from = dragHandle != null ? dragStart : scrollStart;
        if (moving && mc.level != null && from != null) BoxHighlight.refresh(mc.level, box, from, camera);
        if (dragHandle != null) updateDrag(camera, look);
        handles = new Handles(box.x0(), box.y0(), box.z0(), box.sizeX(), box.sizeY(), box.sizeZ(), camera, look, true);
        if (dragHandle != null) {
            for (Handles.Handle h : handles.handles) if (h.id.equals(dragHandle.id)) hover = h;
            Interaction.chips(mc, new Chips.Chip("Release", "Set this side"));
        } else {
            hover = handles.pick(camera, look);
            Interaction.chips(mc, new Chips.Chip("Drag", hover == null ? "An arrow resizes the box" : "Move the " + face(hover.dir) + " side"),
                new Chips.Chip("Scroll", "Grow / shrink the box"),
                new Chips.Chip("Right click", "Save it..."), new Chips.Chip(cancel, "Cancel"), Interaction.helpChip());
        }
        handles.animate(hover, dragHandle == null ? null : hover, Interaction.dt());
        Direction pointed = hover != null ? hover.dir : (now - scrollNs < 1_200_000_000L ? scrollFace : null);
        drawBox(box, camera, false, pointed, dragHandle != null);
        if (moving) BoxHighlight.draw();
        else BoxHighlight.clear();
        handles.emit();
    }

    /** Dev demo: the shapes lit now. */
    /** Dev demo: how many lit shapes are for what the box lost. */
    public static int highlightedRemoved() {
        return BoxHighlight.removedCount();
    }

    public static java.util.List<AABB> highlighted() {
        return BoxHighlight.shapes();
    }

    private static void updateDrag(Vec3 camera, Vec3 look) {
        Direction.Axis axis = dragHandle.axis;
        Direction plus = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        if (!dragArmed) {
            // through the arrow itself, so the box follows the mouse one to one however big it is
            dragCenter = dragHandle.from;
        }
        double t = HandleMath.closestOnLine(camera.x, camera.y, camera.z, look.x, look.y, look.z,
            dragCenter.x, dragCenter.y, dragCenter.z, plus.getStepX(), plus.getStepY(), plus.getStepZ());
        if (Double.isNaN(t)) return;
        if (!dragArmed) {
            dragT0 = t;
            dragArmed = true;
        }
        // a drag along the axis away from the box grows the side it started on, whichever side that is
        int along = HandleMath.snap(t - dragT0);
        int outward = dragHandle.dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? along : -along;
        SelectionBox moved = dragStart.moved(dragHandle.dir, outward);
        if (moved.volume() > Capture.MAX_VOLUME) return;
        if (!moved.equals(box)) {
            box = moved;
            dragSteps = outward;
            Sfx.play(Sfx.SNAP, 1.0f + 0.04f * Math.min(12, Math.abs(outward)));
        }
    }

    private static String face(Direction d) {
        return switch (d) {
            case EAST -> "east";
            case WEST -> "west";
            case UP -> "top";
            case DOWN -> "bottom";
            case SOUTH -> "south";
            case NORTH -> "north";
        };
    }

    // ---- drawing

    private static int alpha(int argb, double a) {
        return ((int) Math.max(0, Math.min(255, a * 255)) << 24) | (argb & 0xFFFFFF);
    }

    private static void cell(BlockPos p, double a) {
        AABB b = new AABB(p).inflate(0.004);
        Gizmos.cuboid(b, GizmoStyle.strokeAndFill(alpha(CYAN, a), 3.0f, alpha(CYAN, 0.16))).setAlwaysOnTop();
    }

    private static void drawBox(SelectionBox b, Vec3 camera, boolean following, @Nullable Direction pointed, boolean dragging) {
        AABB a = b.aabb().inflate(0.004);
        BoxFrame.box(a, camera, following, pointed, dragging);
        Vec3 c = a.getCenter();
        double dist = camera.distanceTo(c);
        float scale = Handles.labelScale(dist, 0.8, 0.055);
        // the top arrow owns the middle of the top, so the label sits at the top left as the view sees it
        org.joml.Vector3f right = new org.joml.Vector3f(1, 0, 0).rotate(Handles.camera);
        double halfW = Math.abs(right.x) * (a.maxX - a.minX) / 2 + Math.abs(right.z) * (a.maxZ - a.minZ) / 2;
        double lx = following ? c.x : c.x - right.x * (halfW + 1.2 * scale), lz = following ? c.z : c.z - right.z * (halfW + 1.2 * scale);
        String text = b.sizeText() + (b.volume() > 1 ? "   " + String.format(Locale.ROOT, "%,d", b.volume()) + " cells" : "");
        Handles.label(new Vec3(lx, a.maxY + 0.7 + 0.05 * dist + 0.5 * scale, lz), text, scale, 0xFFFFFFFF, CYAN);
    }

    // ---- for the demo

    /** Where a face's arrow is drawn this frame ("east", "west", "top", "bottom", "south", "north"), or null. */
    public static @Nullable Vec3 faceAnchor(String name) {
        Handles h = handles;
        if (h == null) return null;
        for (Handles.Handle handle : h.handles) {
            if (handle.kind == Handles.Kind.MOVE && face(handle.dir).equals(name)) return handle.from.add(handle.to).scale(0.5);
        }
        return null;
    }

    /** Dev demo: sets the box without the clicks. */
    public static void testSet(SelectionBox b) {
        box = b;
        stage = Stage.ADJUST;
        Placements.setMode(Mode.SELECT);
    }

    public static int lastDragSteps() {
        return dragSteps;
    }
}
