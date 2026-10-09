package io.github.profetgit.buildbuddy.demo;

import static io.github.profetgit.buildbuddy.demo.Director.G;
import static io.github.profetgit.buildbuddy.demo.Director.act;
import static io.github.profetgit.buildbuddy.demo.Director.camera;
import static io.github.profetgit.buildbuddy.demo.Director.check;
import static io.github.profetgit.buildbuddy.demo.Director.shot;
import static io.github.profetgit.buildbuddy.demo.Director.until;
import static io.github.profetgit.buildbuddy.demo.Director.waitTicks;
import static io.github.profetgit.buildbuddy.demo.PlaceScenes.scroll;

import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.interaction.Interaction;
import io.github.profetgit.buildbuddy.interaction.Keys;
import io.github.profetgit.buildbuddy.placement.Orientation;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Edit mode's scroll wheel: it pushes the build along the axis the player looks along, Shift goes five at a time, Ctrl turns,
 * Ctrl+Shift flips, scroll never follows the arrow under the mouse, one run of scrolls is one undo. On a build over a hundred
 * blocks every arrow must be one the player can reach.
 */
final class NudgeScenes {
    private NudgeScenes() {
    }

    static Placement house;

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static void mods(int m) {
        act(() -> Interaction.testModifiers = m);
    }

    static void look(double x, double y, double z, float yaw, float pitch) {
        camera(x, y, z, yaw, pitch);
        waitTicks(10);
    }

    /** Carrying a tall build from the ground: picked up by its roof, followed with the view lowered to the ground, put back with a right click. */
    static void carry() {
        UiScenes.setup();
        act(() -> {
            house = UiScenes.house;
            Placements.setMode(Placements.Mode.EDIT);
        });
        until("carry/baked and drawn", 600, () -> GhostRenderer.verifierOf(house) != null && GhostRenderer.settled() && GhostRenderer.drawn(house));
        // stand on the ground a little way from the house (it stands at x 0..10, z 6..18, base at G+1) and take hold of its roof
        BlockPos[] o = new BlockPos[1];
        look(5.5, G + 1, -6, 0, -10);
        act(() -> {
            o[0] = house.origin;
            PlaceScenes.aim(mc(), 1.5, G + 10, 9);
        });
        waitTicks(6);
        act(() -> check("carry/the roof is under the crosshair, no arrow", Interaction.hoverName().isEmpty(), "hover '" + Interaction.hoverName() + "'"));
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(4);
        act(() -> check("carry/pressing on the roof picks the build up", Interaction.grabbing(), "grabbing " + Interaction.grabbing()));
        // the view goes down to the ground, east of the house: the roof's plane is above the eye and cannot be met, the floor takes over
        act(() -> PlaceScenes.aim(mc(), 12, G + 1, 4));
        waitTicks(5);
        act(() -> check("carry/lowering the view to the ground does not throw the build", house.origin.distSqr(o[0]) <= 2, "moved " + house.origin.subtract(o[0])));
        act(() -> PlaceScenes.aim(mc(), 18, G + 1, 4));
        waitTicks(6);
        shot("carry_0_ground");
        act(() -> {
            BlockPos d = house.origin.subtract(o[0]);
            check("carry/six blocks of ground are six blocks of build", Math.abs(d.getX() - 6) <= 1 && Math.abs(d.getZ()) <= 1 && d.getY() == 0, "moved " + d);
        });
        // Shift (sneak, fly down) does nothing to the height; the wheel lifts a block a notch
        act(() -> Interaction.testModifiers = 1);
        waitTicks(3);
        act(() -> PlaceScenes.aim(mc(), 18, G + 6, 4));
        waitTicks(6);
        act(() -> check("carry/Shift held while carrying does not lift the build", house.origin.getY() == o[0].getY(), "origin " + house.origin + " from " + o[0]));
        act(() -> Interaction.testModifiers = -1);
        act(() -> {
            scroll(mc(), 1);
            scroll(mc(), 1);
            scroll(mc(), 1);
        });
        waitTicks(4);
        act(() -> check("carry/three notches up lift it three blocks", house.origin.getY() == o[0].getY() + 3, "origin " + house.origin + " from " + o[0]));
        act(() -> {
            scroll(mc(), -1);
            scroll(mc(), -1);
            scroll(mc(), -1);
        });
        waitTicks(4);
        act(() -> check("carry/and three down put it back at the height it had", house.origin.getY() == o[0].getY(), "origin " + house.origin + " from " + o[0]));
        // a right click puts it back where it was picked up
        act(() -> PlaceScenes.tap(mc().options.keyUse));
        waitTicks(6);
        act(() -> {
            check("carry/a right click puts it back", house.origin.equals(o[0]) && !Interaction.grabbing(), "origin " + house.origin + ", from " + o[0] + ", grabbing " + Interaction.grabbing());
            PlaceScenes.release(mc().options.keyAttack);
        });
        waitTicks(4);
        act(() -> check("carry/and nothing is left to undo from the cancelled carry", house.origin.equals(o[0]), "origin " + house.origin));
        // from above (flying over it) and while the player moves: the height set stays the height set
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(3);
        look(5.5, G + 30, -20, 0, 35);
        act(() -> {
            o[0] = house.origin;
            PlaceScenes.aim(mc(), 0.7, G + 6, 9);
        });
        waitTicks(6);
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(3);
        act(() -> check("carry/from above: picked up", Interaction.grabbing(), "grabbing " + Interaction.grabbing()));
        int[] ys = new int[1];
        for (int i = 0; i < 12; i++) {
            final int k = i;
            act(() -> {
                // the player flies up and sideways a little each step while the view sweeps across the ground
                var m = mc().player;
                Director.run(mc(), String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f", m.getGameProfile().name(), 5.5 + k * 0.7, G + 30 + k * 0.9, -20 + k * 0.3));
                PlaceScenes.aim(mc(), 14 + k, G + 1, 3 + k % 4);
                if (house.origin.getY() != o[0].getY()) ys[0]++;
            });
            waitTicks(3);
        }
        act(() -> check("carry/flying up and sweeping the view over the ground never changes the height", ys[0] == 0 && house.origin.getY() == o[0].getY(), ys[0] + " frames off the height, origin " + house.origin + " from " + o[0]));
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(4);
        act(() -> check("carry/and it is put down at the same height", house.origin.getY() == o[0].getY(), "origin " + house.origin + " from " + o[0]));
        act(() -> Placements.undo());
        waitTicks(4);
        look(5.5, G + 1, -6, 0, -10);
        act(() -> PlaceScenes.aim(mc(), 1.5, G + 10, 9));
        waitTicks(5);
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(3);
        // taking it away while it is held (the Delete key) leaves nothing to carry and does not fail
        act(() -> PlaceScenes.aim(mc(), 1.5, G + 10, 9));
        waitTicks(5);
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(3);
        act(() -> Placements.removeUndoable(house));
        waitTicks(4);
        act(() -> check("carry/a build removed while held ends the carry", !Interaction.grabbing(), "grabbing"));
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(3);
        act(() -> Placements.undo());
        waitTicks(3);
        UiScenes.teardown();
    }

    static void nudge() {
        UiScenes.setup();
        act(() -> {
            house = UiScenes.house;
            Placements.select(house);
            Placements.setMode(Placements.Mode.EDIT);
        });
        waitTicks(8);
        int[] hotbar = new int[1];
        act(() -> hotbar[0] = mc().player.getInventory().getSelectedSlot());
        BlockPos[] o = new BlockPos[1];
        act(() -> o[0] = house.origin);

        // looking north (yaw 180) from the south: scroll up pushes it away, north (z down)
        look(5.5, G + 6, 40, 180, 8);
        act(() -> o[0] = house.origin);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        act(() -> check("nudge/looking north, scroll up pushes it north", house.origin.equals(o[0].north()), "origin " + house.origin + " from " + o[0]));
        act(() -> scroll(mc(), -2));
        waitTicks(4);
        act(() -> check("nudge/scroll down twice pulls it two back, south", house.origin.equals(o[0].south()), "origin " + house.origin));
        act(() -> check("nudge/the hotbar did not move", mc().player.getInventory().getSelectedSlot() == hotbar[0], "slot " + mc().player.getInventory().getSelectedSlot()));
        shot("nudge_0_north");

        // looking east
        look(-30, G + 6, 14, -90, 8);
        act(() -> o[0] = house.origin);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        act(() -> check("nudge/looking east, scroll up pushes it east", house.origin.equals(o[0].east()), "origin " + house.origin + " from " + o[0]));

        // looking almost straight down: the axis is the vertical one, away is down
        look(5.5, G + 30, 14, 0, 85);
        act(() -> o[0] = house.origin);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        act(() -> check("nudge/looking down, scroll up pushes it down", house.origin.equals(o[0].below()), "origin " + house.origin + " from " + o[0]));
        act(() -> scroll(mc(), -1));
        waitTicks(4);

        // shift: five blocks
        look(5.5, G + 6, 40, 180, 8);
        act(() -> o[0] = house.origin);
        mods(1);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        mods(-1);
        act(() -> check("nudge/shift+scroll pushes five blocks", house.origin.equals(o[0].north(5)), "origin " + house.origin + " from " + o[0]));
        act(() -> scroll(mc(), -1));
        waitTicks(4);
        act(() -> check("nudge/and back again", house.origin.equals(o[0].north(4)), "origin " + house.origin));

        // one run of scrolls is one undo
        waitTicks(30);
        act(() -> o[0] = house.origin);
        act(() -> {
            scroll(mc(), 1);
            scroll(mc(), 1);
            scroll(mc(), 1);
        });
        waitTicks(4);
        act(() -> check("nudge/three quick scrolls moved it three", house.origin.equals(o[0].north(3)), "origin " + house.origin));
        shot("nudge_1_guide");
        PlaceScenes.ctrlTap(Keys.UNDO, false);
        waitTicks(4);
        act(() -> check("nudge/one Ctrl+Z takes all three back", house.origin.equals(o[0]), "origin " + house.origin + " wanted " + o[0]));
        PlaceScenes.ctrlTap(Keys.REDO, false);
        waitTicks(4);
        act(() -> check("nudge/Ctrl+Y does them again", house.origin.equals(o[0].north(3)), "origin " + house.origin));
        // a pause starts a new step
        waitTicks(30);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        PlaceScenes.ctrlTap(Keys.UNDO, false);
        waitTicks(4);
        act(() -> check("nudge/after a pause the next scroll is its own undo", house.origin.equals(o[0].north(3)), "origin " + house.origin));

        // ctrl turns, ctrl+shift flips
        act(() -> o[0] = house.origin);
        mods(2);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        mods(-1);
        act(() -> check("nudge/ctrl+scroll turns it a quarter, clockwise", house.orientation.rotation() == net.minecraft.world.level.block.Rotation.CLOCKWISE_90, String.valueOf(house.orientation)));
        mods(3);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        mods(-1);
        act(() -> check("nudge/ctrl+shift+scroll flips it", house.orientation.isMirrored(), String.valueOf(house.orientation)));
        for (int i = 0; i < 2; i++) PlaceScenes.ctrlTap(Keys.UNDO, false);
        waitTicks(4);
        act(() -> check("nudge/both are undone", house.orientation.equals(Orientation.NONE), String.valueOf(house.orientation)));

        // the arrow under the mouse does not set the axis: the way the player looks does. From far south the east arrow
        // is nearly straight ahead along north, so a push along the arrow would go east and one along the view north
        look(5.5, G + 6, 75, 180, 4);
        act(() -> {
            Vec3 a = Interaction.handleAnchor("move+x");
            PlaceScenes.aim(mc(), a.x, a.y, a.z);
        });
        waitTicks(6);
        act(() -> o[0] = house.origin);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        act(() -> check("nudge/the east arrow is under the mouse", Interaction.hoverName().equals("movex"), "hover '" + Interaction.hoverName() + "'"));
        act(() -> check("nudge/on the east arrow, scroll still pushes the way the player looks (north), not along the arrow", house.origin.equals(o[0].north()), "origin " + house.origin + " from " + o[0]));
        shot("nudge_2_on_arrow");

        // hidden: the wheel is the game's again
        act(() -> PlaceScenes.tap(Keys.TOGGLE));
        waitTicks(4);
        act(() -> {
            o[0] = house.origin;
            scroll(mc(), 1);
        });
        waitTicks(4);
        act(() -> check("nudge/with the ghosts hidden the scroll is not taken", house.origin.equals(o[0]), "origin " + house.origin));
        act(() -> PlaceScenes.tap(Keys.TOGGLE));
        waitTicks(4);
        UiScenes.teardown();
    }

    /** A hundred-block build: every arrow the player needs is near, and the top and bottom ones are not up in the sky. */
    static void mega() {
        Director.clean();
        act(() -> {
            Director.hideHud(mc(), false);
            Placement p = new Placement("mega", Samples.shell(160, 1), "buildbuddy:mega.litematic", Director.DIM, new BlockPos(0, G + 1, 30), Orientation.NONE);
            p.locked = true;
            Placements.add(p);
            house = p;
            Placements.setMode(Placements.Mode.EDIT);
        });
        until("mega/baked and drawn", 1200, () -> GhostRenderer.verifierOf(house) != null && GhostRenderer.settled() && GhostRenderer.drawn(house));
        look(-12, G + 40, 110, -90, 5);
        act(() -> {
            Vec3 w = Interaction.handleAnchor("move-x"), e = Interaction.handleAnchor("move+x");
            double d = w == null ? 1e9 : w.distanceTo(mc().player.getEyePosition());
            check("mega/the west arrow stands within reach of a walk on a 160-block build", d < 40, String.format("%.1f blocks away", d));
            check("mega/the west and east arrows are the same height and mirror each other", w != null && e != null && Math.abs(w.y - e.y) < 1e-6 && Math.abs(w.z - e.z) < 1e-6, w + " / " + e);
            Vec3 up = Interaction.handleAnchor("move+y"), down = Interaction.handleAnchor("move-y");
            check("mega/the up and down arrows stand on one vertical line", up != null && down != null && Math.abs(up.x - down.x) < 1e-6 && Math.abs(up.z - down.z) < 1e-6, up + " / " + down);
        });
        shot("mega_0_arrows");
        // grab the up arrow where it stands and drag it three blocks: it works though the top is 160 blocks up
        act(() -> {
            Vec3 a = Interaction.handleAnchor("move+y");
            if (a != null) PlaceScenes.aim(mc(), a.x, a.y, a.z);
        });
        waitTicks(5);
        act(() -> check("mega/the up arrow can be aimed at", Interaction.hoverName().equals("movey"), "hover '" + Interaction.hoverName() + "'"));
        BlockPos[] o = new BlockPos[1];
        act(() -> o[0] = house.origin);
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(4);
        Vec3[] grab = new Vec3[1];
        act(() -> grab[0] = Interaction.handleAnchor("move+y"));
        for (int k = 1; k <= 3; k++) {
            int step = k;
            act(() -> {
                if (grab[0] != null) PlaceScenes.aim(mc(), grab[0].x, grab[0].y + step, grab[0].z);
            });
            waitTicks(4);
        }
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(4);
        act(() -> check("mega/dragging it three blocks up lifts the whole build three", house.origin.equals(o[0].above(3)), "origin " + house.origin + " from " + o[0]));
        // carry it: grab the wall from far away, look 20 blocks along it and the whole build comes with the crosshair
        look(-150, G + 60, 110, -90, 0);
        waitTicks(6);
        act(() -> {
            Vec3 e = mc().player.getEyePosition();
            PlaceScenes.aim(mc(), 0.0, e.y + 40, 110.0);
            o[0] = house.origin;
        });
        waitTicks(6);
        act(() -> check("mega/the body is under the crosshair, no arrow", Interaction.hoverName().isEmpty(), "hover '" + Interaction.hoverName() + "'"));
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(4);
        act(() -> check("mega/pressing on the body picks it up", Interaction.grabbing(), "grabbing " + Interaction.grabbing()));
        act(() -> {
            Vec3 e = mc().player.getEyePosition();
            PlaceScenes.aim(mc(), 0.0, e.y + 40, 90.0);
        });
        waitTicks(6);
        shot("mega_2_carry");
        // Shift is sneak and fly-down in the game: it does not lift the build, the wheel does
        act(() -> Interaction.testModifiers = 1);
        waitTicks(3);
        act(() -> {
            Vec3 e = mc().player.getEyePosition();
            PlaceScenes.aim(mc(), 0.0, e.y + 40, 90.0);
        });
        waitTicks(6);
        act(() -> {
            check("mega/Shift held while carrying does not change the height", house.origin.getY() == o[0].getY(), "origin " + house.origin + " from " + o[0]);
            Interaction.testModifiers = -1;
            for (int i = 0; i < 10; i++) scroll(mc(), 1);
        });
        waitTicks(4);
        act(() -> PlaceScenes.release(mc().options.keyAttack));
        waitTicks(4);
        act(() -> {
            BlockPos d = house.origin.subtract(o[0]);
            check("mega/carried 20 blocks north and lifted 10 with the wheel (within a block)", Math.abs(d.getX()) <= 1 && Math.abs(d.getZ() + 20) <= 1 && Math.abs(d.getY() - 10) <= 1, "moved " + d);
            check("mega/it is put down on release", !Interaction.grabbing(), "grabbing");
        });
        act(() -> {
            Placements.undo();
        });
        waitTicks(4);
        act(() -> check("mega/one undo brings it all the way back", house.origin.equals(o[0]), "origin " + house.origin + " from " + o[0]));

        // and the scroll does it from anywhere
        look(-12, G + 40, 110, -90, 5);
        act(() -> o[0] = house.origin);
        act(() -> scroll(mc(), 1));
        waitTicks(4);
        act(() -> check("mega/scroll pushes the build away along the view", house.origin.equals(o[0].east()), "origin " + house.origin));
        // from far away the arrows are big, not huge
        look(-150, G + 60, 110, -90, 5);
        shot("mega_1_far");
        act(() -> {
            Vec3 a = Interaction.handleAnchor("move-x");
            check("mega/from far away the arrow is still there to be picked", a != null, String.valueOf(a));
        });
        act(() -> {
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            Director.hideHud(mc(), true);
        });
        waitTicks(4);
    }
}
