package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.G;
import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.camera;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;
import static io.github.profetgit.cyanotype.demo.PlaceScenes.scroll;

import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Keys;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
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
            Placement p = new Placement("mega", Samples.shell(160, 1), "cyanotype:mega.litematic", Director.DIM, new BlockPos(0, G + 1, 30), Orientation.NONE);
            p.locked = true;
            Placements.add(p);
            house = p;
            Placements.setMode(Placements.Mode.EDIT);
        });
        until("mega/baked and drawn", 1200, () -> GhostRenderer.verifierOf(house) != null && GhostRenderer.settled() && GhostRenderer.drawn(house));
        look(-12, G + 40, 110, -90, 5);
        act(() -> {
            for (String id : new String[]{"move-x", "move+y", "move-y"}) {
                Vec3 a = Interaction.handleAnchor(id);
                double d = a == null ? 1e9 : a.distanceTo(mc().player.getEyePosition());
                check("mega/the " + id + " arrow is within reach of a walk on a 160-block build", d < 40, String.format("%.1f blocks away", d));
            }
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
