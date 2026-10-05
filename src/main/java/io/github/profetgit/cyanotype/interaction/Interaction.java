package io.github.profetgit.cyanotype.interaction;

import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.PlacementStore;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.placement.Placements.Mode;
import io.github.profetgit.cyanotype.ui.Chips;
import io.github.profetgit.cyanotype.ui.Settings;
import io.github.profetgit.cyanotype.ui.Sfx;
import io.github.profetgit.cyanotype.ui.Ui;
import io.github.profetgit.cyanotype.verify.Counts;
import io.github.profetgit.cyanotype.verify.Verifier;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.network.chat.Component;
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
        double ringY, cx, cz, prevAngle, total, startAngle;
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

    static boolean shift(Minecraft mc) {
        return testModifiers >= 0 ? (testModifiers & 1) != 0 : mc.hasShiftDown();
    }

    static boolean ctrl(Minecraft mc) {
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

    /** Shows the ghosts again if they were hidden: starting to use any tool means wanting to see them. */
    public static void reveal() {
        GhostRenderer.hidden = false;
    }

    /** Starts placing: the new placement follows the crosshair until it is locked with a click. */
    public static void startPlacing(String name, Blueprint blueprint, String ref) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        reveal();
        cancelPlacing();
        Placement p = new Placement(name, blueprint, ref, mc.level.dimension().identifier().toString(), mc.player.blockPosition(), Orientation.NONE);
        p.locked = false;
        p.opacity = Settings.get().opacity;
        lift = 0;
        scrollAcc = 0;
        Placements.add(p);
        Placements.setMode(Mode.PLACING);
    }

    /** Whether a scroll belongs to the mod (and should not change the hotbar). */
    public static boolean onScroll(double amount) {
        Minecraft mc = Minecraft.getInstance();
        Placement p = Placements.active();
        if (mc.gui.screen() != null || GhostRenderer.hidden) return false;
        if (Placements.mode() == Mode.SELECT) return Selecting.onScroll(amount);
        if (Placements.mode() == Mode.PICK) return Picking.onScroll(amount);
        if (Placements.mode() == Mode.LAYERS && p != null && p.locked) {
            scrollAcc += amount;
            int n = (int) scrollAcc;
            scrollAcc -= n;
            if (n != 0) layerScroll(mc, p, n);
            return true;
        }
        if (Placements.mode() != Mode.PLACING || p == null || p.locked) return false;
        scrollAcc += amount;
        int steps = (int) scrollAcc;
        scrollAcc -= steps;
        if (steps == 0) return true;
        if (shift(mc)) {
            lift += steps;
        } else if (ctrl(mc)) {
            Vec3 look = mc.player.getLookAngle();
            p.set(p.origin, p.orientation.flipped(Math.abs(look.x) > Math.abs(look.z) ? Direction.Axis.Z : Direction.Axis.X));
            Sfx.play(Sfx.PRESS, 1.2f);
        } else {
            for (int i = 0; i < Math.abs(steps); i++) p.set(p.origin, p.orientation.rotated(steps > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90));
            Sfx.play(Sfx.SNAP, 1.15f);
        }
        return true;
    }

    /** The attack button went down. @return true if the mod used it (so the game must not) */
    public static boolean onAttack() {
        // hidden ghosts and handles are not there to click
        if (GhostRenderer.hidden) return false;
        Placement p = Placements.active();
        if (Placements.mode() == Mode.SELECT) {
            Selecting.onAttack(Minecraft.getInstance());
            suppressAttack = true;
            return true;
        }
        if (Placements.mode() == Mode.PICK) {
            Picking.onAttack(Minecraft.getInstance());
            suppressAttack = true;
            return true;
        }
        if (Placements.mode() == Mode.LAYERS) {
            endLayers(Minecraft.getInstance(), false);
            suppressAttack = true;
            return true;
        }
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
                Sfx.play(Sfx.PRESS, 1.2f);
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
        if (GhostRenderer.hidden) return false;
        Placement p = Placements.active();
        if (Placements.mode() == Mode.SELECT) {
            Selecting.onUse(Minecraft.getInstance());
            return true;
        }
        if (Placements.mode() == Mode.PICK) {
            Picking.onUse(Minecraft.getInstance());
            return true;
        }
        if (Placements.mode() == Mode.LAYERS) {
            endLayers(Minecraft.getInstance(), true);
            return true;
        }
        if (Placements.mode() == Mode.PLACING && p != null && !p.locked) {
            lock(p);
            suppressAttack = true;
            return true;
        }
        return Placements.mode() == Mode.EDIT && hover != null;
    }

    /** Whether holding the attack button should do nothing (it was used for a click or a drag, until it is let go). */
    public static boolean suppressHold() {
        return suppressAttack || drag != null || !GhostRenderer.hidden && (Placements.mode() == Mode.PLACING || Placements.mode() == Mode.LAYERS || Placements.mode() == Mode.SELECT || Placements.mode() == Mode.PICK);
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean screen = mc.gui.screen() != null;
        // the press is read from the key's state, so that a tap and a hold can be told apart; the click count only
        // matters for a tap too short for the state to show
        boolean clicked = false;
        while (Keys.MAIN.consumeClick()) clicked = true;
        tickMainKey(mc, screen, clicked && testMainDown == null);
        while (Keys.TOGGLE.consumeClick()) {
            if (!screen) toggleGhosts(mc);
        }
        while (Keys.REMOVE.consumeClick()) {
            if (!screen && !GhostRenderer.hidden) askRemove(mc);
        }
        // Ctrl+Z undoes, Ctrl+Y and Ctrl+Shift+Z redo: the keys are rebindable, the Ctrl is not, so a stray tap does nothing
        while (Keys.UNDO.consumeClick()) {
            if (screen || GhostRenderer.hidden || !ctrl(mc)) continue;
            if (shift(mc)) redo(mc);
            else undo(mc);
        }
        while (Keys.REDO.consumeClick()) {
            if (screen || GhostRenderer.hidden || !ctrl(mc)) continue;
            redo(mc);
        }
        boolean down = mc.options.keyAttack.isDown();
        if (drag != null && (!down || screen)) endDrag();
        if (Selecting.dragging() && (!down || screen)) Selecting.endDrag();
        if (suppressAttack && !down) suppressAttack = false;
        Picking.tick(mc);
        GhostRenderer.tickVerifiers(mc);
        PlacementStore.tick();
    }

    // ---- the tool key: tap to start or end editing, hold for the wheel

    /** Dev demo only: the tool key's state instead of the real one (null = the real key). */
    public static volatile Boolean testMainDown;
    private static boolean mainWasDown, mainOpened;
    private static long mainDownNs;

    private static boolean mainDown() {
        Boolean t = testMainDown;
        return t != null ? t : Keys.MAIN.isDown();
    }

    private static void tickMainKey(Minecraft mc, boolean screen, boolean tapped) {
        boolean down = mainDown();
        long now = System.nanoTime();
        if (tapped && !down && !mainWasDown) {
            if (!screen) onMainKey(mc);
            return;
        }
        if (down && !mainWasDown) {
            mainDownNs = now;
            mainOpened = false;
        }
        if (down && mainWasDown && !mainOpened && !screen && (now - mainDownNs) / 1_000_000L >= Settings.get().wheelHoldMs) {
            mainOpened = true;
            mc.gui.setScreen(new io.github.profetgit.cyanotype.ui.WheelScreen());
        }
        if (!down && mainWasDown && !mainOpened && !screen) onMainKey(mc);
        mainWasDown = down;
    }

    /** Edit mode for the placement under the crosshair, or the active one. */
    public static boolean enterEdit(Minecraft mc) {
        Placement aimed = aimedPlacement(mc);
        if (aimed == null) aimed = Placements.active();
        if (aimed == null || !aimed.ready() || !aimed.locked) {
            Sfx.play(Sfx.ERROR);
            say(mc, "Nothing to edit yet. Load a blueprint with /cyanotype load <name>, then /cyanotype place.");
            return false;
        }
        reveal();
        Placements.select(aimed);
        Placements.setMode(Mode.EDIT);
        Sfx.play(Sfx.OPEN);
        return true;
    }

    /** The Layers tool: scroll moves a window of layers up and down the build. */
    public static boolean startLayers(Minecraft mc) {
        Placement p = Placements.active();
        if (p == null || !p.ready() || !p.locked) {
            Sfx.play(Sfx.ERROR);
            say(mc, "Place a blueprint first, then pick the Layers tool.");
            return false;
        }
        if (!p.layered()) {
            // start at the lowest layer that is not finished
            int start = 0;
            Verifier v = GhostRenderer.verifierOf(p);
            if (v != null) {
                for (int l = 0; l < v.height; l++) {
                    if (v.layerCount(l, Verifier.MISSING) + v.layerCount(l, Verifier.WRONG) > 0) {
                        start = l;
                        break;
                    }
                }
            }
            p.layerLo = p.layerHi = start;
        }
        reveal();
        endDragSafely();
        Placements.setMode(Mode.LAYERS);
        scrollAcc = 0;
        PlacementStore.markDirty();
        Sfx.play(Sfx.OPEN);
        return true;
    }

    static void endDragSafely() {
        endDrag();
    }

    private static void endLayers(Minecraft mc, boolean showAll) {
        Placement p = Placements.active();
        if (showAll && p != null) {
            p.layerLo = p.layerHi = -1;
            PlacementStore.markDirty();
        }
        Placements.setMode(Mode.IDLE);
        Sfx.play(Sfx.CLOSE);
    }

    private static void layerScroll(Minecraft mc, Placement p, int steps) {
        int h = p.sizeY();
        int lo = Math.max(0, p.layerLo), hi = p.layerHi < 0 ? h - 1 : Math.min(h - 1, p.layerHi);
        if (shift(mc)) {
            hi = Math.max(lo, Math.min(h - 1, hi + steps));
        } else {
            int thick = hi - lo;
            lo = Math.max(0, Math.min(h - 1 - thick, lo + steps));
            hi = lo + thick;
        }
        if (lo != p.layerLo || hi != p.layerHi) {
            p.layerLo = lo;
            p.layerHi = hi;
            PlacementStore.markDirty();
            Sfx.play(Sfx.SNAP, 0.9f + 0.5f * lo / Math.max(1, h));
        }
    }

    /** Reverts the last change (a move, turn, flip or removal) and says what it was. */
    public static void undo(Minecraft mc) {
        Placements.Change c = Placements.undo();
        report(mc, c, "undo", "Undid");
    }

    /** Does again what the last undo reverted. */
    public static void redo(Minecraft mc) {
        Placements.Change c = Placements.redo();
        report(mc, c, "redo", "Redid");
    }

    private static void report(Minecraft mc, Placements.@Nullable Change c, String verb, String past) {
        if (c == null) {
            Sfx.play(Sfx.ERROR);
            say(mc, "Nothing to " + verb);
            return;
        }
        String what = switch (c.kind()) {
            case MOVE -> past + " the last move of " + c.placement().name;
            case RESTORE -> past.equals("Undid") ? "Brought back " + c.placement().name : "Put " + c.placement().name + " back";
            case DELETE -> "Removed " + c.placement().name + " again";
        };
        Sfx.play(Sfx.RELEASE, past.equals("Undid") ? 0.8f : 1.2f);
        say(mc, what);
    }

    /** The Delete key: asks whether to remove the selected placement (the one being edited, or the one the crosshair is on). */
    private static void askRemove(Minecraft mc) {
        Placement p = Placements.mode() == Mode.EDIT ? Placements.active() : Placements.mode() == Mode.IDLE ? aimedPlacement(mc) : null;
        if (p == null) return;
        mc.gui.setScreen(new io.github.profetgit.cyanotype.ui.RemoveScreen(p));
    }

    /** Toggles the show-or-hide of every ghost. */
    public static void toggleGhosts(Minecraft mc) {
        GhostRenderer.hidden = !GhostRenderer.hidden;
        Sfx.play(GhostRenderer.hidden ? Sfx.CLOSE : Sfx.OPEN);
        say(mc, GhostRenderer.hidden ? "Ghosts hidden" : "Ghosts shown");
    }

    /** Forgets everything in progress (the world changed). */
    public static void reset() {
        Handles.forget();
        drag = null;
        hover = null;
        handles = null;
        suppressAttack = false;
        lift = 0;
        Selecting.reset();
        Picking.reset();
    }

    // ---- per frame

    /** Runs once per frame before the scene's features are submitted: aims, hovers, drags, and draws outlines and handles. */
    public static void frame(Minecraft mc, CameraRenderState cam) {
        if (mc.level == null || mc.player == null) return;
        Vec3 pos = cam.pos;
        Handles.camera.set(cam.orientation);
        Vector3f f = new Vector3f(0, 0, -1).rotate(cam.orientation);
        Vec3 look = new Vec3(f.x, f.y, f.z);
        Placement p = Placements.active();
        Mode mode = Placements.mode();
        hover = null;
        handles = null;
        // with the ghosts hidden nothing of the tools shows or answers: no handles, hints, outlines or markers
        if (GhostRenderer.hidden) return;

        if (mode == Mode.PLACING && (p == null || p.locked || !p.ready())) {
            if (p == null || p.locked) Placements.setMode(Mode.IDLE);
        } else if (mode == Mode.PLACING) {
            follow(mc, p, pos, look);
            chips(mc, new Chips.Chip("Scroll", "Turn"), new Chips.Chip("Shift+Scroll", "Up / down"), new Chips.Chip("Ctrl+Scroll", "Flip"),
                new Chips.Chip("Click", "Lock in place"), new Chips.Chip(Ui.keyName(Keys.MAIN), "Cancel"));
        } else if (mode == Mode.LAYERS) {
            if (p == null || !p.locked || !p.ready()) {
                Placements.setMode(Mode.IDLE);
            } else {
                chips(mc, new Chips.Chip("Scroll", "Move up / down"), new Chips.Chip("Shift+Scroll", "Thicker / thinner"), new Chips.Chip("Click", "Done"),
                    new Chips.Chip("Right click", "Show all layers"));
            }
        } else if (mode == Mode.SELECT) {
            Selecting.frame(mc, pos, look);
        } else if (mode == Mode.PICK) {
            Picking.frame(mc, pos, look);
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
        handles = new Handles(p.vx, y, p.vz, p.sizeX(), p.sizeY(), p.sizeZ(), camera, look);
        if (drag != null) {
            updateDrag(mc, camera, look);
            hover = drag.handle;
            chips(mc, new Chips.Chip("Release", "Drop it here"));
        } else {
            hover = handles.pick(camera, look);
            if (hover == null) {
                chips(mc, new Chips.Chip("Drag arrow", "Move"), new Chips.Chip("Drag ring", "Turn"), new Chips.Chip("Click flip", "Mirror"),
                    new Chips.Chip("Ctrl+" + Ui.keyName(Keys.UNDO) + " / " + Ui.keyName(Keys.REDO), "Undo / Redo"), new Chips.Chip(Ui.keyName(Keys.REMOVE), "Remove"), new Chips.Chip(Ui.keyName(Keys.MAIN), "Done"));
            } else {
                chips(mc, switch (hover.kind) {
                    case MOVE -> new Chips.Chip("Drag", "Move " + axisWords(hover.axis));
                    case RING -> new Chips.Chip("Drag", "Turn in quarter turns");
                    case FLIP -> new Chips.Chip("Click", "Flip " + (hover.axis == Direction.Axis.X ? "east-west" : "north-south"));
                });
            }
        }
        handles.animate(hover, drag == null ? null : drag.handle, dt());
        handles.emit();
        if (drag != null && drag.armed) dragGuides(handles, p);
    }

    private static long lastFrameNs;

    /** Seconds since the previous frame, for the eased hover. */
    static double dt() {
        long now = System.nanoTime();
        double d = lastFrameNs == 0 ? 0.016 : Math.min(0.1, (now - lastFrameNs) / 1e9);
        lastFrameNs = now;
        return d;
    }

    /** What the drag in progress shows besides the handle itself: the travel line with block ticks, or the swept angle. */
    private static void dragGuides(Handles h, Placement p) {
        Drag d = drag;
        if (d.handle.kind == Handles.Kind.MOVE) {
            Vec3 center = new Vec3(d.startOrigin.getX() + d.startSx / 2.0, d.startOrigin.getY() + p.sizeY() / 2.0, d.startOrigin.getZ() + d.startSz / 2.0);
            AABB start = new AABB(d.startOrigin.getX(), d.startOrigin.getY(), d.startOrigin.getZ(), d.startOrigin.getX() + d.startSx, d.startOrigin.getY() + p.sizeY(), d.startOrigin.getZ() + d.startSz);
            Vec3 tip = d.handle.from.add(d.handle.to).scale(0.5);
            Handles.moveGuide(center, d.handle.axis, d.steps, d.handle.color, start, tip, h.distance);
        } else if (d.handle.kind == Handles.Kind.RING) {
            Handles.turnGuide(d.cx, d.ringY, d.cz, h.ring.radius(), d.startAngle, d.turns, h.distance);
        }
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
                Sfx.play(Sfx.SNAP, 1.0f + 0.04f * Math.min(12, Math.abs(steps)));
            }
            String what = steps == 0 ? "Drag along the arrow" : "Move " + Math.abs(steps) + " " + wordFor(d.handle.axis, steps);
            if (!Settings.get().chips) say(mc, what + "   now at " + p.origin.getX() + " " + p.origin.getY() + " " + p.origin.getZ());
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
                d.startAngle = angle;
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
                Sfx.play(Sfx.SNAP, 1.25f);
            }
            if (!Settings.get().chips) say(mc, d.turns == 0 ? "Drag around the ring" : "Turn " + Math.abs(d.turns * 90) + " degrees " + (d.turns > 0 ? "clockwise" : "anticlockwise"));
        }
    }

    private static void endDrag() {
        Drag d = drag;
        drag = null;
        if (d != null && (d.steps != 0 || d.turns != 0)) PlacementStore.markDirty();
        else if (d != null) Placements.forgetLast();   // nothing moved: drop the snapshot taken at the grab
    }

    // ---- actions

    private static void lock(Placement p) {
        p.locked = true;
        p.settleStartNs = System.nanoTime();
        Placements.setMode(Mode.EDIT);
        PlacementStore.markDirty();
        Sfx.play(Sfx.LOCK);
        Minecraft mc = Minecraft.getInstance();
        say(mc, p.name + " placed. Drag the arrows to adjust it, V when done.");
    }

    static void cancelPlacing() {
        Placement p = Placements.active();
        if (Placements.mode() == Mode.PLACING && p != null && !p.locked) Placements.remove(p);
        Placements.setMode(Mode.IDLE);
    }

    private static void onMainKey(Minecraft mc) {
        switch (Placements.mode()) {
            case PLACING -> {
                cancelPlacing();
                Sfx.play(Sfx.CLOSE);
                say(mc, "Placing cancelled");
            }
            case EDIT -> {
                endDrag();
                Placements.setMode(Mode.IDLE);
                say(mc, "Done editing");
            }
            case LAYERS -> endLayers(mc, false);
            case SELECT -> Selecting.cancel(mc);
            case PICK -> Picking.cancel(mc);
            case IDLE -> {
                Placement aimed = aimedPlacement(mc);
                if (aimed == null) aimed = Placements.active();
                if (aimed == null || !aimed.ready() || !aimed.locked) {
                    Sfx.play(Sfx.ERROR);
                    say(mc, "Nothing to edit yet. Load a blueprint with /cyanotype load <name>, then /cyanotype place.");
                    return;
                }
                reveal();
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

    /** Whether the "next block" guide is on (see /cyanotype next). */
    public static volatile boolean guide;
    private static long guideTarget = Verifier.NO_TARGET;
    private static long guideChanges = -1;
    private static Verifier guideFor;
    private static int guideFrames;

    private static void outlines(Minecraft mc, Vec3 camera) {
        Placement active = Placements.active();
        for (Placement p : Placements.all()) {
            if (!p.visible || !p.ready() || !GhostRenderer.drawn(p) || GhostRenderer.hidden) continue;
            boolean strong = p == active && (Placements.mode() != Mode.IDLE || p.locked);
            double y = GhostRenderer.visualY(p);
            AABB box = new AABB(p.vx, y, p.vz, p.vx + p.sizeX(), y + p.sizeY(), p.vz + p.sizeZ());
            Handles.outline(box, p.accent, strong);
            if (p.layered()) {
                int lo = Math.max(0, p.layerLo), hi = p.layerHi < 0 ? p.sizeY() - 1 : Math.min(p.sizeY() - 1, p.layerHi);
                Handles.layerBorder(box, y + lo, y + hi + 1, p.accent);
            }
            Vec3 c = box.getCenter();
            if (camera.distanceToSqr(c) < 96 * 96) {
                double dist = camera.distanceTo(c);
                double lift = 0.7 + 0.05 * dist;
                float nameScale = Handles.labelScale(dist, 0.8, 0.055);
                double lx = c.x, lz = c.z;
                if (strong) {
                    // while editing, the arrows own the middle of the top: the label moves to the top left, as the view sees it
                    org.joml.Vector3f right = new org.joml.Vector3f(1, 0, 0).rotate(Handles.camera);
                    double halfW = Math.abs(right.x) * p.sizeX() / 2 + Math.abs(right.z) * p.sizeZ() / 2;
                    lx -= right.x * (halfW + 1.2 * nameScale);
                    lz -= right.z * (halfW + 1.2 * nameScale);
                }
                if (Settings.get().showNames) Handles.label(new Vec3(lx, box.maxY + lift + 0.5 * nameScale, lz), p.name, nameScale, 0xFFFFFFFF, p.accent);
                if (strong) {
                    float dimScale = Handles.labelScale(dist, 0.55, 0.036);
                    String sizeText = p.sizeX() + " x " + p.sizeY() + " x " + p.sizeZ();
                    Verifier v = GhostRenderer.verifierOf(p);
                    if (v != null) sizeText += "   " + progressText(v.counts());
                    Handles.label(new Vec3(lx, box.maxY + lift - 0.2 * nameScale, lz), sizeText, dimScale, 0xFFB8D8FF, 0x66FFFFFF);
                }
            }
        }
        if (guide) nextBlock(mc, camera, active);
        CellHighlight.emit(camera);
    }

    /** "42%  310 to go" or "done". */
    public static String progressText(Counts c) {
        if (c.done()) return "done";
        if (c.judged() == 0) return c.unloaded() > 0 ? c.unloaded() + " not loaded" : "-";
        return Math.round(c.progress() * 100) + "%  " + c.todo() + " to go" + (c.unloaded() > 0 ? "  (" + c.unloaded() + " not loaded)" : "");
    }

    /** Marks the nearest block still to do in the lowest unfinished layer, and says where it is. */
    private static void nextBlock(Minecraft mc, Vec3 camera, Placement active) {
        if (active == null || !active.locked) return;
        Verifier v = GhostRenderer.verifierOf(active);
        if (v == null) return;
        // looking for it costs a scan of one layer, so only when the build changed or after some frames
        if (v != guideFor || v.changes() != guideChanges || ++guideFrames > 30) {
            guideFor = v;
            guideChanges = v.changes();
            guideFrames = 0;
            guideTarget = v.nextTarget(camera.x, camera.y, camera.z, active.layerLo, active.layerHi);
        }
        if (guideTarget == Verifier.NO_TARGET) {
            hint(mc, "Nothing left to build here.");
            return;
        }
        int x = BlockPos.getX(guideTarget), y = BlockPos.getY(guideTarget), z = BlockPos.getZ(guideTarget);
        net.minecraft.world.level.block.state.BlockState want = v.expectedAt(x, y, z);
        String name = want.getBlock().getName().getString();
        byte st = v.statusAt(x, y, z);
        double dist = camera.distanceTo(new Vec3(x + 0.5, y + 0.5, z + 0.5));
        Handles.nextMarker(new Vec3(x, y, z), (st == Verifier.WRONG ? "Replace with " : "Place ") + name, dist, System.nanoTime() / 1e9);
        net.minecraft.core.Vec3i d = new net.minecraft.core.Vec3i(x - mc.player.getBlockX(), y - mc.player.getBlockY(), z - mc.player.getBlockZ());
        hint(mc, "Next: " + name + ", " + offsetWords(d));
    }

    /** "6 east, 2 up, 3 north". */
    static String offsetWords(net.minecraft.core.Vec3i d) {
        StringBuilder sb = new StringBuilder();
        if (d.getX() != 0) sb.append(Math.abs(d.getX())).append(d.getX() > 0 ? " east" : " west");
        if (d.getY() != 0) sb.append(sb.length() > 0 ? ", " : "").append(Math.abs(d.getY())).append(d.getY() > 0 ? " up" : " down");
        if (d.getZ() != 0) sb.append(sb.length() > 0 ? ", " : "").append(Math.abs(d.getZ())).append(d.getZ() > 0 ? " south" : " north");
        return sb.length() == 0 ? "right here" : sb.toString();
    }

    public static long guideTarget() {
        return guideTarget;
    }

    // ---- small helpers

    public static void say(Minecraft mc, String text) {
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

    /** Shows cursor chips, or the same words on the action bar when chips are switched off. */
    static void chips(Minecraft mc, Chips.Chip... chips) {
        if (Settings.get().chips) Chips.show(chips);
        else say(mc, Chips.line(chips));
    }

    /** Where a handle's tip is drawn this frame, for the demo to aim at ("move+x", "move-z", "ring", "flipx", "flipz"). */
    public static @Nullable Vec3 handleAnchor(String id) {
        Handles h = handles;
        if (h == null) return null;
        for (Handles.Handle handle : h.handles) {
            String name = switch (handle.kind) {
                case MOVE -> "move" + (handle.dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? "+" : "-") + handle.axis.getName();
                case RING -> "ring";
                case FLIP -> "flip";
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
