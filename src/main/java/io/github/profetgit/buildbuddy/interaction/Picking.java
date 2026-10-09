package io.github.profetgit.buildbuddy.interaction;

import io.github.profetgit.buildbuddy.blueprint.Capture;
import io.github.profetgit.buildbuddy.pick.Kind;
import io.github.profetgit.buildbuddy.pick.PickSet;
import io.github.profetgit.buildbuddy.pick.Picker;
import io.github.profetgit.buildbuddy.placement.Placements;
import io.github.profetgit.buildbuddy.placement.Placements.Mode;
import io.github.profetgit.buildbuddy.ui.Chips;
import io.github.profetgit.buildbuddy.ui.SaveScreen;
import io.github.profetgit.buildbuddy.ui.Sfx;
import io.github.profetgit.buildbuddy.ui.Ui;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The Smart Pick tool (PRD 7.11): click any part of a build, the picker works out which blocks are the build, and a box
 * fits itself round them. From there it is the Save area tool: drag the sides of the box, right click to save, and what is
 * in the box is what is saved (the Save screen has the switches for the ground, trees and plants, and the neighbouring
 * buildings). Nothing to learn beyond that. The work is {@link PickSet}'s, a few milliseconds a tick; this class is the
 * click, the hints and the outline of the block aimed at. Static state like {@link Selecting}.
 */
public final class Picking {
    private static final double RANGE = 64;
    private static final int CYAN = 0xFF7FE3FF, AMBER = 0xFFFFC857, RED = 0xFFFF6B6B;
    private static final int SLICE_NS = 3_000_000;

    private static PickSet set;
    private static Vec3 camPos = Vec3.ZERO, camLook = new Vec3(0, 0, 1);

    // a click on terrain asks for a second click on the same block before it picks it
    private static long forceCell = Long.MIN_VALUE;

    // what the crosshair is on, worked out when it moves to another block
    private static long hoverCell = Long.MIN_VALUE;
    private static Kind hoverKind = Kind.AIR;
    private static long hoverHop = Long.MIN_VALUE;

    private Picking() {
    }

    public static boolean active() {
        return Placements.mode() == Mode.PICK;
    }

    public static boolean working() {
        return set != null && set.working();
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

    public static void reset() {
        if (set != null) set.clear();
        set = null;
        forceCell = Long.MIN_VALUE;
        hoverCell = Long.MIN_VALUE;
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

    /** A left click: pick the build it is on. */
    public static void onAttack(Minecraft mc) {
        if (set == null || set.working()) return;
        BlockPos cell = aimCell(mc);
        if (cell == null) {
            problem(mc, "Aim at a block of the build.");
            return;
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

    /** A right click: stop looking, or leave when nothing is running. */
    public static void onUse(Minecraft mc) {
        if (set == null) return;
        if (set.working()) {
            set.clear();
            Sfx.play(Sfx.CLOSE, 1.2f);
            return;
        }
        cancel(mc);
    }

    /** Scroll belongs to the tool (so the hotbar does not move) and has no job here. */
    public static boolean onScroll(double amount) {
        return true;
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
        if (!set.step(SLICE_NS)) return;
        String notice = set.takeNotice();
        if (!notice.isBlank()) problem(mc, notice);
        if (set.picked().isEmpty()) {
            set.clear();
            return;
        }
        fitBox(mc);
    }

    /** The pick is done: a box fits itself round the build and the Save area tool takes over. */
    private static void fitBox(Minecraft mc) {
        int[] b = set.bounds();
        SelectionBox box = new SelectionBox(b[0], b[1], b[2], b[3], b[4], b[5]);
        if (box.volume() > Capture.MAX_VOLUME) {
            problem(mc, "This build is too wide to save in one piece (at most " + String.format(Locale.ROOT, "%,d", Capture.MAX_VOLUME) + " blocks in the box). Try a smaller one.");
            set.clear();
            return;
        }
        SaveScreen.Pick info = new SaveScreen.Pick(set.context(), set.picked().size(), set.otherParts(), set.touchedUnloaded());
        Selecting.startFromPick(mc, box, info);
    }

    // ---- per frame

    public static void frame(Minecraft mc, Vec3 camera, Vec3 look) {
        camPos = camera;
        camLook = look;
        if (set == null) return;
        String cancel = Ui.keyName(Keys.MAIN);
        if (set.working()) {
            Interaction.say(mc, "Looking at the build... " + Math.round(set.progress() * 100) + "%");
            Interaction.chips(mc, new Chips.Chip("Right click", "Stop"), new Chips.Chip(cancel, "Cancel"), Interaction.helpChip());
            return;
        }
        BlockPos cell = aimCell(mc);
        updateHover(mc, cell);
        aimHint(mc, cell, cancel);
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

    /** The outline of the block as it is shaped (an anvil, a slab, a fence), not a box round the cell. */
    static void outline(Minecraft mc, BlockPos pos, int argb, float width, int fill) {
        BlockState s = mc.level.getBlockState(pos);
        VoxelShape shape = s.getShape(mc.level, pos);
        if (shape.isEmpty()) shape = net.minecraft.world.phys.shapes.Shapes.block();
        for (AABB box : shape.toAabbs()) {
            AABB at = box.move(pos).inflate(0.004);
            Gizmos.cuboid(at, fill == 0 ? GizmoStyle.stroke(argb, width) : GizmoStyle.strokeAndFill(argb, width, fill)).setAlwaysOnTop();
        }
    }

    private static void aimHint(Minecraft mc, @Nullable BlockPos cell, String cancel) {
        // the same two rows whatever is aimed at: only the words of the first follow it
        if (cell == null) {
            Interaction.chips(mc, new Chips.Chip("Click", "Aim at a build first"), new Chips.Chip(cancel, "Cancel"), Interaction.helpChip());
            return;
        }
        boolean buildable = hoverKind == Kind.BUILT || hoverHop != Long.MIN_VALUE;
        long shown = hoverKind == Kind.BUILT ? hoverCell : hoverHop;
        if (buildable) {
            outline(mc, BlockPos.of(shown), 0xCC000000 | (CYAN & 0xFFFFFF), 3.0f, 0x287FE3FF);
            Interaction.chips(mc, new Chips.Chip("Click", "Fit a box round this build"), new Chips.Chip(cancel, "Cancel"), Interaction.helpChip());
        } else if (hoverKind == Kind.FLUID) {
            outline(mc, cell, 0xCC000000 | (RED & 0xFFFFFF), 2.4f, 0);
            Interaction.chips(mc, new Chips.Chip("Click", "Water is not a build"), new Chips.Chip(cancel, "Cancel"), Interaction.helpChip());
        } else {
            outline(mc, cell, 0xCC000000 | (AMBER & 0xFFFFFF), 2.4f, 0);
            Interaction.chips(mc, new Chips.Chip("Click", "Twice to pick " + describe(hoverKind)), new Chips.Chip(cancel, "Cancel"), Interaction.helpChip());
        }
    }
}
