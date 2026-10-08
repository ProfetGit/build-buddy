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

import io.github.profetgit.cyanotype.auto.AutoBuilder;
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
}
