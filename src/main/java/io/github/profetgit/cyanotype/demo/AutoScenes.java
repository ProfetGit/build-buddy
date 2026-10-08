package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.G;
import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.camera;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.cmd;
import static io.github.profetgit.cyanotype.demo.Director.shot;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;
import static io.github.profetgit.cyanotype.demo.PlaceScenes.aim;
import static io.github.profetgit.cyanotype.demo.SaveScenes.mc;
import static io.github.profetgit.cyanotype.demo.SaveScenes.screen;

import io.github.profetgit.cyanotype.auto.AutoBuilder;
import io.github.profetgit.cyanotype.auto.Rate;
import io.github.profetgit.cyanotype.auto.ServerRules;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.AutoDisclaimerScreen;
import io.github.profetgit.cyanotype.ui.Settings;
import io.github.profetgit.cyanotype.ui.SettingsScreen;
import io.github.profetgit.cyanotype.ui.Tool;
import io.github.profetgit.cyanotype.ui.WheelScreen;
import io.github.profetgit.cyanotype.verify.Counts;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Auto-placing in the real client, against the integrated server (which checks reach and placement for real): a small
 * building with stairs facing four ways, a door, panes, slabs, a wall torch and a hanging lantern is swept into being from
 * the items in the player's inventory. Rate, turning, stopping (damage, screens, no materials), Assist, and the server
 * warning with its memory and block list are each checked.
 */
final class AutoScenes {
    private AutoScenes() {
    }

    static final int OX = 30, OZ = 30;
    static Placement build;

    static Counts counts() {
        return GhostRenderer.verifierOf(build).counts();
    }

    static void items() {
        cmd("clear Builder");
        giveItems();
    }

    static void giveItems() {
        cmd("give Builder minecraft:stone_bricks 64", "give Builder minecraft:oak_planks 64", "give Builder minecraft:oak_log 64", "give Builder minecraft:glass_pane 64",
            "give Builder minecraft:oak_door 8", "give Builder minecraft:oak_slab 64", "give Builder minecraft:oak_stairs 64", "give Builder minecraft:lantern 8", "give Builder minecraft:torch 16", "give Builder minecraft:oak_sign 8", "give Builder minecraft:oak_hanging_sign 8");
        waitTicks(6);
    }

    static void wipe() {
        cmd("fill 22 " + (G + 1) + " 22 44 " + (G + 10) + " 40 air");
        waitTicks(12);
    }

    static void healthy() {
        cmd("effect give Builder minecraft:instant_health 1 10", "effect give Builder minecraft:saturation 5 10");
        waitTicks(10);
    }

    static void at(double x, double z, float yaw) {
        camera(x, G + 1, z, yaw, 18);
        waitTicks(6);
    }

    static void sweepOn() {
        act(() -> AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP));
        waitTicks(2);
    }

    static void off() {
        act(() -> AutoBuilder.stop(mc(), "scene"));
        waitTicks(2);
    }

    /** Walks round the building, a few seconds at each side, facing the way that side's stairs face (or always south, to leave the turning to the mod). */
    static void tour(boolean faceEachSide, int ticks) {
        at(32.5, 27.5, 0);
        waitTicks(ticks);
        at(32.5, 34.8, faceEachSide ? 180 : 0);
        waitTicks(ticks);
        at(27.4, 31.5, faceEachSide ? -90 : 0);
        waitTicks(ticks);
        at(37.2, 31.5, faceEachSide ? 90 : 0);
        waitTicks(ticks);
    }

    static void setup() {
        setup(Samples.autoTest(), "survival", true);
    }

    static void setup(io.github.profetgit.cyanotype.blueprint.Blueprint blueprint, String gamemode, boolean give) {
        Director.clean();
        act(() -> {
            Director.hideHud(mc(), false);
            AutoBuilder.testServer = "";
            Settings.get().autoRate = 4;
            Settings.get().autoTurn = false;
            ServerRules.get().unblock("mc.example.com");
        });
        cmd("gamerule random_tick_speed 0", "gamerule do_mob_loot false", "gamemode " + gamemode + " Builder");
        waitTicks(10);
        healthy();
        wipe();
        if (give) items();
        else cmd("clear Builder");
        act(() -> {
            build = new Placement("auto test", blueprint, "cyanotype:auto-test.litematic", Director.DIM, new BlockPos(OX, G + 1, OZ), Orientation.NONE);
            build.locked = true;
            Placements.add(build);
            UiScenes.house = build;
        });
        until("auto/the build is compared with the world", 400, () -> GhostRenderer.verifierOf(build) != null && GhostRenderer.verifierOf(build).settled());
    }

    /** The cells still missing, with what is wanted there, for the log. */
    static String missingList() {
        StringBuilder sb = new StringBuilder();
        var v = GhostRenderer.verifierOf(build);
        int n = 0;
        for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++) for (int x = 0; x < 5; x++) {
            int wx = OX + x, wy = G + 1 + y, wz = OZ + z;
            byte st = v.statusAt(wx, wy, wz);
            if (st == io.github.profetgit.cyanotype.verify.Verifier.MISSING || st == io.github.profetgit.cyanotype.verify.Verifier.WRONG) {
                if (n++ < 8) sb.append(" [").append(x).append(',').append(y).append(',').append(z).append(' ').append(v.expectedAt(wx, wy, wz).getBlock().getName().getString()).append(st == io.github.profetgit.cyanotype.verify.Verifier.WRONG ? " WRONG" : "").append(']');
            }
        }
        return sb.toString();
    }

    static void done(String what) {
        act(() -> {
            Counts c = counts();
            check(what, c.missing() == 0 && c.wrong() == 0 && c.correct() == build.blueprint.totalBlocks(), c + " of " + build.blueprint.totalBlocks() + ", " + AutoBuilder.detail() + missingList());
        });
    }

    static void auto() {
        setup();
        // ---- the wheel's Build tool opens a panel: Build it myself, Place what I look at, Place everything in reach
        act(() -> check("auto/off to begin with", AutoBuilder.mode() == AutoBuilder.Mode.OFF, "mode " + AutoBuilder.mode()));
        SaveScenes.startThroughTheWheel(Tool.BUILD);
        act(() -> check("auto/the wheel's Build tool opens its panel", screen() instanceof io.github.profetgit.cyanotype.ui.ChoiceScreen cs && cs.count() == 4, String.valueOf(screen())));
        shot("auto_00_build_panel");
        act(() -> SaveScenes.clickScreen(((io.github.profetgit.cyanotype.ui.ChoiceScreen) screen()).anchor(1)));
        waitTicks(6);
        act(() -> check("auto/Place what I look at turns Assist on", AutoBuilder.mode() == AutoBuilder.Mode.ASSIST && screen() == null, "mode " + AutoBuilder.mode()));
        SaveScenes.startThroughTheWheel(Tool.BUILD);
        act(() -> SaveScenes.clickScreen(((io.github.profetgit.cyanotype.ui.ChoiceScreen) screen()).anchor(2)));
        waitTicks(6);
        act(() -> check("auto/Place everything in reach turns Sweep on", AutoBuilder.mode() == AutoBuilder.Mode.SWEEP, "mode " + AutoBuilder.mode()));
        at(32.5, 27.5, 0);
        waitTicks(8);
        shot("auto_0_badge");
        SaveScenes.startThroughTheWheel(Tool.BUILD);
        act(() -> SaveScenes.clickScreen(((io.github.profetgit.cyanotype.ui.ChoiceScreen) screen()).anchor(0)));
        waitTicks(6);
        act(() -> check("auto/Build it myself turns it off", AutoBuilder.mode() == AutoBuilder.Mode.OFF, "mode " + AutoBuilder.mode()));
        // the next-block marker is a switch in the same panel
        SaveScenes.startThroughTheWheel(Tool.BUILD);
        act(() -> {
            var cs = (io.github.profetgit.cyanotype.ui.ChoiceScreen) screen();
            SaveScenes.clickScreen(cs.anchor(4));
            check("auto/the panel's switch turns the next-block marker on", io.github.profetgit.cyanotype.interaction.Interaction.guide, "guide " + io.github.profetgit.cyanotype.interaction.Interaction.guide);
            SaveScenes.clickScreen(cs.anchor(4));
            check("auto/and off again", !io.github.profetgit.cyanotype.interaction.Interaction.guide, "guide " + io.github.profetgit.cyanotype.interaction.Interaction.guide);
            screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0));
        });
        waitTicks(6);

        // ---- sweep: the rate, with the player facing south from the north side (the stairs that face south are placeable, the others are not)
        at(32.5, 27.5, 0);
        sweepOn();
        waitTicks(40);
        act(() -> {
            int n = AutoBuilder.placedCount();
            check("auto/4 a second: 40 ticks place about 8 blocks, not more", n >= 6 && n <= 10, n + " placed in 40 ticks; " + AutoBuilder.detail());
            check("auto/its badge says what it is doing", AutoBuilder.on() && !AutoBuilder.status().isEmpty(), AutoBuilder.detail());
        });
        shot("auto_1_sweeping");
        // faster, to get on with it
        act(() -> Settings.get().autoRate = 20);
        waitTicks(200);
        act(() -> check("auto/nothing placed is wrong", counts().wrong() == 0, counts().toString()));
        shot("auto_2_front_done");
        // walk round: each side faces its own way, so the stairs of that side go in
        at(32.5, 34.8, 180);
        waitTicks(120);
        at(27.4, 31.5, -90);
        waitTicks(120);
        at(37.2, 31.5, 90);
        waitTicks(120);
        at(32.5, 27.5, 0);
        waitTicks(60);
        act(() -> AutoBuilder.stop(mc(), "scene"));
        waitTicks(2);
        done("auto/everything is built, every block right (stairs, door, panes, slabs, torch, lantern)");
        act(() -> {
            long planks = 0;
            for (int i = 0; i < 36; i++) if (mc().player.getInventory().getItem(i).is(net.minecraft.world.item.Items.OAK_PLANKS)) planks += mc().player.getInventory().getItem(i).getCount();
            check("auto/the blocks came out of the inventory", planks < 64, planks + " planks left");
        });
        camera(32.5, G + 5, 25, 0, 30);
        waitTicks(20);
        shot("auto_3_built");

        // ---- facing: from the middle of the north side, facing south, the stairs that face the other ways cannot be placed, and it says so
        off();
        wipe();
        items();
        act(() -> {
            Settings.get().autoTurn = false;
            Settings.get().autoRate = 20;
        });
        act(() -> {
            AutoBuilder.testServer = "strict.example.com";
            ServerRules.get().allow("strict.example.com");
        });
        at(32.5, 28.4, 0);
        sweepOn();
        waitTicks(200);
        act(() -> {
            int[] why = AutoBuilder.reasons();
            check("auto/it says what needs another way of facing", why[0] > 0 && AutoBuilder.status().contains("face"), "wrong facing " + why[0] + ", " + AutoBuilder.status());
            check("auto/and it left those alone, putting nothing wrong", counts().wrong() == 0 && counts().missing() > 0, counts().toString());
        });
        shot("auto_2b_needs_facing");
        off();
        act(() -> {
            ServerRules.get().forget("strict.example.com");
            AutoBuilder.testServer = "";
        });

        // ---- turning: with Turn to face the view turns to the way each block needs, walking only round, always starting from south
        wipe();
        items();
        act(() -> Settings.get().autoTurn = true);
        sweepOn();
        tour(false, 150);
        off();
        done("auto/with Turn to face every block goes in however the player started");

        // ---- materials: no panes in the bag, so the panes stay missing and it stops saying so
        wipe();
        items();
        cmd("clear Builder minecraft:glass_pane");
        waitTicks(6);
        act(() -> Settings.get().autoTurn = true);
        sweepOn();
        tour(false, 150);
        until("auto/it stops by itself when the panes run out", 600, () -> !AutoBuilder.on());
        act(() -> {
            Counts c = counts();
            var v = GhostRenderer.verifierOf(build);
            boolean panes = true;
            for (int[] c4 : new int[][]{{0, 1, 1}, {4, 1, 1}, {0, 2, 1}, {4, 2, 1}}) panes &= v.statusAt(OX + c4[0], G + 1 + c4[1], OZ + c4[2]) == io.github.profetgit.cyanotype.verify.Verifier.MISSING;
            // the roof's middle cannot be reached from the ground without the panes to lean on, which is fine: it is the panes that are asked about
            check("auto/the four panes are what is missing, and nothing is wrong", panes && c.wrong() == 0 && c.missing() >= 4 && c.missing() <= 6 && c.correct() >= 52, c + missingList());
        });

        // ---- the items are in the bag, not on the hotbar: they are swapped into the hand
        wipe();
        cmd("clear Builder");
        for (String filler : new String[]{"dirt", "sand", "gravel", "cobblestone", "stone", "andesite", "diorite", "granite", "netherrack"}) cmd("give Builder minecraft:" + filler + " 64");
        giveItems();
        act(() -> {
            boolean inBag = true;
            for (int i = 0; i < 9; i++) inBag &= !mc().player.getInventory().getItem(i).is(net.minecraft.world.item.Items.OAK_PLANKS);
            check("auto/the planks start in the bag, not on the hotbar", inBag, "hotbar holds " + mc().player.getInventory().getItem(0));
            Settings.get().autoTurn = true;
            Settings.get().autoRate = 20;
        });
        sweepOn();
        tour(true, 150);
        act(() -> AutoBuilder.stop(mc(), "scene"));
        waitTicks(2);
        act(() -> {
            Counts c = counts();
            check("auto/it swaps what it needs in from the bag and builds", c.wrong() == 0 && c.missing() <= 6 && c.correct() >= 52, c + missingList() + "; " + AutoBuilder.detail());
        });

        // ---- damage stops it
        wipe();
        items();
        healthy();
        at(32.5, 28.4, 0);
        sweepOn();
        waitTicks(10);
        act(() -> check("auto/running before the hit", AutoBuilder.on(), AutoBuilder.detail()));
        cmd("damage Builder 2");
        waitTicks(8);
        act(() -> check("auto/damage stops it", !AutoBuilder.on(), AutoBuilder.detail()));
        healthy();

        // ---- a screen stops it, the tool wheel only pauses it
        sweepOn();
        waitTicks(6);
        act(() -> mc().gui.setScreen(new WheelScreen()));
        waitTicks(6);
        act(() -> check("auto/the tool wheel pauses it, no more", AutoBuilder.on(), AutoBuilder.detail()));
        act(() -> mc().gui.setScreen(null));
        waitTicks(4);
        act(() -> mc().gui.setScreen(new PauseScreen(true)));
        waitTicks(6);
        act(() -> check("auto/Esc (the pause screen) stops it", !AutoBuilder.on(), AutoBuilder.detail()));
        act(() -> mc().gui.setScreen(null));
        waitTicks(4);

        // ---- assist: only what the crosshair is on, only while use is held, never past the rate
        wipe();
        items();
        act(() -> {
            Settings.get().autoRate = 4;
            AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
        });
        at(32.5, 28.0, 0);
        act(() -> aim(mc(), 31.5, G + 1.5, 31.5));
        waitTicks(6);
        act(() -> check("auto/assist claims a use press aimed at a ghost block", AutoBuilder.claimsUse(mc()), AutoBuilder.detail()));
        shot("auto_4_assist_aim");
        int[] before = new int[1];
        act(() -> before[0] = AutoBuilder.placedCount());
        waitTicks(20);
        act(() -> check("auto/without the use key nothing is placed", AutoBuilder.placedCount() == before[0], "placed " + (AutoBuilder.placedCount() - before[0])));
        // one press places the block at once, in the very tick of the press
        int[] pressed = new int[1];
        act(() -> pressed[0] = AutoBuilder.placedCount());
        act(() -> PlaceScenes.tap(mc().options.keyUse));
        waitTicks(2);
        act(() -> check("auto/a single press places the block under the crosshair at once", AutoBuilder.placedCount() == pressed[0] + 1, "placed " + (AutoBuilder.placedCount() - pressed[0])));
        // hold use and sweep the crosshair over the floor, one block at a time
        act(() -> PlaceScenes.hold(mc().options.keyUse));
        for (int x = 30; x <= 34; x++) {
            int xx = x;
            act(() -> aim(mc(), xx + 0.5, G + 1.5, 30.5));
            waitTicks(7);
        }
        act(() -> PlaceScenes.release(mc().options.keyUse));
        waitTicks(6);
        act(() -> {
            int n = AutoBuilder.placedCount() - before[0];
            check("auto/holding use over the ghost places what the crosshair goes over", n >= 4 && n <= 8, n + " placed; " + AutoBuilder.detail());
            check("auto/and only blocks of the build, none stacked against them", counts().wrong() == 0 && counts().correct() >= n - 1, counts().toString());
        });
        // pointing at a right block of the build, a held use must not stack the item against it
        int[] stack = new int[1];
        act(() -> {
            aim(mc(), 32.5, G + 1.9, 31.5);
            stack[0] = (int) counts().correct();
        });
        waitTicks(6);
        act(() -> check("auto/a block of the build that is right is not something to build on", AutoBuilder.claimsUse(mc()), AutoBuilder.detail()));
        // aimed at the bare grass in front of the feet with a block in hand: with auto-placing on, nothing goes down there
        int[] total = new int[1];
        act(() -> {
            aim(mc(), 32.5, G + 0.5, 28.9);
            total[0] = AutoBuilder.placedCount();
        });
        waitTicks(6);
        act(() -> check("auto/a press on the bare ground takes it, so the held block cannot be placed there", AutoBuilder.claimsUse(mc()), AutoBuilder.detail()));
        act(() -> PlaceScenes.hold(mc().options.keyUse));
        waitTicks(10);
        act(() -> PlaceScenes.release(mc().options.keyUse));
        waitTicks(4);
        act(() -> check("auto/and nothing was put on the ground", mc().level.getBlockState(new BlockPos(32, G + 1, 28)).isAir() && mc().level.getBlockState(new BlockPos(32, G + 1, 29)).isAir(), mc().level.getBlockState(new BlockPos(32, G + 1, 28)).toString()));
        shot("auto_4b_ground_blocked");
        off();

        // ---- the warning on a multiplayer server, the choice that is remembered, the block list
        wipe();
        items();
        act(() -> AutoBuilder.testServer = "mc.example.com");
        at(32.5, 28.4, 0);
        act(() -> AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP));
        waitTicks(8);
        act(() -> check("auto/on a server the warning comes first, and nothing is on", screen() instanceof AutoDisclaimerScreen && !AutoBuilder.on(), String.valueOf(screen())));
        act(() -> AutoDisclaimerScreen.testWaitedMs = 200L);
        waitTicks(4);
        shot("auto_5_warning_wait");
        act(() -> {
            var s = (AutoDisclaimerScreen) screen();
            check("auto/the enabling button is dead for three seconds", !s.enableReady(), "waited 200 ms");
            click(s.anchor("enable"));
        });
        waitTicks(4);
        act(() -> check("auto/a click on it before then does nothing", screen() instanceof AutoDisclaimerScreen && !AutoBuilder.on(), String.valueOf(screen())));
        // Enter has the focus on "Keep it off"
        act(() -> screen().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        waitTicks(4);
        act(() -> {
            check("auto/Enter keeps it off", screen() == null && !AutoBuilder.on(), String.valueOf(screen()));
            check("auto/and nothing is remembered from that", ServerRules.get().decision("mc.example.com") == ServerRules.Decision.ASK, String.valueOf(ServerRules.get().decision("mc.example.com")));
        });
        act(() -> AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP));
        waitTicks(6);
        act(() -> AutoDisclaimerScreen.testWaitedMs = 3200L);
        waitTicks(6);
        shot("auto_6_warning_ready");
        act(() -> {
            var s = (AutoDisclaimerScreen) screen();
            check("auto/after three seconds it can be enabled", s.enableReady(), "waited 3200 ms");
            click(s.anchor("enable"));
        });
        waitTicks(6);
        act(() -> check("auto/enabled for this visit", AutoBuilder.on() && screen() == null, AutoBuilder.detail()));
        waitTicks(30);
        shot("auto_7_server_badge");
        act(() -> {
            check("auto/it builds on the server too", AutoBuilder.placedCount() > 0, AutoBuilder.detail());
            AutoBuilder.stop(mc(), "scene");
            AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP);
            check("auto/the same visit does not ask again", screen() == null && AutoBuilder.on(), String.valueOf(screen()));
            check("auto/a yes without the tick box is not written down", ServerRules.get().decision("mc.example.com") == ServerRules.Decision.ASK, String.valueOf(ServerRules.get().decision("mc.example.com")));
            AutoBuilder.stop(mc(), "scene");
            // leaving the world forgets the yes of the visit
            AutoBuilder.reset();
            AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP);
        });
        waitTicks(6);
        act(() -> {
            check("auto/a new visit asks again", screen() instanceof AutoDisclaimerScreen && !AutoBuilder.on(), String.valueOf(screen()));
            AutoDisclaimerScreen.testWaitedMs = 3200L;
            var s = (AutoDisclaimerScreen) screen();
            click(s.anchor("ask"));
            check("auto/the tick box toggles", s.dontAsk(), "dont ask " + s.dontAsk());
            click(s.anchor("enable"));
        });
        waitTicks(6);
        act(() -> {
            check("auto/with 'don't ask again' the yes is remembered for the server", ServerRules.get().decision("MC.Example.com:25565") == ServerRules.Decision.ALLOWED, String.valueOf(ServerRules.get().decision("mc.example.com")));
            AutoBuilder.stop(mc(), "scene");
            AutoBuilder.reset();
            AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP);
            check("auto/and next time it just starts", screen() == null && AutoBuilder.on(), String.valueOf(screen()));
            AutoBuilder.stop(mc(), "scene");
        });
        // keep it off for good
        act(() -> {
            AutoBuilder.testServer = "kept.example.com";
            AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
        });
        waitTicks(6);
        act(() -> {
            AutoDisclaimerScreen.testWaitedMs = 3200L;
            var s = (AutoDisclaimerScreen) screen();
            click(s.anchor("ask"));
            click(s.anchor("keep"));
        });
        waitTicks(6);
        act(() -> {
            check("auto/kept off with 'don't ask again' is remembered", ServerRules.get().decision("kept.example.com") == ServerRules.Decision.DECLINED, String.valueOf(ServerRules.get().decision("kept.example.com")));
            AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
            check("auto/and it does not ask or start", screen() == null && !AutoBuilder.on(), String.valueOf(screen()));
            ServerRules.get().forget("kept.example.com");
        });
        // the block list wins, and a block takes effect on a running one
        act(() -> {
            AutoBuilder.testServer = "bad.example.com";
            ServerRules.get().allow("bad.example.com");
            ServerRules.get().block("bad.example.com");
            AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP);
            check("auto/a blocked server never gets it", screen() == null && !AutoBuilder.on(), String.valueOf(screen()));
            ServerRules.get().unblock("bad.example.com");
            ServerRules.get().forget("bad.example.com");
        });
        act(() -> {
            AutoBuilder.testServer = "late.example.com";
            ServerRules.get().allow("late.example.com");
            AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP);
            check("auto/an allowed server starts at once", AutoBuilder.on() && screen() == null, String.valueOf(screen()));
            ServerRules.get().block("late.example.com");
        });
        waitTicks(4);
        act(() -> {
            check("auto/blocking the server while it runs stops it", !AutoBuilder.on(), AutoBuilder.detail());
            ServerRules.get().unblock("late.example.com");
            ServerRules.get().forget("late.example.com");
            AutoBuilder.testServer = "";
        });

        // ---- the settings screens
        act(() -> {
            AutoBuilder.testServer = "mc.example.com";
            ServerRules.get().block("blocked.example.com");
            ServerRules.get().allow("allowed.example.com");
            mc().gui.setScreen(new SettingsScreen());
        });
        waitTicks(8);
        act(() -> {
            var s = (SettingsScreen) screen();
            clickAt(s.anchor("tab:AUTO"));
        });
        waitTicks(8);
        shot("auto_8_settings_auto");
        act(() -> {
            var s = (SettingsScreen) screen();
            clickAt(s.anchor("tab:SERVERS"));
        });
        waitTicks(8);
        shot("auto_9_settings_servers");
        act(() -> {
            var s = (SettingsScreen) screen();
            int[] at = s.anchor("unblock:blocked.example.com");
            check("auto/the blocked server is listed with a way to unblock it", at != null, "anchor " + java.util.Arrays.toString(at));
            if (at != null) clickAt(at);
        });
        waitTicks(6);
        act(() -> check("auto/Unblock takes it off the list", ServerRules.get().decision("blocked.example.com") == ServerRules.Decision.ASK, String.valueOf(ServerRules.get().decision("blocked.example.com"))));
        act(() -> {
            var s = (SettingsScreen) screen();
            int[] at = s.anchor("blockHere");
            check("auto/the current server can be blocked from here", at != null, "anchor " + java.util.Arrays.toString(at));
            if (at != null) clickAt(at);
        });
        waitTicks(6);
        act(() -> check("auto/Block puts the current server on the list", ServerRules.get().decision("mc.example.com") == ServerRules.Decision.BLOCKED, String.valueOf(ServerRules.get().decision("mc.example.com"))));
        act(() -> {
            var s = (SettingsScreen) screen();
            int[] at = s.anchor("forget:allowed.example.com");
            if (at != null) clickAt(at);
        });
        waitTicks(6);
        act(() -> {
            check("auto/Ask again forgets a choice", ServerRules.get().decision("allowed.example.com") == ServerRules.Decision.ASK, String.valueOf(ServerRules.get().decision("allowed.example.com")));
            ServerRules.get().unblock("mc.example.com");
            ServerRules.get().forget("mc.example.com");
            AutoBuilder.testServer = null;
            mc().gui.setScreen(null);
            Settings.get().autoRate = Rate.DEFAULT;
            Settings.get().autoTurn = false;
        });
        waitTicks(4);
        act(() -> {
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            Director.hideHud(mc(), true);
        });
        cmd("gamemode spectator Builder");
    }

    /** Own-world rules: creative with an empty inventory, air placement, any facing, and the same cell refused on a server. */
    static void own() {
        setup(Samples.autoOwn(), "creative", false);
        act(() -> {
            Settings.get().autoRate = 20;
            Settings.get().autoTurn = false;
            boolean empty = true;
            for (int i = 0; i < 36; i++) empty &= mc().player.getInventory().getItem(i).isEmpty();
            check("auto-own/creative starts with an empty inventory", empty, "slot 0 " + mc().player.getInventory().getItem(0));
        });
        var before = new net.minecraft.world.level.block.state.BlockState[1][];
        act(() -> before[0] = snapshot());
        at(33.5, 28.5, 0);
        sweepOn();
        float[] yaw = new float[1];
        for (double[] stop : new double[][]{{33.5, 28.5, 0}, {33.5, 36.5, 180}, {28.5, 32.5, -90}, {38.5, 32.5, 90}}) {
            if (stop[1] != 28.5) at(stop[0], stop[1], (float) stop[2]);
            waitTicks(100);
            act(() -> yaw[0] = mc().player.getYRot());
            waitTicks(2);
            act(() -> check("auto-own/the view does not move while stairs of any facing go in", yaw[0] == mc().player.getYRot(), yaw[0] + " then " + mc().player.getYRot()));
        }
        act(() -> AutoBuilder.stop(mc(), "scene"));
        waitTicks(2);
        done("auto-own/creative, no items: stairs of all four facings and the floating row are all right");
        act(() -> strayCheck("auto-own/the sweep put nothing outside the build", before[0]));
        act(() -> check("auto-own/it did not stop for lack of items", AutoBuilder.placedCount() == build.blueprint.totalBlocks(), AutoBuilder.detail() + " of " + build.blueprint.totalBlocks()));

        // ---- assist on a floating cell
        wipe();
        act(() -> {
            before[0] = snapshot();
            AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
        });
        at(33.5, 38.0, 180);
        act(() -> aim(mc(), 33.5, G + 3.5, 34.5));
        waitTicks(6);
        act(() -> check("auto-own/assist claims a press on a floating ghost cell", AutoBuilder.claimsUse(mc()), AutoBuilder.detail()));
        act(() -> PlaceScenes.tap(mc().options.keyUse));
        waitTicks(6);
        act(() -> check("auto-own/a press places the floating cell, nothing around it", mc().level.getBlockState(new BlockPos(33, G + 3, 34)).is(Blocks.OAK_PLANKS), mc().level.getBlockState(new BlockPos(33, G + 3, 34)) + "; " + AutoBuilder.detail()));
        act(() -> strayCheck("auto-own/assist put nothing outside the build", before[0]));
        off();

        // ---- the same cell on a server: the strict rules, nothing to attach to
        wipe();
        act(() -> {
            AutoBuilder.testServer = "strict.example.com";
            ServerRules.get().allow("strict.example.com");
            AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
        });
        at(33.5, 38.0, 180);
        act(() -> aim(mc(), 33.5, G + 3.5, 34.5));
        waitTicks(6);
        act(() -> PlaceScenes.hold(mc().options.keyUse));
        waitTicks(12);
        act(() -> {
            check("auto-own/on a server the floating cell is not placed", mc().level.getBlockState(new BlockPos(33, G + 3, 34)).isAir(), mc().level.getBlockState(new BlockPos(33, G + 3, 34)).toString());
            check("auto-own/and the badge says it has nothing to attach to", AutoBuilder.status().contains("attach"), "'" + AutoBuilder.status() + "'");
            PlaceScenes.release(mc().options.keyUse);
        });
        waitTicks(2);
        off();
        act(() -> {
            ServerRules.get().forget("strict.example.com");
            AutoBuilder.testServer = null;
            for (Placement p : java.util.List.copyOf(Placements.all())) Placements.remove(p);
            Director.hideHud(mc(), true);
        });
        cmd("gamemode spectator Builder");
    }

    /** The blocks of the build's box grown by 2 each way, in x, y, z order. */
    static net.minecraft.world.level.block.state.BlockState[] snapshot() {
        var b = build.blueprint;
        var out = new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (int y = -2; y < b.sizeY + 2; y++) for (int z = -2; z < b.sizeZ + 2; z++) for (int x = -2; x < b.sizeX + 2; x++) out.add(mc().level.getBlockState(new BlockPos(OX + x, G + 1 + y, OZ + z)));
        return out.toArray(new net.minecraft.world.level.block.state.BlockState[0]);
    }

    /** Every block in that box that is not a cell of the build is what it was before: a stray block next to the build is what the verifier cannot see. */
    static void strayCheck(String what, net.minecraft.world.level.block.state.BlockState[] before) {
        var b = build.blueprint;
        var v = GhostRenderer.verifierOf(build);
        StringBuilder strays = new StringBuilder();
        int i = 0;
        for (int y = -2; y < b.sizeY + 2; y++) for (int z = -2; z < b.sizeZ + 2; z++) for (int x = -2; x < b.sizeX + 2; x++, i++) {
            int wx = OX + x, wy = G + 1 + y, wz = OZ + z;
            if (!v.expectedAt(wx, wy, wz).isAir()) continue;
            var now = mc().level.getBlockState(new BlockPos(wx, wy, wz));
            if (now != before[i]) strays.append(" [").append(wx).append(',').append(wy).append(',').append(wz).append(' ').append(now).append(']');
        }
        check(what, strays.length() == 0, strays.length() == 0 ? "box " + before.length + " cells" : strays.toString());
    }

    private static void click(int[] at) {
        var s = screen();
        var info = new net.minecraft.client.input.MouseButtonInfo(0, 0);
        s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info), false);
        s.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], info));
    }

    private static void clickAt(int[] at) {
        click(at);
    }
}
