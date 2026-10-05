package io.github.profetgit.cyanotype.interaction;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.PlacementStore;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.placement.Placements.Mode;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Putting a blueprint in the world and adjusting it, with the mouse: while placing, the ghost follows the crosshair
 * (scroll turns it, shift+scroll lifts it, ctrl+scroll flips it, a click locks it); after the lock its handles appear
 * (drag an arrow to move, drag the ring to turn, click a flip arrow). Input comes in through mixins on the mouse and
 * attack/use handling; geometry is worked out once per frame from the camera, so hovering and dragging track the view
 * exactly, and everything snaps to whole blocks and quarter turns.
 */
public final class Interaction {
    private static final double PLACE_RANGE = 64, AIR_DISTANCE = 10;

    /** A grab in progress. */
    private static final class Drag {
        final Placement placement;
        final Handles.Handle handle;
        final BlockPos startOrigin;
        final Orientation startOrientation;
        final int startSx, startSz;
        boolean armed;
        // move
        Vec3 linePoint;
        Vec3 axis;
        double t0;
        int steps;
        // ring
        double ringY, cx, cz, prevAngle, total;
        int turns;

        Drag(Placement p, Handles.Handle handle) {
            this.placement = p;
            this.handle = handle;
            this.startOrigin = p.origin;
            this.startOrientation = p.orientation;
            this.startSx = p.sizeX();
            this.startSz = p.sizeZ();
        }
    }

    /** Dev demo only: which modifier keys the scroll sees (1 = shift, 2 = ctrl) instead of the real ones; -1 = the real keys. */
    public static volatile int testModifiers = -1;

    private static boolean shift(Minecraft mc) {
        return testModifiers >= 0 ? (testModifiers & 1) != 0 : mc.hasShiftDown();
    }

    private static boolean ctrl(Minecraft mc) {
        return testModifiers >= 0 ? (testModifiers & 2) != 0 : mc.hasControlDown();
    }

    /** Blocks the placement has been lifted above the surface it aims at (shift+scroll). */
    public static int lift() {
        return lift;
    }

    private static int lift;
    private static double scrollAcc;
    private static boolean suppressAttack;
    private static Handles handles;
    private static Handles.Handle hover;
    private static Drag drag;

    private Interaction() {
    }

    // ---- entry points from the mixins and the tick

    /** Starts placing: the new placement follows the crosshair until it is locked with a click. */
    public static void startPlacing(String name, Blueprint blueprint, String ref) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        cancelPlacing();
        Placement p = new Placement(name, blueprint, ref, mc.level.dimension().identifier().toString(), mc.player.blockPosition(), Orientation.NONE);
        p.locked = false;
        lift = 0;
        scrollAcc = 0;
        Placements.add(p);
        Placements.setMode(Mode.PLACING);
    }

    /** Whether a scroll belongs to the mod (and should not change the hotbar). */
    public static boolean onScroll(double amount) {
        Minecraft mc = Minecraft.getInstance();
        Placement p = Placements.active();
        if (Placements.mode() != Mode.PLACING || p == null || p.locked || mc.gui.screen() != null) return false;
        scrollAcc += amount;
        int steps = (int) scrollAcc;
        scrollAcc -= steps;
        if (steps == 0) return true;
        if (shift(mc)) {
            lift += steps;
        } else if (ctrl(mc)) {
            Vec3 look = mc.player.getLookAngle();
            p.set(p.origin, p.orientation.flipped(Math.abs(look.x) > Math.abs(look.z) ? Direction.Axis.Z : Direction.Axis.X));
            ui(1.3f);
        } else {
            for (int i = 0; i < Math.abs(steps); i++) p.set(p.origin, p.orientation.rotated(steps > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90));
            ui(1.5f);
        }
        return true;
    }

    /** The attack button went down. @return true if the mod used it (so the game must not) */
    public static boolean onAttack() {
        Placement p = Placements.active();
        if (Placements.mode() == Mode.PLACING && p != null && !p.locked) {
            lock(p);
            suppressAttack = true;
            return true;
        }
        if (Placements.mode() == Mode.EDIT && p != null && hover != null && drag == null) {
            suppressAttack = true;
            if (hover.kind == Handles.Kind.FLIP) {
                Placements.remember(p);
                p.set(p.origin, p.orientation.flipped(hover.axis));
                PlacementStore.markDirty();
                ui(1.3f);
            } else {
                Placements.remember(p);
                drag = new Drag(p, hover);
            }
            return true;
        }
        return false;
    }

    /** The use button went down. */
    public static boolean onUse() {
        Placement p = Placements.active();
        if (Placements.mode() == Mode.PLACING && p != null && !p.locked) {
            lock(p);
            suppressAttack = true;
            return true;
        }
        return Placements.mode() == Mode.EDIT && hover != null;
    }

    /** Whether holding the attack button should do nothing (it was used for a click or a drag, until it is let go). */
    public static boolean suppressHold() {
        return suppressAttack || drag != null || Placements.mode() == Mode.PLACING;
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean screen = mc.gui.screen() != null;
        while (Keys.MAIN.consumeClick()) if (!screen) onMainKey(mc);
        while (Keys.TOGGLE.consumeClick()) {
            if (!screen) {
                GhostRenderer.hidden = !GhostRenderer.hidden;
                say(mc, GhostRenderer.hidden ? "Ghosts hidden" : "Ghosts shown");
            }
        }
        while (Keys.UNDO.consumeClick()) {
            if (screen) continue;
            Placement back = Placements.undo();
            say(mc, back == null ? "Nothing to undo" : "Undid the last move of " + back.name);
            if (back != null) sound(SoundEvents.AMETHYST_BLOCK_PLACE, 0.8f, 0.8f);
        }
        boolean down = mc.options.keyAttack.isDown();
        if (drag != null && (!down || screen)) endDrag();
        if (suppressAttack && !down) suppressAttack = false;
        PlacementStore.tick();
    }

    /** Forgets everything in progress (the world changed). */
    public static void reset() {
        drag = null;
        hover = null;
        handles = null;
        suppressAttack = false;
        lift = 0;
    }

    // ---- per frame

    /** Runs once per frame before the scene's features are submitted: aims, hovers, drags, and draws outlines and handles. */
    public static void frame(Minecraft mc, CameraRenderState cam) {
        if (mc.level == null || mc.player == null) return;
        Vec3 pos = cam.pos;
        Vector3f f = new Vector3f(0, 0, -1).rotate(cam.orientation);
        Vec3 look = new Vec3(f.x, f.y, f.z);
        Placement p = Placements.active();
        Mode mode = Placements.mode();
        hover = null;
        handles = null;

        if (mode == Mode.PLACING && (p == null || p.locked || !p.ready())) {
            if (p == null || p.locked) Placements.setMode(Mode.IDLE);
        } else if (mode == Mode.PLACING) {
            follow(mc, p, pos, look);
            hint(mc, "Scroll: turn  |  Shift+scroll: up and down  |  Ctrl+scroll: flip  |  Click: lock in place  |  V: cancel");
        } else if (mode == Mode.EDIT) {
            if (p == null || !p.locked || !p.ready() || !GhostRenderer.drawn(p)) {
                if (p == null || !p.locked) Placements.setMode(Mode.IDLE);
            } else {
                edit(mc, p, pos, look);
            }
        }
        outlines(mc, pos);
    }

    private static void follow(Minecraft mc, Placement p, Vec3 camera, Vec3 look) {
        BlockHitResult hit = mc.level.clip(new ClipContext(camera, camera.add(look.scale(PLACE_RANGE)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        BlockPos target = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection()) : BlockPos.containing(camera.add(look.scale(AIR_DISTANCE)));
        int[] xz = Moves.centerOn(target.getX(), target.getZ(), p.sizeX(), p.sizeZ());
        p.set(new BlockPos(xz[0], target.getY() + lift, xz[1]), p.orientation);
    }

    private static void edit(Minecraft mc, Placement p, Vec3 camera, Vec3 look) {
        double y = GhostRenderer.visualY(p);
        handles = new Handles(p.vx, y, p.vz, p.sizeX(), p.sizeY(), p.sizeZ(), camera);
        if (drag != null) {
            updateDrag(mc, camera, look);
            hover = drag.handle;
        } else {
            hover = handles.pick(camera, look);
            hint(mc, hover == null
                ? "Drag an arrow: move  |  Drag the ring: turn  |  Click a flip arrow  |  Z: undo  |  V: done"
                : switch (hover.kind) {
                    case MOVE -> "Drag to move " + axisWords(hover.axis);
                    case RING -> "Drag around to turn in quarter turns";
                    case FLIP -> "Click to flip " + (hover.axis == Direction.Axis.X ? "east-west" : "north-south");
                });
        }
        handles.emit(hover);
    }

    private static void updateDrag(Minecraft mc, Vec3 camera, Vec3 look) {
        Drag d = drag;
        Placement p = d.placement;
        if (d.handle.kind == Handles.Kind.MOVE) {
            if (!d.armed) {
                Direction dir = Direction.fromAxisAndDirection(d.handle.axis, Direction.AxisDirection.POSITIVE);
                d.axis = new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ());
                d.linePoint = new Vec3(d.startOrigin.getX() + d.startSx / 2.0, d.startOrigin.getY() + p.sizeY() / 2.0, d.startOrigin.getZ() + d.startSz / 2.0);
            }
            double t = HandleMath.closestOnLine(camera.x, camera.y, camera.z, look.x, look.y, look.z,
                d.linePoint.x, d.linePoint.y, d.linePoint.z, d.axis.x, d.axis.y, d.axis.z);
            if (Double.isNaN(t)) return;
            if (!d.armed) {
                d.t0 = t;
                d.armed = true;
            }
            int steps = HandleMath.snap(t - d.t0);
            if (steps != d.steps) {
                d.steps = steps;
                p.set(d.startOrigin.offset((int) (d.axis.x * steps), (int) (d.axis.y * steps), (int) (d.axis.z * steps)), p.orientation);
                ui(1.7f);
            }
            String what = steps == 0 ? "Drag along the arrow" : "Move " + Math.abs(steps) + " " + wordFor(d.handle.axis, steps);
            say(mc, what + "   now at " + p.origin.getX() + " " + p.origin.getY() + " " + p.origin.getZ());
        } else {
            if (!d.armed) {
                d.ringY = d.startOrigin.getY() + 0.05;
                d.cx = d.startOrigin.getX() + d.startSx / 2.0;
                d.cz = d.startOrigin.getZ() + d.startSz / 2.0;
            }
            double t = HandleMath.rayPlaneY(camera.y, look.y, d.ringY);
            if (Double.isNaN(t)) return;
            double angle = HandleMath.angle(camera.x + look.x * t, camera.z + look.z * t, d.cx, d.cz);
            if (!d.armed) {
                d.prevAngle = angle;
                d.armed = true;
            }
            d.total += HandleMath.angleDelta(d.prevAngle, angle);
            d.prevAngle = angle;
            int turns = HandleMath.quarterTurns(d.total);
            if (turns != d.turns) {
                d.turns = turns;
                Orientation o = d.startOrientation;
                for (int i = 0; i < Math.abs(turns); i++) o = o.rotated(turns > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
                Blueprint bp = p.blueprint;
                int nsx = o.sizeX(bp.sizeX, bp.sizeZ), nsz = o.sizeZ(bp.sizeX, bp.sizeZ);
                int[] xz = Moves.keepCenter(d.startOrigin.getX(), d.startOrigin.getZ(), d.startSx, d.startSz, nsx, nsz);
                p.set(new BlockPos(xz[0], d.startOrigin.getY(), xz[1]), o);
                ui(1.5f);
            }
            say(mc, d.turns == 0 ? "Drag around the ring" : "Turn " + Math.abs(d.turns * 90) + " degrees " + (d.turns > 0 ? "clockwise" : "anticlockwise"));
        }
    }

    private static void endDrag() {
        Drag d = drag;
        drag = null;
        if (d != null && (d.steps != 0 || d.turns != 0)) PlacementStore.markDirty();
        else if (d != null) Placements.undo();   // nothing moved: drop the snapshot taken at the grab
    }

    // ---- actions

    private static void lock(Placement p) {
        p.locked = true;
        p.settleStartNs = System.nanoTime();
        Placements.setMode(Mode.EDIT);
        PlacementStore.markDirty();
        sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
        Minecraft mc = Minecraft.getInstance();
        say(mc, p.name + " placed. Drag the arrows to adjust it, V when done.");
    }

    private static void cancelPlacing() {
        Placement p = Placements.active();
        if (Placements.mode() == Mode.PLACING && p != null && !p.locked) Placements.remove(p);
        Placements.setMode(Mode.IDLE);
    }

    private static void onMainKey(Minecraft mc) {
        switch (Placements.mode()) {
            case PLACING -> {
                cancelPlacing();
                say(mc, "Placing cancelled");
            }
            case EDIT -> {
                endDrag();
                Placements.setMode(Mode.IDLE);
                say(mc, "Done editing");
            }
            case IDLE -> {
                Placement aimed = aimedPlacement(mc);
                if (aimed == null) aimed = Placements.active();
                if (aimed == null || !aimed.ready() || !aimed.locked) {
                    say(mc, "Nothing to edit yet. Load a blueprint with /cyanotype load <name>, then /cyanotype place.");
                    return;
                }
                Placements.select(aimed);
                Placements.setMode(Mode.EDIT);
                say(mc, "Editing " + aimed.name);
            }
        }
    }

    /** The drawn placement the crosshair points into, nearest first. */
    public static @Nullable Placement aimedPlacement(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        Placement best = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (Placement p : Placements.all()) {
            if (!p.ready() || !p.visible || !GhostRenderer.drawn(p)) continue;
            AABB b = p.bounds();
            double t = HandleMath.rayBox(eye.x, eye.y, eye.z, look.x, look.y, look.z, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
            if (!Double.isNaN(t) && t < bestT) {
                bestT = t;
                best = p;
            }
        }
        return best;
    }

    // ---- drawing the outlines

    private static void outlines(Minecraft mc, Vec3 camera) {
        Placement active = Placements.active();
        for (Placement p : Placements.all()) {
            if (!p.visible || !p.ready() || !GhostRenderer.drawn(p) || GhostRenderer.hidden) continue;
            boolean on = p == active && Placements.mode() != Mode.IDLE || p == active && p.locked;
            double y = GhostRenderer.visualY(p);
            AABB box = new AABB(p.vx, y, p.vz, p.vx + p.sizeX(), y + p.sizeY(), p.vz + p.sizeZ());
            int color = on ? p.accent : (p.accent & 0x00FFFFFF) | 0x88000000;
            Gizmos.cuboid(box, GizmoStyle.stroke(color, on ? 3.0f : 2.0f));
            if (camera.distanceToSqr(box.getCenter()) < 96 * 96) {
                Gizmos.billboardText(p.name, new Vec3(box.getCenter().x, box.maxY + 0.9, box.getCenter().z), TextGizmo.Style.forColorAndCentered(p.accent).withScale(0.36f)).setAlwaysOnTop();
            }
        }
    }

    // ---- small helpers

    private static void say(Minecraft mc, String text) {
        mc.gui.hud.setOverlayMessage(Component.literal(text), false);
    }

    private static void hint(Minecraft mc, String text) {
        say(mc, text);
    }

    private static String axisWords(Direction.Axis a) {
        return switch (a) {
            case X -> "east or west";
            case Y -> "up or down";
            case Z -> "north or south";
        };
    }

    private static String wordFor(Direction.Axis a, int steps) {
        return switch (a) {
            case X -> steps > 0 ? "east" : "west";
            case Y -> steps > 0 ? "up" : "down";
            case Z -> steps > 0 ? "south" : "north";
        };
    }

    private static void ui(float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    private static void sound(net.minecraft.sounds.SoundEvent event, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }

    /** Where a handle's tip is drawn this frame, for the demo to aim at ("move+x", "move-z", "ring", "flipx", "flipz"). */
    public static @Nullable Vec3 handleAnchor(String id) {
        Handles h = handles;
        if (h == null) return null;
        for (Handles.Handle handle : h.handles) {
            String name = switch (handle.kind) {
                case MOVE -> "move" + (handle.dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? "+" : "-") + handle.axis.getName();
                case RING -> "ring";
                case FLIP -> "flip" + handle.axis.getName();
            };
            if (!name.equals(id)) continue;
            if (handle.kind == Handles.Kind.RING) {
                return new Vec3(h.ring.cx() + h.ring.radius(), h.ring.y(), h.ring.cz());
            }
            return handle.from.add(handle.to).scale(0.5);
        }
        return null;
    }

    /** Ring centre and radius as drawn, for the demo. */
    public static double @Nullable [] ringGeometry() {
        Handles h = handles;
        return h == null ? null : new double[]{h.ring.cx(), h.ring.y(), h.ring.cz(), h.ring.radius()};
    }

    public static boolean dragging() {
        return drag != null;
    }

    public static String hoverName() {
        Handles.Handle h = hover;
        return h == null ? "" : h.kind.name().toLowerCase(Locale.ROOT) + (h.axis != null ? h.axis.getName() : "");
    }
}
