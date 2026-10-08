package io.github.profetgit.cyanotype.demo;

import static io.github.profetgit.cyanotype.demo.Director.G;
import static io.github.profetgit.cyanotype.demo.Director.act;
import static io.github.profetgit.cyanotype.demo.Director.camera;
import static io.github.profetgit.cyanotype.demo.Director.check;
import static io.github.profetgit.cyanotype.demo.Director.cmd;
import static io.github.profetgit.cyanotype.demo.Director.until;
import static io.github.profetgit.cyanotype.demo.Director.waitTicks;
import static io.github.profetgit.cyanotype.demo.PlaceScenes.aim;
import static io.github.profetgit.cyanotype.demo.SaveScenes.mc;
import static io.github.profetgit.cyanotype.demo.SaveScenes.screen;

import io.github.profetgit.cyanotype.auto.AutoBuilder;
import io.github.profetgit.cyanotype.auto.ServerRules;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.interaction.Keys;
import io.github.profetgit.cyanotype.placement.Orientation;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.Settings;
import io.github.profetgit.cyanotype.ui.Ui;
import io.github.profetgit.cyanotype.verify.Verifier;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A finished build's ghost going away, Assist placing only ghost blocks, and the Smart Pick key, in the real client.
 */
final class StrictScenes {
    private StrictScenes() {
    }

    private static int size = 3;

    private static void put(int ox, int oz) {
        Placement[] out = new Placement[1];
        act(() -> {
            out[0] = new Placement("tiny", Samples.uniform(size, 2, size), "cyanotype:tiny.litematic", Director.DIM, new BlockPos(ox, G + 1, oz), Orientation.NONE);
            out[0].locked = true;
            Placements.add(out[0]);
            AutoScenes.build = out[0];
        });
    }

    private static void fresh() {
        Director.clean();
        AutoScenes.wipe();
        put(AutoScenes.OX, AutoScenes.OZ);
        until("finish/the tiny build is compared with the world", 400, () -> AutoScenes.build != null && GhostRenderer.verifierOf(AutoScenes.build) != null && GhostRenderer.verifierOf(AutoScenes.build).settled());
    }

    private static Verifier v() {
        return GhostRenderer.verifierOf(AutoScenes.build);
    }

    private static void complete() {
        Director.add(mc -> {
            Director.run(mc, "fill " + AutoScenes.OX + " " + (G + 1) + " " + AutoScenes.OZ + " " + (AutoScenes.OX + size - 1) + " " + (G + 2) + " " + (AutoScenes.OZ + size - 1) + " minecraft:stone_bricks");
            return true;
        });
    }

    /** The ghost of a finished build is taken away, undoably, and only when there was something to build. */
    static void finish() {
        act(() -> Interaction.keepFinished = false);
        AutoScenes.setup(Samples.uniform(3, 2, 3), "creative", false);
        Placement[] first = new Placement[1];
        act(() -> {
            first[0] = AutoScenes.build;
            check("finish/a build over bare ground has work to do", v().hadWork() && v().phase() == Verifier.Phase.BUILDING, String.valueOf(v().phase()));
        });
        complete();
        until("finish/the build is done", 300, () -> v() != null && v().phase() == Verifier.Phase.DONE);
        waitTicks(20);
        act(() -> check("finish/the ghost is still there a second after the build is done (the moment lands first)", Placements.all().contains(first[0]), "gone"));
        until("finish/about two seconds after done the ghost is gone", 60, () -> !Placements.all().contains(first[0]));
        act(() -> {
            check("finish/nothing is active any more", Placements.active() == null, String.valueOf(Placements.active()));
            check("finish/and it can be undone", Placements.canUndo(), "no undo");
        });
        PlaceScenes.ctrlTap(Keys.UNDO, false);
        act(() -> check("finish/Ctrl+Z brings the ghost back", Placements.all().contains(first[0]) && Placements.active() == first[0], "all " + Placements.all().size()));
        waitTicks(100);
        act(() -> check("finish/and it stays: a build that comes back done is not removed a second time", Placements.all().contains(first[0]), "gone again"));

        // put down over an identical build: it must not vanish the instant it is locked
        Director.clean();
        put(AutoScenes.OX, AutoScenes.OZ);
        until("finish/an identical build is compared", 400, () -> AutoScenes.build != null && v() != null && v().settled());
        waitTicks(120);
        act(() -> {
            check("finish/a ghost put over an identical build stays", Placements.all().contains(AutoScenes.build), "removed");
            check("finish/it is done but had nothing to build", v().phase() == Verifier.Phase.DONE && !v().hadWork(), v().phase() + " hadWork " + v().hadWork());
        });

        // auto-placing building it stops quietly
        fresh();
        act(() -> {
            Settings.get().autoRate = 20;
            AutoBuilder.testServer = "";
            AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
            check("finish/assist is on", AutoBuilder.on(), AutoBuilder.detail());
        });
        complete();
        until("finish/(assist) the ghost is gone", 300, () -> !Placements.all().contains(AutoScenes.build));
        act(() -> {
            check("finish/auto-placing stopped with the reason", !AutoBuilder.on() && AutoBuilder.stoppedWhy().equals("Build finished"), "on " + AutoBuilder.on() + " why '" + AutoBuilder.stoppedWhy() + "'");
            check("finish/the placement was not left active or in a mode", Placements.active() == null && Placements.mode() == Placements.Mode.IDLE, Placements.mode().toString());
        });

        // edit mode ends cleanly
        fresh();
        act(() -> {
            Placements.select(AutoScenes.build);
            Placements.setMode(Placements.Mode.EDIT);
        });
        complete();
        until("finish/(edit mode) the ghost is gone", 300, () -> !Placements.all().contains(AutoScenes.build));
        act(() -> check("finish/edit mode ended with it", Placements.mode() == Placements.Mode.IDLE && !Interaction.grabbing(), Placements.mode().toString()));

        // layers mode ends cleanly
        fresh();
        act(() -> {
            Placements.select(AutoScenes.build);
            Placements.setMode(Placements.Mode.LAYERS);
        });
        complete();
        until("finish/(layers mode) the ghost is gone", 300, () -> !Placements.all().contains(AutoScenes.build));
        act(() -> check("finish/layers mode ended with it", Placements.mode() == Placements.Mode.IDLE, Placements.mode().toString()));

        // a carry in progress ends cleanly
        act(() -> size = 9);
        fresh();
        act(() -> {
            Placements.select(AutoScenes.build);
            Placements.setMode(Placements.Mode.EDIT);
        });
        camera(28.0, G + 5, 27.5, 0, 0);
        waitTicks(6);
        act(() -> aim(mc(), 31.0, G + 2.99, 31.0));
        waitTicks(4);
        act(() -> PlaceScenes.hold(mc().options.keyAttack));
        waitTicks(4);
        act(() -> check("finish/(carry) the build is being carried", Interaction.grabbing(), "grabbing " + Interaction.grabbing() + " hover '" + Interaction.hoverName() + "'"));
        complete();
        until("finish/(carry) the ghost is gone", 300, () -> !Placements.all().contains(AutoScenes.build));
        act(() -> {
            check("finish/the carry ended with it", !Interaction.grabbing() && Placements.mode() == Placements.Mode.IDLE, "grabbing " + Interaction.grabbing() + " mode " + Placements.mode());
            PlaceScenes.release(mc().options.keyAttack);
        });
        waitTicks(4);
        act(() -> {
            size = 3;
            AutoBuilder.testServer = null;
            Director.hideHud(mc(), true);
        });
        Director.clean();
        act(() -> Interaction.keepFinished = true);
        cmd("gamemode spectator Builder");
    }

    // ---- Assist places only ghost blocks

    private static BlockState[] box() {
        java.util.List<BlockState> out = new java.util.ArrayList<>();
        for (int y = G + 1; y <= G + 4; y++) for (int z = 22; z <= 32; z++) for (int x = 20; x <= 28; x++) out.add(mc().level.getBlockState(new BlockPos(x, y, z)));
        return out.toArray(new BlockState[0]);
    }

    private static int changed(BlockState[] before) {
        BlockState[] now = box();
        int n = 0;
        for (int i = 0; i < now.length; i++) if (now[i] != before[i]) n++;
        return n;
    }

    /** Use held for a moment, aimed at the grass beside the ghost, with whatever the commands put in the hands: nothing may land in the box. */
    private static void stray(String label, String... hands) {
        cmd("fill 20 " + (G + 1) + " 22 28 " + (G + 4) + " 32 air");
        cmd(hands);
        camera(25.5, G + 1, 24.5, 0, 40);
        waitTicks(8);
        BlockState[][] before = new BlockState[1][];
        act(() -> {
            before[0] = box();
            aim(mc(), 25.5, G + 1.0, 27.5);
        });
        waitTicks(2);
        act(() -> PlaceScenes.hold(mc().options.keyUse));
        waitTicks(14);
        act(() -> PlaceScenes.release(mc().options.keyUse));
        waitTicks(3);
        act(() -> PlaceScenes.tap(mc().options.keyUse));
        waitTicks(4);
        act(() -> {
            int n = changed(before[0]);
            check("assist-strict/" + label + ": no block appeared outside the ghost", n == 0, n + " cells changed; " + AutoBuilder.detail());
        });
    }

    private static void strayRound(String gm) {
        String[] cobble = {"item replace entity Builder weapon.mainhand with minecraft:cobblestone 64"};
        String[] stick = {"item replace entity Builder weapon.mainhand with minecraft:stick"};
        String[] empty = {"item replace entity Builder weapon.mainhand with minecraft:air", "item replace entity Builder weapon.offhand with minecraft:air"};
        String[] offhand = {"item replace entity Builder weapon.mainhand with minecraft:stick", "item replace entity Builder weapon.offhand with minecraft:cobblestone 64"};
        String[] clearOff = {"item replace entity Builder weapon.offhand with minecraft:air"};
        for (String state : new String[]{"idle", "edit", "none"}) {
            act(() -> {
                switch (state) {
                    case "idle" -> {
                        Placements.select(AutoScenes.build);
                        Placements.setMode(Placements.Mode.IDLE);
                    }
                    case "edit" -> {
                        Placements.select(AutoScenes.build);
                        Placements.setMode(Placements.Mode.EDIT);
                    }
                    default -> {
                        Placements.setMode(Placements.Mode.IDLE);
                        Placements.select(null);
                    }
                }
            });
            String p = gm + "/" + state + "/";
            stray(p + "a block in hand", cobble);
            stray(p + "a stick in hand", stick);
            stray(p + "an empty hand", empty);
            stray(p + "a block in the off hand", offhand);
            cmd(clearOff);
        }
    }

    static void assistStrict() {
        for (String gm : new String[]{"creative", "survival"}) {
            AutoScenes.setup(Samples.autoTest(), gm, true);
            act(() -> {
                Settings.get().autoRate = 20;
                AutoBuilder.testServer = "";
                AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
                check("assist-strict/" + gm + ": assist is on", AutoBuilder.on(), AutoBuilder.detail());
            });
            strayRound(gm);
            AutoScenes.off();
        }

        // the sweep: use held, the crosshair over a row of ghost cells, each placed once and with the ghost's own block
        AutoScenes.setup(Samples.autoTest(), "survival", true);
        act(() -> {
            Settings.get().autoRate = 20;
            AutoBuilder.testServer = "";
            AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
        });
        cmd("item replace entity Builder hotbar.8 with minecraft:cobblestone 64");
        waitTicks(4);
        act(() -> mc().player.getInventory().setSelectedSlot(8));
        AutoScenes.at(32.5, 28.0, 0);
        int[] placed = new int[1];
        act(() -> {
            placed[0] = AutoBuilder.placedCount();
            aim(mc(), 30.5, G + 1.5, 30.5);
        });
        waitTicks(4);
        act(() -> PlaceScenes.hold(mc().options.keyUse));
        for (int x = 30; x <= 34; x++) {
            int xx = x;
            act(() -> aim(mc(), xx + 0.5, G + 1.5, 30.5));
            waitTicks(7);
        }
        act(() -> PlaceScenes.release(mc().options.keyUse));
        waitTicks(6);
        act(() -> {
            check("assist-strict/sweep: five cells, five blocks, each once", AutoBuilder.placedCount() - placed[0] == 5, (AutoBuilder.placedCount() - placed[0]) + " placed; " + AutoBuilder.detail());
            StringBuilder bad = new StringBuilder();
            for (int x = 30; x <= 34; x++) if (!mc().level.getBlockState(new BlockPos(x, G + 1, 30)).is(Blocks.STONE_BRICKS)) bad.append(' ').append(x).append('=').append(mc().level.getBlockState(new BlockPos(x, G + 1, 30)));
            check("assist-strict/sweep: every one is the ghost's own block, not the cobblestone in hand", bad.length() == 0, bad.toString());
            Verifier ver = GhostRenderer.verifierOf(AutoScenes.build);
            StringBuilder stray = new StringBuilder();
            for (int y = G + 1; y <= G + 6; y++) for (int z = 24; z <= 36; z++) for (int x = 26; x <= 38; x++) {
                BlockState s = mc().level.getBlockState(new BlockPos(x, y, z));
                if (!s.isAir() && !s.equals(ver.expectedAt(x, y, z))) stray.append(" [").append(x).append(',').append(y).append(',').append(z).append(' ').append(s).append(']');
            }
            check("assist-strict/sweep: nothing anywhere else", stray.length() == 0, stray.toString());
        });
        AutoScenes.off();
        act(() -> {
            AutoBuilder.testServer = null;
            Director.hideHud(mc(), true);
        });
        Director.clean();
        cmd("gamemode spectator Builder");
    }

    // ---- the Smart Pick key

    static void pickKey() {
        Director.clean();
        cmd("gamemode creative Builder");
        waitTicks(6);
        act(() -> {
            Director.hideHud(mc(), false);
            GhostRenderer.hidden = false;
            Placements.setMode(Placements.Mode.IDLE);
            check("pick-key/the key is K by default", Keys.PICK.getDefaultKey().getValue() == com.mojang.blaze3d.platform.InputConstants.KEY_K && Ui.keyName(Keys.PICK).length() > 0, Ui.keyName(Keys.PICK));
        });
        act(() -> PlaceScenes.tap(Keys.PICK));
        waitTicks(4);
        act(() -> check("pick-key/K starts Smart Pick", Placements.mode() == Placements.Mode.PICK, String.valueOf(Placements.mode())));
        act(() -> PlaceScenes.tap(Keys.PICK));
        waitTicks(4);
        act(() -> check("pick-key/K again cancels it", Placements.mode() == Placements.Mode.IDLE, String.valueOf(Placements.mode())));
        act(() -> GhostRenderer.hidden = true);
        act(() -> PlaceScenes.tap(Keys.PICK));
        waitTicks(4);
        act(() -> check("pick-key/inert while the ghosts are hidden", Placements.mode() == Placements.Mode.IDLE, String.valueOf(Placements.mode())));
        act(() -> GhostRenderer.hidden = false);
        act(() -> mc().gui.setScreen(new PauseScreen(true)));
        waitTicks(4);
        act(() -> PlaceScenes.tap(Keys.PICK));
        waitTicks(4);
        act(() -> mc().gui.setScreen(null));
        waitTicks(4);
        act(() -> check("pick-key/inert while a screen is open (and the press is not kept for later)", Placements.mode() == Placements.Mode.IDLE, String.valueOf(Placements.mode())));
        act(() -> PlaceScenes.tap(Keys.PICK));
        waitTicks(4);
        act(() -> check("pick-key/and works again after", Placements.mode() == Placements.Mode.PICK, String.valueOf(Placements.mode())));
        act(() -> PlaceScenes.tap(Keys.MAIN));
        waitTicks(4);
        act(() -> {
            check("pick-key/the main key still cancels it too", Placements.mode() == Placements.Mode.IDLE, String.valueOf(Placements.mode()));
            Director.hideHud(mc(), true);
        });
        cmd("gamemode spectator Builder");
    }

    // ---- slabs, the interact lock, signs, layers

    private static final String STRICT = "strict.example.com";
    private static final int OX = AutoScenes.OX, OZ = AutoScenes.OZ;

    /** Every cell of the build that is not what it wants, for the log. */
    private static String unfinished() {
        StringBuilder sb = new StringBuilder();
        var b = AutoScenes.build.blueprint;
        var ver = v();
        int n = 0;
        for (int y = 0; y < b.sizeY; y++) for (int z = 0; z < b.sizeZ; z++) for (int x = 0; x < b.sizeX; x++) {
            int wx = OX + x, wy = G + 1 + y, wz = OZ + z;
            byte st = ver.statusAt(wx, wy, wz);
            if (st == Verifier.MISSING || st == Verifier.WRONG) {
                if (n++ < 10) sb.append(" [").append(x).append(',').append(y).append(',').append(z).append(' ').append(ver.expectedAt(wx, wy, wz)).append(" now ").append(mc().level.getBlockState(new BlockPos(wx, wy, wz))).append(']');
            }
        }
        return n == 0 ? "" : " (" + n + " not right:" + sb + ")";
    }

    private static void complete(String what) {
        act(() -> {
            var c = v().counts();
            check(what, c.missing() == 0 && c.wrong() == 0 && c.correct() == AutoScenes.build.blueprint.totalBlocks(), c + " of " + AutoScenes.build.blueprint.totalBlocks() + "; " + AutoBuilder.detail() + unfinished());
        });
    }

    /** No mode of the placement, so no handle on the build takes a press before auto-placing does. */
    private static void idle() {
        act(() -> {
            Placements.select(AutoScenes.build);
            Placements.setMode(Placements.Mode.IDLE);
        });
    }

    private static void server(boolean own) {
        act(() -> {
            if (own) {
                AutoBuilder.testServer = "";
            } else {
                AutoBuilder.testServer = STRICT;
                ServerRules.get().allow(STRICT);
            }
        });
    }

    private static void leaveServer() {
        act(() -> {
            ServerRules.get().forget(STRICT);
            AutoBuilder.testServer = null;
        });
    }

    /** The floor, the wall and the roof of the slab build, set by commands, so Assist is left with the slabs. */
    private static void slabBase() {
        cmd("fill " + OX + " " + (G + 1) + " " + OZ + " " + (OX + 8) + " " + (G + 1) + " " + (OZ + 3) + " minecraft:stone_bricks",
            "fill " + OX + " " + (G + 2) + " " + (OZ + 1) + " " + (OX + 8) + " " + (G + 3) + " " + (OZ + 1) + " minecraft:oak_planks",
            "fill " + OX + " " + (G + 4) + " " + OZ + " " + (OX + 8) + " " + (G + 4) + " " + (OZ + 1) + " minecraft:oak_planks",
            "setblock " + (OX + 7) + " " + (G + 2) + " " + OZ + " minecraft:oak_planks");
        waitTicks(8);
    }

    /** Stands in front of a cell of the build (or behind it, for the cells at the back) and aims at its middle. */
    private static void aimAtCell(int cx, int cy, int cz, boolean back) {
        double x = OX + cx + 0.5, z = back ? OZ + 5.4 : OZ - 1.8;
        camera(x, G + 1, z, back ? 180 : 0, 0);
        waitTicks(4);
        act(() -> aim(mc(), OX + cx + 0.5, G + 1 + cy + 0.5, OZ + cz + 0.5));
        waitTicks(3);
    }

    private static void press(int cx, int cy, int cz, boolean back, int times) {
        for (int i = 0; i < times; i++) {
            aimAtCell(cx, cy, cz, back);
            act(() -> PlaceScenes.tap(mc().options.keyUse));
            waitTicks(12);
            act(() -> System.out.println("[cydemo] press " + cx + "," + cy + "," + cz + " -> " + mc().level.getBlockState(new BlockPos(OX + cx, G + 1 + cy, OZ + cz)) + "; " + AutoBuilder.detail()));
        }
    }

    static void slabs() {
        for (String gm : new String[]{"survival", "creative"}) {
            for (boolean own : new boolean[]{true, false}) {
                String tag = "slabs/" + gm + "/" + (own ? "own" : "strict") + ": ";
                String only = System.getenv("SLABS");
                if (only != null && !only.equals(gm + "/" + (own ? "own" : "strict"))) continue;
                AutoScenes.setup(Samples.slabs(own), gm, gm.equals("survival"));
                server(own);
                idle();
                act(() -> {
                    Settings.get().autoRate = 20;
                    Settings.get().autoTurn = false;
                });
                var before = new BlockState[1][];
                act(() -> before[0] = AutoScenes.snapshot());
                AutoScenes.sweepOn();
                for (int round = 0; round < 2; round++) {
                    for (double sx : new double[]{1.5, 4.5, 7.5}) {
                        camera(OX + sx, G + 1, OZ - 1.5, 0, 18);
                        waitTicks(60);
                    }
                    for (double sx : new double[]{2.5, 6.5}) {
                        camera(OX + sx, G + 1, OZ + 5.2, 180, 18);
                        waitTicks(60);
                    }
                    for (double sx : new double[]{0.5, 4.5, 8.5}) {
                        camera(OX + sx, G + 2.05, OZ + 3.5, 180, 30);
                        waitTicks(50);
                    }
                }
                AutoScenes.off();
                complete(tag + "sweep builds bottom, top and double slabs");
                act(() -> AutoScenes.strayCheck(tag + "sweep put nothing outside the build", before[0]));

                // assist: the slab cells one press at a time, the double ones twice
                AutoScenes.wipe();
                if (gm.equals("survival")) AutoScenes.items();
                else cmd("clear Builder");
                slabBase();
                act(() -> AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST));
                press(1, 1, 0, false, 1);
                press(3, 1, 0, false, 1);
                press(5, 1, 0, false, 2);
                press(1, 2, 0, false, 1);
                press(3, 2, 0, false, 2);
                press(5, 2, 0, false, 1);
                press(7, 2, 0, false, 1);
                press(7, 1, 3, true, 1);
                press(7, 2, 3, true, 1);
                if (own) {
                    press(1, 2, 3, true, 1);
                    press(3, 2, 3, true, 2);
                    press(5, 2, 3, true, 1);
                }
                complete(tag + "assist places bottom, top and double slabs");
                AutoScenes.off();
                leaveServer();
            }
        }
        act(() -> Director.hideHud(mc(), true));
        Director.clean();
        cmd("gamemode spectator Builder");
    }

    // ---- a right click never interacts while auto-placing is on

    private static int frameRotation() {
        var frames = mc().level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new net.minecraft.world.phys.AABB(OX - 12, G, OZ - 12, OX + 20, G + 6, OZ + 3));
        return frames.isEmpty() ? -1 : frames.get(0).getRotation();
    }

    private static boolean doorOpen() {
        return mc().level.getBlockState(new BlockPos(OX - 2, G + 1, OZ - 4)).getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
    }

    private static void closeScreen() {
        act(() -> {
            if (screen() != null) mc().player.closeContainer();
            if (screen() != null) mc().gui.setScreen(null);
        });
        waitTicks(6);
    }

    /** Stands two blocks south of a prop at (x, z) and aims at it, then presses use once. */
    private static void pressAt(double x, double y, double z) {
        camera(x, G + 1, z + 2.3, 180, 0);
        waitTicks(4);
        act(() -> aim(mc(), x, y, z));
        waitTicks(5);
        act(() -> PlaceScenes.tap(mc().options.keyUse));
        waitTicks(8);
    }

    static void interactLock() {
        AutoScenes.setup(Samples.autoTest(), "survival", true);
        idle();
        act(() -> Settings.get().autoRate = 20);
        double z0 = OZ - 4;
        cmd("setblock " + (OX - 6) + " " + (G + 1) + " " + (OZ - 4) + " minecraft:barrel",
            "setblock " + (OX - 4) + " " + (G + 1) + " " + (OZ - 4) + " minecraft:chest[facing=south]",
            "setblock " + (OX - 2) + " " + (G + 1) + " " + (OZ - 4) + " minecraft:oak_door[half=lower,facing=south,open=false]",
            "setblock " + (OX - 2) + " " + (G + 2) + " " + (OZ - 4) + " minecraft:oak_door[half=upper,facing=south,open=false]",
            "setblock " + (OX + 2) + " " + (G + 1) + " " + (OZ - 4) + " minecraft:stone",
            "summon item_frame " + (OX + 2) + " " + (G + 1) + " " + (OZ - 3) + " {Facing:3b,Invulnerable:1b,Item:{id:\"minecraft:stick\",count:1}}",
            "summon wandering_trader " + (OX + 4.5) + " " + (G + 1) + " " + (OZ - 4.5) + " {NoAI:1b,Silent:1b,Invulnerable:1b,DespawnDelay:100000}");
        waitTicks(20);
        double bx = OX - 6 + 0.5, cx = OX - 4 + 0.5, dx = OX - 2 + 0.5, fx = OX + 2 + 0.5, tx = OX + 4.5;
        int[] rot = new int[1];
        act(() -> rot[0] = frameRotation());

        // auto-placing off: the controls
        pressAt(bx, G + 1.5, z0 + 0.5);
        act(() -> check("interact-lock/off: a right click on a barrel opens it", screen() instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen, String.valueOf(screen())));
        closeScreen();
        pressAt(dx, G + 1.5, z0 + 0.5);
        act(() -> check("interact-lock/off: a right click on a door opens it", doorOpen(), "open " + doorOpen()));
        pressAt(dx, G + 1.5, z0 + 0.5);
        pressAt(fx, G + 1.5, z0 + 1.05);
        act(() -> check("interact-lock/off: a right click on an item frame turns the item", frameRotation() != rot[0], "rotation " + rot[0] + " then " + frameRotation()));
        pressAt(tx, G + 2.0, z0 - 0.5);
        act(() -> check("interact-lock/off: a right click on a trader opens the trade screen", screen() instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen, String.valueOf(screen())));
        closeScreen();

        cmd("setblock " + (OX - 2) + " " + (G + 1) + " " + (OZ - 4) + " minecraft:oak_door[half=lower,facing=south,open=false]", "setblock " + (OX - 2) + " " + (G + 2) + " " + (OZ - 4) + " minecraft:oak_door[half=upper,facing=south,open=false]");
        waitTicks(6);
        for (AutoBuilder.Mode mode : new AutoBuilder.Mode[]{AutoBuilder.Mode.ASSIST, AutoBuilder.Mode.SWEEP}) {
            String tag = "interact-lock/" + mode.name().toLowerCase() + ": ";
            act(() -> {
                AutoBuilder.request(mc(), mode);
                rot[0] = frameRotation();
            });
            for (String hands : new String[]{"a block in hand", "an empty hand", "a block in the off hand"}) {
                act(() -> {
                    switch (hands) {
                        case "a block in hand" -> {
                            mc().player.getInventory().setSelectedSlot(0);
                        }
                        default -> {
                        }
                    }
                });
                if (hands.equals("an empty hand")) cmd("item replace entity Builder weapon.mainhand with minecraft:air");
                if (hands.equals("a block in the off hand")) cmd("item replace entity Builder weapon.mainhand with minecraft:stick", "item replace entity Builder weapon.offhand with minecraft:cobblestone 64");
                waitTicks(4);
                String t = tag + hands + ": ";
                pressAt(bx, G + 1.5, z0 + 0.5);
                act(() -> check(t + "a barrel stays shut", screen() == null && AutoBuilder.on(), screen() + " " + AutoBuilder.detail()));
                pressAt(cx, G + 1.5, z0 + 0.5);
                act(() -> check(t + "a chest stays shut", screen() == null && AutoBuilder.on(), screen() + " " + AutoBuilder.detail()));
                pressAt(dx, G + 1.5, z0 + 0.5);
                act(() -> check(t + "a door stays closed", !doorOpen() && screen() == null && AutoBuilder.on(), "open " + doorOpen() + " " + screen()));
                pressAt(fx, G + 1.5, z0 + 1.05);
                act(() -> check(t + "an item frame is not turned", frameRotation() == rot[0] && AutoBuilder.on(), "rotation " + rot[0] + " then " + frameRotation()));
                pressAt(tx, G + 2.0, z0 - 0.5);
                act(() -> check(t + "a trader does not trade", screen() == null && AutoBuilder.on(), screen() + " " + AutoBuilder.detail()));
                pressAt(OX + 8.5, G + 1.0, OZ - 6.5);
                act(() -> check(t + "a press on the ground does nothing either", screen() == null && AutoBuilder.on(), screen() + " " + AutoBuilder.detail()));
                cmd("item replace entity Builder weapon.offhand with minecraft:air");
            }
            AutoScenes.off();
        }
        act(() -> {
            AutoBuilder.testServer = null;
            Director.hideHud(mc(), true);
        });
        cmd("kill @e[type=!player]", "setblock " + (OX - 6) + " " + (G + 1) + " " + (OZ - 4) + " air", "setblock " + (OX - 4) + " " + (G + 1) + " " + (OZ - 4) + " air", "setblock " + (OX - 2) + " " + (G + 2) + " " + (OZ - 4) + " air", "setblock " + (OX - 2) + " " + (G + 1) + " " + (OZ - 4) + " air", "setblock " + (OX + 2) + " " + (G + 1) + " " + (OZ - 4) + " air");
        Director.clean();
        cmd("gamemode spectator Builder");
    }

    // ---- signs

    private static String signLines(int x, int y, int z) {
        if (!(mc().level.getBlockEntity(new BlockPos(OX + x, G + 1 + y, OZ + z)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) return "no sign";
        var text = sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(i == 0 ? "" : "|").append(text.getMessages(false).get(i).getString());
        return sb.toString();
    }

    static void sign() {
        AutoScenes.setup(Samples.signs(), "survival", true);
        idle();
        act(() -> Settings.get().autoRate = 20);
        for (String mode : new String[]{"sweep", "assist"}) {
            if (mode.equals("assist")) {
                AutoScenes.wipe();
                AutoScenes.items();
                slabBaseSigns();
            }
            int[] handled = new int[1];
            act(() -> {
                handled[0] = AutoBuilder.signEditorsHandled();
                AutoBuilder.request(mc(), mode.equals("sweep") ? AutoBuilder.Mode.SWEEP : AutoBuilder.Mode.ASSIST);
            });
            if (mode.equals("sweep")) {
                for (int round = 0; round < 2; round++) {
                    for (double sx : new double[]{1.5, 3.5, 5.5}) {
                        camera(OX + sx, G + 1, OZ - 1.5, 0, 18);
                        waitTicks(60);
                    }
                    camera(OX + 3.5, G + 2.05, OZ + 1.0, 0, 18);
                    waitTicks(60);
                }
            } else {
                press(1, 1, 1, false, 2);
                press(3, 1, 2, false, 2);
                press(5, 2, 2, false, 2);
            }
            waitTicks(20);
            act(() -> {
                check("sign/" + mode + ": no editor stands in the player's face", !(screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen), String.valueOf(screen()));
                check("sign/" + mode + ": auto-placing is still on", AutoBuilder.on() && screen() == null, AutoBuilder.detail() + " screen " + screen());
                check("sign/" + mode + ": three signs were placed and their editors taken", AutoBuilder.signEditorsHandled() - handled[0] == 3, (AutoBuilder.signEditorsHandled() - handled[0]) + " handled");
                check("sign/" + mode + ": the standing sign has the blueprint's text", signLines(1, 1, 1).equals("Standing|sign||"), signLines(1, 1, 1));
                check("sign/" + mode + ": the wall sign has the blueprint's text", signLines(3, 1, 2).equals("Wall sign|of the|blueprint|"), signLines(3, 1, 2));
                check("sign/" + mode + ": the hanging sign has the blueprint's text", signLines(5, 2, 2).equals("Hanging|text||"), signLines(5, 2, 2));
            });
            complete("sign/" + mode + ": the build is complete");
            AutoScenes.off();
        }
        // a sign placed by hand still opens its editor
        cmd("gamemode survival Builder", "item replace entity Builder weapon.mainhand with minecraft:oak_sign 4");
        camera(OX + 3.5, G + 1, OZ - 4.5, 0, 0);
        waitTicks(6);
        act(() -> aim(mc(), OX + 3.5, G + 1.0, OZ - 2.5));
        waitTicks(4);
        act(() -> PlaceScenes.tap(mc().options.keyUse));
        waitTicks(10);
        act(() -> check("sign/by hand with auto-placing off the editor opens", screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen, String.valueOf(screen())));
        closeScreen();
        act(() -> {
            AutoBuilder.testServer = null;
            Director.hideHud(mc(), true);
        });
        Director.clean();
        cmd("gamemode spectator Builder");
    }

    private static void slabBaseSigns() {
        cmd("fill " + OX + " " + (G + 1) + " " + OZ + " " + (OX + 6) + " " + (G + 1) + " " + (OZ + 3) + " minecraft:stone_bricks",
            "fill " + OX + " " + (G + 2) + " " + (OZ + 3) + " " + (OX + 6) + " " + (G + 3) + " " + (OZ + 3) + " minecraft:oak_planks",
            "fill " + OX + " " + (G + 4) + " " + OZ + " " + (OX + 6) + " " + (G + 4) + " " + (OZ + 3) + " minecraft:oak_planks");
        waitTicks(8);
    }

    // ---- one layer

    static void layer() {
        for (boolean own : new boolean[]{true, false}) {
            String tag = "layer/" + (own ? "own" : "strict") + ": ";
            AutoScenes.setup(Samples.layers(), "survival", true);
            server(own);
            idle();
            act(() -> {
                Settings.get().autoRate = 20;
                Settings.get().autoTurn = true;
                AutoScenes.build.layerLo = AutoScenes.build.layerHi = 0;
            });

            // sweep: from the edge, then standing on the floor it has made
            act(() -> AutoBuilder.request(mc(), AutoBuilder.Mode.SWEEP));
            for (int round = 0; round < 2; round++) {
                camera(OX + 3.5, G + 1, OZ - 1.5, 0, 30);
                waitTicks(70);
                camera(OX + 3.5, G + 2.01, OZ + 1.5, 0, 30);
                waitTicks(70);
                camera(OX + 3.5, G + 2.01, OZ + 4.5, 180, 30);
                waitTicks(70);
                camera(OX + 3.5, G + 1, OZ + 7.5, 180, 30);
                waitTicks(70);
            }
            act(() -> {
                int floor = 0;
                for (int z = 0; z < 7; z++) for (int x = 0; x < 7; x++) if (v().statusAt(OX + x, G + 1, OZ + z) == Verifier.CORRECT) floor++;
                check(tag + "sweep: every cell of the floor, the middle included, is placed", floor == 49, floor + " of 49; " + AutoBuilder.detail());
                check(tag + "sweep: nothing of the other layers is placed", otherLayers() == 0, otherLayers() + " cells");
                check(tag + "sweep: it says nothing is left on this layer", AutoBuilder.status().contains("this layer"), "'" + AutoBuilder.status() + "'");
            });
            AutoScenes.off();

            // assist: the floor is there but for a hole in the middle; standing on its edge and looking down across the wall line
            // at the hole, the press takes the hole's cell and not a wall cell of the layers that are not shown
            AutoScenes.wipe();
            AutoScenes.items();
            cmd("fill " + OX + " " + (G + 1) + " " + OZ + " " + (OX + 6) + " " + (G + 1) + " " + (OZ + 6) + " minecraft:stone_bricks",
                "fill " + (OX + 2) + " " + (G + 1) + " " + (OZ + 1) + " " + (OX + 4) + " " + (G + 1) + " " + (OZ + 3) + " minecraft:air");
            waitTicks(12);
            var before = new BlockState[1][];
            act(() -> {
                before[0] = AutoScenes.snapshot();
                AutoBuilder.request(mc(), AutoBuilder.Mode.ASSIST);
            });
            camera(OX + 3.5, G + 2.0, OZ + 0.5, 0, 0);
            waitTicks(8);
            act(() -> aim(mc(), OX + 3.5, G + 1.5, OZ + 2.5));
            waitTicks(4);
            act(() -> PlaceScenes.tap(mc().options.keyUse));
            waitTicks(12);
            act(() -> {
                int placed = 0;
                StringBuilder where = new StringBuilder();
                for (int z = 1; z <= 3; z++) for (int x = 2; x <= 4; x++) if (!mc().level.getBlockState(new BlockPos(OX + x, G + 1, OZ + z)).isAir()) {
                    placed++;
                    where.append(' ').append(x).append(',').append(z);
                }
                check(tag + "assist: the press placed a cell of the floor's hole", placed == 1, placed + " placed:" + where + "; " + AutoBuilder.detail());
                check(tag + "assist: and nothing of the other layers", otherLayers() == 0, otherLayers() + " cells; " + AutoBuilder.detail());
                AutoScenes.strayCheck(tag + "assist: and nothing outside the build", before[0]);
            });
            AutoScenes.off();
            leaveServer();
        }
        act(() -> Director.hideHud(mc(), true));
        Director.clean();
        cmd("gamemode spectator Builder");
    }

    /** Blocks standing in the build's box above the floor layer. */
    private static int otherLayers() {
        int n = 0;
        for (int y = 1; y < 5; y++) for (int z = 0; z < 7; z++) for (int x = 0; x < 7; x++) if (!mc().level.getBlockState(new BlockPos(OX + x, G + 1 + y, OZ + z)).isAir()) n++;
        return n;
    }
}
