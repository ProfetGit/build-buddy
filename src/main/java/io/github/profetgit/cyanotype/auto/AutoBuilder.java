package io.github.profetgit.cyanotype.auto;

import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.ui.AutoDisclaimerScreen;
import io.github.profetgit.cyanotype.ui.Chips;
import io.github.profetgit.cyanotype.ui.Materials;
import io.github.profetgit.cyanotype.ui.Settings;
import io.github.profetgit.cyanotype.ui.Sfx;
import io.github.profetgit.cyanotype.ui.WheelScreen;
import io.github.profetgit.cyanotype.verify.Verifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Auto-placing (PRD 7.6, 7.8): puts blocks of the active placement in the world for the player, from the items in the
 * inventory, the way a player does: the game's own use-item-on path, within the player's own reach, one block at a time at
 * a rate that has a ceiling nobody can raise. In <b>Assist</b> mode the block under the crosshair's ghost is placed while
 * the use key is held; in <b>Sweep</b> mode everything within reach is built, lowest layer first, while the player walks.
 *
 * <p>It stops when the player is hurt, dies, leaves survival or creative mode, opens a screen (Esc included), or runs out
 * of the items the build needs. It never hides: placed blocks swing the arm and play the place sound, a badge shows in the
 * HUD while it is on, and on a multiplayer server it is off until the player has seen the warning and said yes.
 */
public final class AutoBuilder {
    public enum Mode {
        OFF, ASSIST, SWEEP
    }

    /** Dev demo only: acts as if connected to this server address (null = what the game says). */
    public static volatile String testServer;

    private static final int PENDING_TICKS = 8, FAILS_BEFORE_SKIP = 2, SKIP_TICKS = 100, NO_ITEM_STOP_TICKS = 50, SCAN_LIMIT = 40, MAX_TURN_STEP = 18;

    private static Mode mode = Mode.OFF;
    private static final Rate RATE = Rate.create();
    private static String status = "";
    private static int placed, wrongFacing, noSupport, outOfReach, planFails, useFails;
    private static long tickNo;
    private static int originalSlot = -1;
    private static float lastHealth;
    private static int noItemTicks;
    private static String missingItem = "";
    private static final Map<Long, Long> PENDING = new HashMap<>(), SKIP_UNTIL = new HashMap<>();
    private static final Map<Long, Integer> FAILS = new HashMap<>();
    /** Why a block was left alone the last time it was looked at, kept while it is skipped so the HUD keeps saying so: a facing, "no support" or "out of reach". */
    private static final Map<Long, String[]> REASON = new HashMap<>();
    /** Servers the player said yes to this session (without "don't ask again"): until they leave. */
    private static final Set<String> SESSION_ALLOWED = new HashSet<>();
    private static @Nullable Assist assist;

    private AutoBuilder() {
    }

    private record Assist(BlockPos cell, BlockState wanted, Materials.Cost cost, boolean incremental) {
    }

    public static Mode mode() {
        return mode;
    }

    public static boolean on() {
        return mode != Mode.OFF;
    }

    public static String status() {
        return status;
    }

    public static int placedCount() {
        return placed;
    }

    // ---- who we are playing with

    /** The normalised address of the multiplayer server the player is on, or null in a world of their own (singleplayer, or a LAN world they host). */
    public static @Nullable String serverKey(Minecraft mc) {
        String test = testServer;
        if (test != null) return test.isEmpty() ? null : ServerKey.normalize(test);
        if (mc.getSingleplayerServer() != null) return null;
        ServerData sd = mc.getCurrentServer();
        if (sd == null || sd.ip == null || sd.ip.isBlank()) return "unknown-server";
        String k = ServerKey.normalize(sd.ip);
        return k.isEmpty() ? "unknown-server" : k;
    }

    // ---- turning it on and off

    /** The wheel tool: off, then Assist, then Sweep, then off. */
    public static void cycle(Minecraft mc) {
        request(mc, switch (mode) {
            case OFF -> Mode.ASSIST;
            case ASSIST -> Mode.SWEEP;
            case SWEEP -> Mode.OFF;
        });
    }

    /** Asks for a mode. On a server it needs the player's yes first; a blocked server never gets one. */
    public static void request(Minecraft mc, Mode wanted) {
        if (wanted == Mode.OFF) {
            if (mode != Mode.OFF) stop(mc, "Auto-placing off");
            return;
        }
        if (mc.level == null || mc.player == null) return;
        String key = serverKey(mc);
        if (key != null) {
            switch (ServerRules.get().decision(key)) {
                case BLOCKED -> {
                    refuse(mc, "Auto-placing is blocked on this server. Settings > Servers can take it off the list.");
                    return;
                }
                case DECLINED -> {
                    refuse(mc, "You kept auto-placing off on this server. Settings > Servers can ask again.");
                    return;
                }
                case ALLOWED -> {
                }
                case ASK -> {
                    if (!SESSION_ALLOWED.contains(key)) {
                        Sfx.play(Sfx.OPEN);
                        mc.gui.setScreen(new AutoDisclaimerScreen(key, wanted));
                        return;
                    }
                }
            }
        }
        start(mc, wanted);
    }

    /** What the disclaimer screen reports back. */
    public static void answered(Minecraft mc, String key, Mode wanted, boolean enable, boolean dontAsk) {
        ServerRules rules = ServerRules.get();
        if (enable) {
            if (dontAsk) rules.allow(key);
            else SESSION_ALLOWED.add(key);
            start(mc, wanted);
        } else {
            if (dontAsk) rules.decline(key);
            Interaction.say(mc, "Auto-placing stays off on this server.");
        }
    }

    private static void refuse(Minecraft mc, String text) {
        Sfx.play(Sfx.ERROR);
        Interaction.say(mc, text);
    }

    private static void start(Minecraft mc, Mode wanted) {
        GameType gt = mc.gameMode == null ? null : mc.gameMode.getPlayerMode();
        if (gt == GameType.SPECTATOR || gt == GameType.ADVENTURE) {
            refuse(mc, "Auto-placing needs survival or creative mode.");
            return;
        }
        if (mode == Mode.OFF) {
            placed = wrongFacing = noSupport = outOfReach = planFails = useFails = 0;
            clearBookkeeping();
            originalSlot = mc.player.getInventory().getSelectedSlot();
            lastHealth = mc.player.getHealth();
            noItemTicks = 0;
            missingItem = "";
        }
        mode = wanted;
        RATE.reset();
        status = wanted == Mode.SWEEP ? "Building what is in reach" : "Hold use on a ghost block";
        Sfx.play(Sfx.OPEN, wanted == Mode.SWEEP ? 1.2f : 1.0f);
        Interaction.say(mc, wanted == Mode.SWEEP ? "Sweep: building everything in reach. Esc or any damage stops it." : "Assist: hold use on a ghost block to place it.");
    }

    /** Switches it off, says why, and gives the player their hotbar slot back. */
    public static void stop(Minecraft mc, String why) {
        if (mode == Mode.OFF) return;
        mode = Mode.OFF;
        status = "";
        assist = null;
        if (mc != null && mc.player != null && originalSlot >= 0 && originalSlot < Inventory.getSelectionSize()) mc.player.getInventory().setSelectedSlot(originalSlot);
        originalSlot = -1;
        clearBookkeeping();
        Sfx.play(Sfx.CLOSE);
        if (mc != null) Interaction.say(mc, why + (placed > 0 ? " (" + placed + " placed)" : ""));
    }

    /** The world changed or the player left: forget everything quietly. */
    public static void reset() {
        mode = Mode.OFF;
        status = "";
        assist = null;
        originalSlot = -1;
        clearBookkeeping();
        SESSION_ALLOWED.clear();
    }

    private static void clearBookkeeping() {
        PENDING.clear();
        SKIP_UNTIL.clear();
        FAILS.clear();
        REASON.clear();
    }

    // ---- every tick

    public static void tick(Minecraft mc) {
        tickNo++;
        LocalPlayer p = mc.player;
        if (mode == Mode.OFF) return;
        if (p == null || mc.level == null || mc.gameMode == null) {
            reset();
            return;
        }
        if (!p.isAlive()) {
            stop(mc, "Auto-placing stopped");
            return;
        }
        GameType gt = mc.gameMode.getPlayerMode();
        if (gt == GameType.SPECTATOR || gt == GameType.ADVENTURE) {
            stop(mc, "Auto-placing needs survival or creative mode");
            return;
        }
        if (p.getHealth() < lastHealth - 0.001f) {
            stop(mc, "Auto-placing stopped: you were hurt");
            return;
        }
        lastHealth = p.getHealth();
        // a block of the list can come after the player turned it on (they added the server from the settings screen)
        String key = serverKey(mc);
        if (key != null && ServerRules.get().decision(key) == ServerRules.Decision.BLOCKED) {
            stop(mc, "Auto-placing is blocked on this server");
            return;
        }
        var screen = mc.gui.screen();
        if (screen != null) {
            // the tool wheel opens over the game to choose a tool: that only pauses it; anything else is the player doing something else
            if (screen instanceof WheelScreen) {
                assist = null;
                return;
            }
            stop(mc, "Auto-placing stopped");
            return;
        }
        checkPending();
        Placement pl = Placements.active();
        Verifier v = pl == null || !pl.locked || !pl.ready() ? null : GhostRenderer.verifierOf(pl);
        if (pl == null || v == null) {
            status = "Place a blueprint first";
            assist = null;
            return;
        }
        if (!pl.visible) {
            status = "Paused: this blueprint is hidden";
            assist = null;
            return;
        }
        if (GhostRenderer.hidden) {
            status = "Paused: the ghosts are hidden";
            assist = null;
            return;
        }
        RATE.tick(Settings.get().autoRate);
        if (mode == Mode.SWEEP) sweep(mc, p, pl, v);
        else assist(mc, p, pl, v);
    }

    // ---- bookkeeping of what was placed

    /** A block placed a few ticks ago that is still missing was refused (by the server, or by something in the way): too many times and it is left alone for a while. */
    private static void checkPending() {
        if (PENDING.isEmpty()) return;
        Placement pl = Placements.active();
        Verifier v = pl == null ? null : GhostRenderer.verifierOf(pl);
        if (v == null) return;
        var it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (tickNo - e.getValue() < PENDING_TICKS) continue;
            long c = e.getKey();
            byte st = v.statusAt(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c));
            if (st == Verifier.MISSING) {
                int f = FAILS.merge(c, 1, Integer::sum);
                if (f >= FAILS_BEFORE_SKIP) SKIP_UNTIL.put(c, tickNo + SKIP_TICKS);
            } else {
                FAILS.remove(c);
            }
            it.remove();
        }
    }

    private static boolean skipped(long c) {
        Long until = SKIP_UNTIL.get(c);
        if (until == null) return false;
        if (tickNo >= until) {
            SKIP_UNTIL.remove(c);
            FAILS.remove(c);
            return false;
        }
        return true;
    }

    // ---- Sweep

    private static void sweep(Minecraft mc, LocalPlayer p, Placement pl, Verifier v) {
        if (!RATE.ready()) return;
        Vec3 eye = p.getEyePosition();
        double reach = PlacePlanner.reach(p) + 1.0;
        int r = (int) Math.ceil(reach);
        BlockPos at = BlockPos.containing(eye);
        List<TargetOrder.Cand> cands = new ArrayList<>();
        int lo = Math.max(0, pl.layerLo), hi = pl.layerHi < 0 ? Integer.MAX_VALUE : pl.layerHi;
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = at.getX() + dx, y = at.getY() + dy, z = at.getZ() + dz;
                    byte st = v.statusAt(x, y, z);
                    if (st != Verifier.MISSING && st != Verifier.WRONG) continue;
                    int layer = y - v.originY;
                    if (layer < lo || layer > hi) continue;
                    double d = new Vec3(x + 0.5, y + 0.5, z + 0.5).distanceToSqr(eye);
                    if (d > reach * reach) continue;
                    cands.add(new TargetOrder.Cand(BlockPos.asLong(x, y, z), layer, d));
                }
            }
        }
        cands.sort(TargetOrder.ORDER);
        int looked = 0, lackingItems = 0, tried = 0;
        String lacking = "";
        wrongFacing = noSupport = outOfReach = 0;
        String facing = "";
        for (TargetOrder.Cand c : cands) {
            if (looked >= SCAN_LIMIT) break;
            long key = c.pos();
            if (skipped(key)) {
                String[] why = REASON.get(key);
                if (why != null) {
                    switch (why[0]) {
                        case "facing" -> {
                            wrongFacing++;
                            if (facing.isEmpty()) facing = why[1];
                        }
                        case "support" -> noSupport++;
                        case "reach" -> outOfReach++;
                        default -> {
                        }
                    }
                }
                continue;
            }
            BlockPos pos = BlockPos.of(key);
            BlockState wanted = v.expectedAt(pos.getX(), pos.getY(), pos.getZ());
            boolean incremental = v.statusAt(pos.getX(), pos.getY(), pos.getZ()) == Verifier.WRONG;
            Materials.Cost cost = Materials.costOf(wanted);
            if (cost == null) continue;
            if (incremental && !stacksUp(mc, pos, wanted)) continue;
            looked++;
            if (count(p, cost.item()) < 1) {
                lackingItems++;
                if (lacking.isEmpty()) lacking = cost.item().getName(new ItemStack(cost.item())).getString();
                continue;
            }
            tried++;
            ItemStack stack = stackFor(p, cost.item());
            PlacePlanner.Result res = PlacePlanner.plan(mc, pos, wanted, stack, incremental, Settings.get().autoTurn);
            if (res.plan() == null) {
                switch (res.why()) {
                    case WRONG_FACING -> {
                        wrongFacing++;
                        if (facing.isEmpty()) facing = res.facing();
                        REASON.put(key, new String[]{"facing", res.facing()});
                    }
                    case NO_SUPPORT -> {
                        noSupport++;
                        REASON.put(key, new String[]{"support", ""});
                    }
                    case OUT_OF_REACH -> {
                        outOfReach++;
                        REASON.put(key, new String[]{"reach", ""});
                    }
                    default -> REASON.remove(key);
                }
                planFails++;
                SKIP_UNTIL.put(key, tickNo + 12);
                continue;
            }
            if (place(mc, p, res.plan(), cost.item(), key, true)) {
                noItemTicks = 0;
                missingItem = "";
                return;
            }
            // turning toward the block counts as working on it
            if (res.plan().turn()) return;
        }
        // nothing placed this tick: say why
        if (cands.isEmpty()) {
            status = "Nothing to build in reach";
            noItemTicks = 0;
        } else if (tried == 0 && lackingItems > 0) {
            status = "Out of " + lacking;
            missingItem = lacking;
            if (++noItemTicks >= NO_ITEM_STOP_TICKS) stop(mc, "Auto-placing stopped: out of " + lacking);
        } else if (wrongFacing > 0) {
            status = wrongFacing + " need you to face " + facing + (Settings.get().autoTurn ? "" : " (or turn on Turn to face)");
            noItemTicks = 0;
        } else if (noSupport > 0) {
            status = noSupport + " have nothing to attach to yet";
            noItemTicks = 0;
        } else {
            status = "Building what is in reach";
            noItemTicks = 0;
        }
    }

    /** A block already standing there that one more of the item would complete (the second slab, the next candle): true when it is the same block. */
    private static boolean stacksUp(Minecraft mc, BlockPos pos, BlockState wanted) {
        BlockState actual = mc.level.getBlockState(pos);
        return actual.getBlock() == wanted.getBlock();
    }

    // ---- Assist

    private static void assist(Minecraft mc, LocalPlayer p, Placement pl, Verifier v) {
        assist = findAssist(mc, p, v);
        if (!mc.options.keyUse.isDown()) {
            status = assist != null ? "Hold use to place " + nameOf(assist.wanted) : "Hold use on a ghost block";
            return;
        }
        // the use key is held: the block under the crosshair goes down every tick, the fastest there is (20 a second)
        if (assist != null) assistPlace(mc, p, assist);
    }

    private static long lastAssistTick = -1;

    /** Places the ghost block under the crosshair now, with no delay: one block a tick, 20 a second, the ceiling of the mod. */
    private static void assistPlace(Minecraft mc, LocalPlayer p, Assist a) {
        if (lastAssistTick == tickNo) return;
        if (count(p, a.cost.item()) < 1) {
            status = "You have no " + a.cost.item().getName(new ItemStack(a.cost.item())).getString();
            return;
        }
        PlacePlanner.Result res = PlacePlanner.plan(mc, a.cell, a.wanted, stackFor(p, a.cost.item()), a.incremental, false);
        if (res.plan() == null) {
            status = switch (res.why()) {
                case WRONG_FACING -> "Face " + res.facing() + " to place this";
                case NO_SUPPORT -> "Nothing to attach it to yet";
                case OUT_OF_REACH -> "Out of reach";
                case BLOCKED -> "Something is in the way";
                default -> "Cannot place that here";
            };
            return;
        }
        if (place(mc, p, res.plan(), a.cost.item(), a.cell.asLong(), false)) lastAssistTick = tickNo;
    }

    /** The ghost block under the crosshair: the first cell along the look that the build wants a block in and has none, before any real block. */
    private static @Nullable Assist findAssist(Minecraft mc, LocalPlayer p, Verifier v) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        double reach = PlacePlanner.reach(p) + 0.5;
        BlockHitResult real = mc.level.clip(new ClipContext(eye, eye.add(look.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        double limit = real.getType() == HitResult.Type.BLOCK ? real.getLocation().distanceTo(eye) : reach;
        BlockPos last = null;
        for (double t = 0; t <= limit + 1e-6; t += 0.05) {
            BlockPos c = BlockPos.containing(eye.add(look.scale(t)));
            if (c.equals(last)) continue;
            last = c;
            byte st = v.statusAt(c.getX(), c.getY(), c.getZ());
            if (st != Verifier.MISSING && st != Verifier.WRONG) continue;
            BlockState wanted = v.expectedAt(c.getX(), c.getY(), c.getZ());
            boolean incremental = st == Verifier.WRONG;
            if (incremental && !stacksUp(mc, c, wanted)) continue;
            Materials.Cost cost = Materials.costOf(wanted);
            if (cost == null) continue;
            return new Assist(c, wanted, cost, incremental);
        }
        return null;
    }

    /**
     * Whether a use-key press belongs to auto-placing. With it on, a block never goes down anywhere but where the build wants
     * it: a ghost block under the crosshair takes the press, and so does any press with a block in hand (nothing is placed
     * on the ground at random). A block that does something when used (a chest, a door) is still the game's.
     */
    public static boolean claimsUse(Minecraft mc) {
        if (mode == Mode.OFF || mc.gui.screen() != null || GhostRenderer.hidden || mc.player == null || mc.level == null) return false;
        Placement pl = Placements.active();
        Verifier v = pl == null || !pl.locked || !pl.ready() ? null : GhostRenderer.verifierOf(pl);
        if (v == null) return false;
        if (findAssist(mc, mc.player, v) != null) return true;
        if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) return false;
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult real = mc.level.clip(new ClipContext(eye, eye.add(mc.player.getLookAngle().scale(PlacePlanner.reach(mc.player) + 0.5)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (real.getType() == HitResult.Type.BLOCK && !mc.player.isShiftKeyDown() && PlacePlanner.interactive(mc.level, real.getBlockPos(), mc.level.getBlockState(real.getBlockPos()))) return false;
        return true;
    }

    /** A use press, as the game handles it: takes it (true) and places the block under the crosshair at once, or leaves it to the game (false). */
    public static boolean onUse(Minecraft mc) {
        if (!claimsUse(mc)) return false;
        LocalPlayer p = mc.player;
        Placement pl = Placements.active();
        Verifier v = GhostRenderer.verifierOf(pl);
        Assist a = findAssist(mc, p, v);
        if (a != null) {
            assist = a;
            assistPlace(mc, p, a);
        } else {
            status = "Look at a ghost block to place it";
        }
        return true;
    }

    /** The cursor chip for Assist: what a held use key would place. */
    public static void frame(Minecraft mc) {
        if (mode == Mode.OFF || assist == null || mc.gui.screen() != null || GhostRenderer.hidden) return;
        Chips.show(new Chips.Chip("Hold use", "Place " + nameOf(assist.wanted)));
    }

    private static String nameOf(BlockState s) {
        return s.getBlock().getName().getString();
    }

    // ---- placing

    /** Does one placement: the right item in hand, a turn when the plan needs one, then the game's own use-item-on. @return whether a block was placed */
    private static boolean place(Minecraft mc, LocalPlayer p, PlacePlanner.Plan plan, Item item, long cell, boolean paid) {
        if (plan.turn()) {
            // look toward the way the block needs: a few degrees a tick, so the view turns smoothly and nothing is hidden
            float dy = wrap(plan.yaw() - p.getYRot()), dp = plan.pitch() - p.getXRot();
            p.setYRot(p.getYRot() + Math.max(-MAX_TURN_STEP, Math.min(MAX_TURN_STEP, dy)));
            p.setXRot(p.getXRot() + Math.max(-MAX_TURN_STEP, Math.min(MAX_TURN_STEP, dp)));
            status = "Turning to face the next block";
            return false;
        }
        if (!hold(mc, p, item)) {
            status = "Cannot take " + item.getName(new ItemStack(item)).getString() + " in hand";
            return false;
        }
        BlockHitResult hit = new BlockHitResult(plan.hit(), plan.face(), plan.support(), false);
        ItemStack held = p.getItemInHand(InteractionHand.MAIN_HAND);
        int before = held.getCount();
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        if (r instanceof InteractionResult.Success success) {
            if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
                p.swing(InteractionHand.MAIN_HAND, held.getInteractAnimation(), false);
                if (!held.isEmpty() && (held.getCount() != before || p.hasInfiniteMaterials())) p.itemUsed(InteractionHand.MAIN_HAND);
            }
            placed++;
            PENDING.put(cell, tickNo);
            if (paid) RATE.spend();
            status = "Placed " + placed;
            return true;
        }
        useFails++;
        int f = FAILS.merge(cell, 1, Integer::sum);
        if (f >= FAILS_BEFORE_SKIP) SKIP_UNTIL.put(cell, tickNo + SKIP_TICKS);
        else SKIP_UNTIL.put(cell, tickNo + 6);
        return false;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    // ---- the inventory

    /** How many of an item the player carries (hotbar and bag, not armour). */
    static int count(LocalPlayer p, Item item) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static ItemStack stackFor(LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return inv.getItem(i);
        return new ItemStack(item);
    }

    /** Puts the item in the player's hand: select its hotbar slot, or swap it in from the bag (into an empty hotbar slot if there is one). */
    private static boolean hold(Minecraft mc, LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        int sel = inv.getSelectedSlot();
        if (inv.getItem(sel).is(item)) return true;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        int from = -1;
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                from = i;
                break;
            }
        }
        if (from < 0) return false;
        int into = sel;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                into = i;
                break;
            }
        }
        inv.setSelectedSlot(into);
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, from, into, ContainerInput.SWAP, p);
        return true;
    }

    // ---- for the demo

    public static String detail() {
        return String.format(Locale.ROOT, "mode %s, placed %d, plan failures %d, refused %d, status '%s'", mode, placed, planFails, useFails, status);
    }

    /** Dev demo: how many blocks are waiting to be seen as placed. */
    public static int pendingCount() {
        return PENDING.size();
    }

    public static int[] reasons() {
        return new int[]{wrongFacing, noSupport, outOfReach};
    }
}
