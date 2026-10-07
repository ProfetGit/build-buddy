package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.G;
import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.camera;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.profetgit.cyanotype.command.DevCommands;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Keys;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.PlacementStore;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.placement.Session;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/**
 * The mouse tools driven through the same paths a player uses: scroll goes through the real mouse handler, clicks through
 * the key-mapping click counts the game loop consumes, the camera is aimed by rotating the player. Only the modifier keys
 * are faked (the scroll reads them from the demo when asked to).
 */
final class PlaceScenes {
    private PlaceScenes() {
    }

    static InputConstants.Key key(KeyMapping k) {
        return k.getDefaultKey();
    }

    static void tap(KeyMapping k) {
        KeyMapping.click(key(k));
    }

    /** A tap with Ctrl held (and Shift too when asked): undo and redo need the modifier. */
    static void ctrlTap(KeyMapping k, boolean shift) {
        act(() -> {
            Interaction.testModifiers = shift ? 3 : 2;
            tap(k);
        });
        waitTicks(3);
        act(() -> Interaction.testModifiers = -1);
    }

    static void hold(KeyMapping k) {
        KeyMapping.set(key(k), true);
        KeyMapping.click(key(k));
    }

    static void release(KeyMapping k) {
        KeyMapping.set(key(k), false);
    }

    static void scroll(Minecraft mc, double amount) {
        mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, amount);
    }

    /** Turns the player's head so the crosshair passes through a point. */
    static void aim(Minecraft mc, double x, double y, double z) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setYHeadRot(yaw);
    }

    static Placement active() {
        return Placements.active();
    }

    static String at(Placement p) {
        return p.origin.getX() + "," + p.origin.getY() + "," + p.origin.getZ();
    }

    // ---- scene: place (aim, turn, lift, flip, lock; then the handles)

    static void place() {
        Director.clean();
        act(() -> DevCommands.run("/cyanotype sample"));
        until("place/blueprint loaded", 200, () -> DevCommands.loadedBlueprint() != null);
        // fly high above a block of grass and look straight down at it
        camera(20.5, G + 6, 3.5, 0, 90);
        waitTicks(10);
        double[] slot = new double[1];
        act(() -> {
            slot[0] = Minecraft.getInstance().player.getInventory().getSelectedSlot();
            DevCommands.run("/cyanotype place");
        });
        waitTicks(8);
        act(() -> {
            Placement p = active();
            // the 11 x 13 house, centred on the cell the crosshair touches (20, G + 1, 3)
            boolean ok = p != null && Placements.mode() == Placements.Mode.PLACING && !p.locked
                && p.origin.equals(new BlockPos(15, G + 1, -3));
            check("place/follows the crosshair", ok, p == null ? "no placement" : "origin " + at(p) + ", mode " + Placements.mode());
        });
        shot("place_follow");

        act(() -> scroll(Minecraft.getInstance(), 1));
        waitTicks(6);
        act(() -> {
            Placement p = active();
            // turned a quarter: 13 x 11, centred on the same cell
            boolean ok = p.orientation.rotation() == Rotation.CLOCKWISE_90 && p.origin.equals(new BlockPos(14, G + 1, -2));
            check("place/scroll turns it", ok, "orientation " + p.orientation.rotation() + ", origin " + at(p));
            Minecraft mc = Minecraft.getInstance();
            check("place/scroll leaves the hotbar", mc.player.getInventory().getSelectedSlot() == (int) slot[0], "slot " + mc.player.getInventory().getSelectedSlot());
        });
        act(() -> {
            Interaction.testModifiers = 1;
            scroll(Minecraft.getInstance(), 1);
            scroll(Minecraft.getInstance(), 1);
            Interaction.testModifiers = -1;
        });
        waitTicks(6);
        act(() -> {
            Placement p = active();
            check("place/shift+scroll lifts it", p.origin.getY() == G + 3 && Interaction.lift() == 2, "origin " + at(p) + ", lift " + Interaction.lift());
        });
        act(() -> {
            Interaction.testModifiers = 1;
            scroll(Minecraft.getInstance(), -2);
            Interaction.testModifiers = 2;
            scroll(Minecraft.getInstance(), 1);
            Interaction.testModifiers = -1;
        });
        waitTicks(6);
        act(() -> {
            Placement p = active();
            check("place/ctrl+scroll flips it", p.orientation.isMirrored() && p.origin.getY() == G + 1, "mirrored " + p.orientation.isMirrored() + ", origin " + at(p));
        });
        shot("place_flipped");
        act(() -> {
            Interaction.testModifiers = 2;
            scroll(Minecraft.getInstance(), 1);
            Interaction.testModifiers = -1;
        });
        waitTicks(4);

        // click to lock
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> {
            release(Minecraft.getInstance().options.keyAttack);
            Placement p = active();
            check("place/click locks", p != null && p.locked && Placements.mode() == Placements.Mode.EDIT && p.settleStartNs != 0, "locked " + (p != null && p.locked) + ", mode " + Placements.mode());
            double lift = GhostRenderer.visualY(p) - p.vy;
            check("place/the lock spring is running", Math.abs(lift) > 0.03, String.format(Locale.ROOT, "%.3f blocks above its place", lift));
        });
        waitTicks(24);
        act(() -> {
            Placement p = active();
            double lift = GhostRenderer.visualY(p) - p.vy;
            check("place/the lock spring ended", Math.abs(lift) < 1e-9, String.format(Locale.ROOT, "%.4f blocks above its place", lift));
        });
        shot("place_locked");
        until("place/ghost baked", 400, GhostRenderer::settled);
    }

    // ---- scene: handles (move, turn, flip, undo, select)

    static Placement first;

    static void handles() {
        act(() -> {
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
            DevCommands.run("/cyanotype sample");
        });
        until("handles/blueprint loaded", 200, () -> DevCommands.loadedBlueprint() != null);
        act(() -> {
            DevCommands.run("/cyanotype place 0 " + (G + 1) + " 6");
            first = active();
        });
        camera(5.5, G + 9, -12, 0, 12);
        waitTicks(10);
        act(() -> {
            Placements.setMode(Placements.Mode.EDIT);
        });
        waitTicks(30);
        shot("handles_idle");

        // hover each handle by aiming at it
        for (String id : List.of("move+x", "move-x", "move+y", "ring")) {
            act(() -> {
                Minecraft mc = Minecraft.getInstance();
                Vec3 a = Interaction.handleAnchor(id);
                if (a == null) {
                    check("handles/hover " + id, false, "no such handle");
                    return;
                }
                aim(mc, a.x, a.y, a.z);
            });
            waitTicks(4);
            act(() -> {
                String want = id.startsWith("move") ? "move" + id.substring(id.length() - 1) : "ringy";
                check("handles/hover " + id, Interaction.hoverName().startsWith(want), "hover '" + Interaction.hoverName() + "'");
            });
        }

        // an arrow pointing straight along the view is still drawn and still grabbable: arrows never come and go with the view
        act(() -> {
            Vec3 a = Interaction.handleAnchor("move+z");
            if (a != null) aim(Minecraft.getInstance(), a.x, a.y, a.z);
        });
        waitTicks(4);
        act(() -> check("handles/an end-on arrow can still be grabbed", Interaction.hoverName().startsWith("movez"), "hover '" + Interaction.hoverName() + "'"));
        camera(-16, G + 6, 12.5, -90, 6);
        waitTicks(10);
        act(() -> {
            Vec3 a = Interaction.handleAnchor("move+z");
            if (a != null) aim(Minecraft.getInstance(), a.x, a.y, a.z);
        });
        waitTicks(5);
        act(() -> check("handles/the same arrow from the side is", Interaction.hoverName().equals("movez"), "hover '" + Interaction.hoverName() + "'"));
        camera(5.5, G + 9, -12, 0, 12);
        waitTicks(10);

        // drag the +x arrow three blocks east
        act(() -> {
            Minecraft mc = Minecraft.getInstance();
            Vec3 a = Interaction.handleAnchor("move+x");
            aim(mc, a.x, a.y, a.z);
        });
        waitTicks(3);
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> check("handles/grab starts a drag", Interaction.dragging(), "dragging " + Interaction.dragging()));
        Vec3[] grab = new Vec3[1];
        act(() -> grab[0] = Interaction.handleAnchor("move+x"));
        for (int k = 1; k <= 3; k++) {
            int step = k;
            act(() -> aim(Minecraft.getInstance(), grab[0].x + step, grab[0].y, grab[0].z));
            waitTicks(4);
        }
        shot("handles_dragging");
        act(() -> release(Minecraft.getInstance().options.keyAttack));
        waitTicks(4);
        act(() -> check("handles/arrow moves it east", first.origin.equals(new BlockPos(3, G + 1, 6)) && !Interaction.dragging(), "origin " + at(first)));

        // a drag that stays within half a block moves nothing (and leaves no undo behind)
        act(() -> {
            Vec3 a = Interaction.handleAnchor("move+z");
            aim(Minecraft.getInstance(), a.x, a.y, a.z);
        });
        waitTicks(4);
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> release(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> check("handles/a click without drag moves nothing", first.origin.equals(new BlockPos(3, G + 1, 6)), "origin " + at(first)));

        // drag the +y arrow up two
        act(() -> {
            Vec3 a = Interaction.handleAnchor("move+y");
            aim(Minecraft.getInstance(), a.x, a.y, a.z);
        });
        waitTicks(4);
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> grab[0] = Interaction.handleAnchor("move+y"));
        for (int k = 1; k <= 2; k++) {
            int step = k;
            act(() -> aim(Minecraft.getInstance(), grab[0].x, grab[0].y + step, grab[0].z));
            waitTicks(4);
        }
        act(() -> release(Minecraft.getInstance().options.keyAttack));
        waitTicks(4);
        act(() -> check("handles/arrow lifts it", first.origin.equals(new BlockPos(3, G + 3, 6)), "origin " + at(first)));

        // the ring: a quarter turn clockwise
        act(() -> {
            Vec3 a = Interaction.handleAnchor("ring");
            aim(Minecraft.getInstance(), a.x, a.y, a.z);
        });
        waitTicks(4);
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        double[][] ring = new double[1][];
        act(() -> ring[0] = Interaction.ringGeometry());
        for (int deg = 10; deg <= 100; deg += 10) {
            int d = deg;
            act(() -> {
                double a = Math.toRadians(d);
                double[] r = ring[0];
                aim(Minecraft.getInstance(), r[0] + Math.cos(a) * r[3], r[1], r[2] + Math.sin(a) * r[3]);
            });
            waitTicks(3);
        }
        act(() -> release(Minecraft.getInstance().options.keyAttack));
        waitTicks(4);
        act(() -> check("handles/ring turns it a quarter", first.orientation.rotation() == Rotation.CLOCKWISE_90, "rotation " + first.orientation.rotation() + ", origin " + at(first)));
        until("handles/rebaked after the turn", 400, GhostRenderer::settled);
        shot("handles_turned");

        // M mirrors it (there are no flip arrows any more)
        act(() -> tap(Keys.MIRROR));
        waitTicks(4);
        act(() -> check("handles/M mirrors it", first.orientation.isMirrored(), "orientation " + first.orientation));

        // undo, three times: the flip, the turn, the lift
        for (int i = 0; i < 3; i++) ctrlTap(Keys.UNDO, false);
        act(() -> check("handles/undo walks back", first.origin.equals(new BlockPos(3, G + 1, 6)) && first.orientation.equals(Orientation.NONE), "origin " + at(first) + ", " + first.orientation));
        ctrlTap(Keys.UNDO, false);
        act(() -> check("handles/undo reaches the start", first.origin.equals(new BlockPos(0, G + 1, 6)), "origin " + at(first)));
        until("handles/settled again", 400, GhostRenderer::settled);

        // a second placement, then pick the first by aiming at it
        act(() -> DevCommands.run("/cyanotype place 30 " + (G + 1) + " 6"));
        waitTicks(4);
        act(() -> {
            Placements.setMode(Placements.Mode.IDLE);
            check("handles/two placements", Placements.all().size() == 2 && active() != first, "placements " + Placements.all().size());
        });
        camera(5.5, G + 4, -4, 0, 5);
        waitTicks(10);
        act(() -> tap(Keys.MAIN));
        waitTicks(4);
        act(() -> check("handles/V aimed selects and edits", active() == first && Placements.mode() == Placements.Mode.EDIT, "active " + (active() == null ? "none" : active().name) + ", mode " + Placements.mode()));
        act(() -> tap(Keys.MAIN));
        waitTicks(3);
        act(() -> check("handles/V again is done", Placements.mode() == Placements.Mode.IDLE, "mode " + Placements.mode()));
        // while editing, H takes the handles and the hints away with the ghost, and nothing hidden can be grabbed
        act(() -> tap(Keys.MAIN));
        waitTicks(6);
        act(() -> check("handles/editing before H", Placements.mode() == Placements.Mode.EDIT && Interaction.handleAnchor("ring") != null, "mode " + Placements.mode()));
        act(() -> tap(Keys.TOGGLE));
        waitTicks(6);
        act(() -> {
            check("handles/H hides the handles too", GhostRenderer.hidden && Interaction.handleAnchor("ring") == null && Interaction.handleAnchor("move+y") == null, "hidden " + GhostRenderer.hidden);
            check("handles/hidden handles cannot be grabbed", !Interaction.onAttack() && !Interaction.onUse() && !Interaction.onScroll(1), "the clicks were taken");
        });
        act(() -> tap(Keys.TOGGLE));
        waitTicks(6);
        act(() -> check("handles/H brings the handles back", !GhostRenderer.hidden && Interaction.handleAnchor("ring") != null, "hidden " + GhostRenderer.hidden));
        act(() -> tap(Keys.MAIN));
        waitTicks(3);
        // starting a tool while hidden shows the ghosts again
        act(() -> tap(Keys.TOGGLE));
        waitTicks(4);
        act(() -> check("handles/H hides the ghosts", GhostRenderer.hidden, "hidden " + GhostRenderer.hidden));
        act(() -> tap(Keys.MAIN));
        waitTicks(6);
        act(() -> check("handles/V while hidden shows them and starts editing", !GhostRenderer.hidden && Placements.mode() == Placements.Mode.EDIT, "hidden " + GhostRenderer.hidden + ", mode " + Placements.mode()));
        act(() -> tap(Keys.MAIN));
        waitTicks(3);
        act(() -> tap(Keys.TOGGLE));
        waitTicks(3);
        act(() -> tap(Keys.TOGGLE));
        waitTicks(3);
        act(() -> check("handles/H shows them again", !GhostRenderer.hidden, "hidden " + GhostRenderer.hidden));
        act(() -> {
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
        });
    }

    // ---- scene: look (stills of the handles for design review: idle, hovered, mid-drag, from a few distances)

    static void aimAtHandle(String id) {
        act(() -> {
            Vec3 a = Interaction.handleAnchor(id);
            if (a != null) aim(Minecraft.getInstance(), a.x, a.y, a.z);
        });
        waitTicks(8);
    }

    static void look() {
        Director.clean();
        act(() -> {
            DevCommands.run("/cyanotype sample");
        });
        until("look/blueprint loaded", 200, () -> DevCommands.loadedBlueprint() != null);
        act(() -> {
            DevCommands.run("/cyanotype place 0 " + (G + 1) + " 6");
            first = active();
            Placements.setMode(Placements.Mode.EDIT);
        });
        until("look/baked", 300, GhostRenderer::settled);
        camera(5.5, G + 9, -24, 0, 9);
        waitTicks(14);
        shot("look_far");
        camera(5.5, G + 6, -11, 0, 8);
        waitTicks(14);
        shot("look_mid");
        camera(-13, G + 8, -9, -38, 17);
        waitTicks(14);
        shot("look_angle");
        camera(5.5, G + 6, -11, 0, 8);
        waitTicks(10);
        aimAtHandle("move+x");
        shot("look_hover_arrow");
        aimAtHandle("ring");
        shot("look_hover_ring");
        // mid-drag: the +x arrow two blocks out
        aimAtHandle("move+x");
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        Vec3[] grab = new Vec3[1];
        act(() -> grab[0] = Interaction.handleAnchor("move+x"));
        for (int k = 1; k <= 2; k++) {
            int step = k;
            act(() -> aim(Minecraft.getInstance(), grab[0].x + step, grab[0].y, grab[0].z));
            waitTicks(5);
        }
        shot("look_drag_move");
        act(() -> release(Minecraft.getInstance().options.keyAttack));
        waitTicks(4);
        aimAtHandle("ring");
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        double[][] ring = new double[1][];
        act(() -> ring[0] = Interaction.ringGeometry());
        for (int deg = 15; deg <= 90; deg += 15) {
            int d = deg;
            act(() -> {
                double a = Math.toRadians(d);
                double[] r = ring[0];
                aim(Minecraft.getInstance(), r[0] + Math.cos(a) * r[3], r[1], r[2] + Math.sin(a) * r[3]);
            });
            waitTicks(3);
        }
        shot("look_drag_turn");
        act(() -> release(Minecraft.getInstance().options.keyAttack));
        waitTicks(4);
        act(() -> {
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
        });
    }

    // ---- scene: hints (the HUD shown: what the action bar says while placing and editing)

    static void hints() {
        act(() -> {
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
            Director.hideHud(Minecraft.getInstance(), false);
            DevCommands.run("/cyanotype sample");
        });
        until("hints/blueprint loaded", 200, () -> DevCommands.loadedBlueprint() != null);
        camera(5.5, G + 3, -22, 0, 11);
        waitTicks(12);
        act(() -> DevCommands.run("/cyanotype place"));
        waitTicks(12);
        shot("hint_placing");
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(3);
        act(() -> release(Minecraft.getInstance().options.keyAttack));
        waitTicks(25);
        shot("hint_edit");
        act(() -> check("hints/edit starts with three rows and the help pointer", io.github.profetgit.cyanotype.ui.Chips.count() == 4, io.github.profetgit.cyanotype.ui.Chips.count() + " rows"));
        waitTicks(150);
        act(() -> check("hints/the rest of the keys come in after a quiet while", io.github.profetgit.cyanotype.ui.Chips.count() >= 8, io.github.profetgit.cyanotype.ui.Chips.count() + " rows"));
        shot("hint_edit_all");
        act(() -> {
            Vec3 a = Interaction.handleAnchor("move+x");
            if (a != null) aim(Minecraft.getInstance(), a.x, a.y, a.z);
        });
        waitTicks(6);
        shot("hint_hover");
        act(() -> {
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
            Director.hideHud(Minecraft.getInstance(), true);
        });
    }

    // ---- scene: block safety (a click that locks must not break the block under the crosshair)

    static void safety() {
        act(() -> DevCommands.run("/cyanotype sample"));
        until("safety/blueprint loaded", 200, () -> DevCommands.loadedBlueprint() != null);
        Director.cmd("gamemode creative " + "Builder");
        camera(40.5, G + 1, 3.5, 0, 90);
        waitTicks(20);
        act(() -> {
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
            DevCommands.run("/cyanotype place");
        });
        waitTicks(6);
        act(() -> tap(Keys.MAIN));
        waitTicks(4);
        act(() -> check("safety/V cancels placing and drops the ghost", Placements.mode() == Placements.Mode.IDLE && Placements.all().isEmpty(), "mode " + Placements.mode() + ", placements " + Placements.all().size()));
        act(() -> DevCommands.run("/cyanotype place"));
        waitTicks(6);
        act(() -> hold(Minecraft.getInstance().options.keyAttack));
        waitTicks(6);
        act(() -> release(Minecraft.getInstance().options.keyAttack));
        waitTicks(6);
        act(() -> {
            boolean grass = Minecraft.getInstance().level.getBlockState(new BlockPos(40, G, 3)).is(Blocks.GRASS_BLOCK);
            check("safety/the locking click does not break the block", grass && active() != null && active().locked, "block is " + Minecraft.getInstance().level.getBlockState(new BlockPos(40, G, 3)).getBlock());
        });
        act(() -> {
            for (Placement p : List.copyOf(Placements.all())) Placements.remove(p);
        });
        Director.cmd("gamemode spectator " + "Builder");
    }

    // ---- scenes: persist (save, forget, reload; and a second run that finds them)

    static void persist() {
        act(() -> DevCommands.run("/cyanotype sample"));
        until("persist/blueprint loaded", 200, () -> DevCommands.loadedBlueprint() != null);
        act(() -> {
            DevCommands.run("/cyanotype place 7 " + (G + 1) + " 9");
            Placement p = active();
            p.set(p.origin, new Orientation(Rotation.COUNTERCLOCKWISE_90, net.minecraft.world.level.block.Mirror.NONE));
            p.opacity = 0.4f;
            DevCommands.run("/cyanotype place -30 " + (G + 1) + " 9");
            DevCommands.run("/cyanotype hide");
        });
        waitTicks(30);
        act(() -> {
            java.nio.file.Path file = PlacementStore.fileFor(Session.key());
            boolean there = java.nio.file.Files.isRegularFile(file);
            check("persist/saved after a pause", there, file.toString());
        });
        act(() -> {
            List<Placement> before = List.copyOf(Placements.all());
            PlacementStore.flush(true);
            Placements.clear();
            GhostRenderer.clear();
            PlacementStore.load(Session.key());
            check("persist/placements come back", Placements.all().size() == before.size(), Placements.all().size() + " of " + before.size());
        });
        until("persist/blueprints reloaded", 300, () -> Placements.all().stream().allMatch(Placement::ready));
        act(() -> {
            Placement a = Placements.find("1"), b = Placements.find("2");
            check("persist/first placement as left", a != null && a.origin.equals(new BlockPos(7, G + 1, 9)) && a.orientation.rotation() == Rotation.COUNTERCLOCKWISE_90 && Math.abs(a.opacity - 0.4f) < 1e-6 && a.locked,
                a == null ? "missing" : at(a) + ", " + a.orientation + ", opacity " + a.opacity);
            check("persist/second placement hidden", b != null && !b.visible && b.origin.equals(new BlockPos(-30, G + 1, 9)), b == null ? "missing" : at(b) + ", visible " + b.visible);
            check("persist/names and colours kept", a != null && b != null && !a.name.equals(b.name) && a.accent != b.accent, a == null ? "" : a.name + " / " + (b == null ? "" : b.name));
        });
        until("persist/ghost back", 300, GhostRenderer::settled);
        // a placement that belongs to another dimension is kept but not drawn here
        Placement[] nether = new Placement[1];
        act(() -> {
            Placement a = Placements.find("1");
            nether[0] = new Placement("elsewhere", a.blueprint, a.ref, "minecraft:the_nether", new BlockPos(0, 64, 0), Orientation.NONE);
            nether[0].locked = true;
            Placements.add(nether[0]);
        });
        waitTicks(12);
        act(() -> {
            check("persist/another dimension is not drawn", !GhostRenderer.drawn(nether[0]), "drawn " + GhostRenderer.drawn(nether[0]));
            Placements.remove(nether[0]);
        });
        camera(12, G + 5, -6, 0, 10);
        waitTicks(12);
        shot("persist_back");
    }

    /** First half of the cross-run check: leaves a placement in the save file and quits with it there. */
    static void persistLeave() {
        act(() -> DevCommands.run("/cyanotype sample"));
        until("persist-leave/blueprint loaded", 200, () -> DevCommands.loadedBlueprint() != null);
        act(() -> {
            Placements.clear();
            DevCommands.run("/cyanotype place 11 " + (G + 1) + " 5");
            Placement p = active();
            p.set(p.origin, new Orientation(Rotation.CLOCKWISE_180, net.minecraft.world.level.block.Mirror.NONE));
        });
        waitTicks(40);
        act(() -> {
            PlacementStore.flush(true);
            check("persist-leave/saved", java.nio.file.Files.isRegularFile(PlacementStore.fileFor(Session.key())), String.valueOf(Session.key()));
        });
    }

    /** Second half, in a fresh client: the placement must be there on its own. */
    static void persistReturn() {
        until("persist-return/placement restored", 300, () -> !Placements.all().isEmpty() && Placements.all().stream().allMatch(Placement::ready));
        until("persist-return/ghost drawn", 300, GhostRenderer::settled);
        act(() -> {
            Placement p = Placements.all().isEmpty() ? null : Placements.all().get(0);
            check("persist-return/same place", p != null && p.origin.equals(new BlockPos(11, G + 1, 5)) && p.orientation.rotation() == Rotation.CLOCKWISE_180,
                p == null ? "none" : at(p) + ", " + p.orientation);
        });
        camera(15, G + 5, -8, 0, 10);
        waitTicks(12);
        shot("persist_return");
    }

    static String id(Locale l) {
        return l.toString();
    }
}
