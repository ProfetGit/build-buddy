package io.github.profetgit.cyanotype.auto;

import io.github.profetgit.cyanotype.verify.Matcher;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Works out how a player would put one block in one place, using the game's own placement code: for every spot to click
 * and every way of facing, the item's {@code getStateForPlacement} says what block would stand there, and the first that is
 * the block the blueprint wants is the plan. So stairs, slabs, logs, torches, trapdoors, observers and the rest come out
 * as wanted without this mod knowing anything about any of them.
 *
 * <p>The plan never reaches further than the player could (the game's own block interaction range, less a margin), never
 * clicks through a block (the line from the eyes must meet the clicked face first), and never leans on a block that
 * would open or use itself when clicked (a chest, a door, a button).
 *
 * <p>In a world the player hosts ({@link AutoBuilder#ownWorld}) nobody else is policed, so the rules are the game's own and
 * no more: the full reach, no line of sight, any facing (the caller turns the packet, not the camera), and a cell with
 * nothing to lean on is clicked on itself. On a server none of that applies.
 */
public final class PlacePlanner {
    public enum Why {
        OK, NO_SUPPORT, WRONG_FACING, OUT_OF_REACH, BLOCKED, CANNOT
    }

    /** What to do: click {@code face} of {@code support} at {@code hit} while looking {@code yaw}/{@code pitch}; {@code turn} says the view has to turn there first (in a world of the player's own it never does: the caller just says so to the game). */
    public record Plan(BlockPos support, Direction face, Vec3 hit, float yaw, float pitch, boolean turn) {
    }

    public record Result(@Nullable Plan plan, Why why, @Nullable String facing) {
        static Result ok(Plan p) {
            return new Result(p, Why.OK, null);
        }

        static Result no(Why w) {
            return new Result(null, w, null);
        }
    }

    /** Margin kept off the game's reach, so a block placed at the edge is not refused by the server's own rounding. */
    private static final double REACH_MARGIN = 0.3, REACH_CAP = 5.0;
    private static final float[] YAWS = {0, 90, 180, 270};
    private static Method placementState;

    private PlacePlanner() {
    }

    /** How far the player may place from the eyes. */
    public static double reach(Player p) {
        if (AutoBuilder.ownWorld(Minecraft.getInstance())) return p.blockInteractionRange();
        return Math.max(1.0, Math.min(REACH_CAP, p.blockInteractionRange()) - REACH_MARGIN);
    }

    /**
     * @param incremental the block is there already but one item short (a half slab that should be double): click on it
     * @param allowTurn   plans that need another way of facing are allowed (the player will be turned)
     */
    public static Result plan(Minecraft mc, BlockPos target, BlockState wanted, ItemStack stack, boolean incremental, boolean allowTurn) {
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || !(stack.getItem() instanceof BlockItem item)) return Result.no(Why.CANNOT);
        boolean own = AutoBuilder.ownWorld(mc);
        Vec3 eye = player.getEyePosition();
        double reach = reach(player);
        List<Candidates.Click> clicks = new ArrayList<>();
        BlockState here = level.getBlockState(target);
        if (incremental || (!here.isAir() && here.canBeReplaced())) clicks.addAll(Candidates.onSelf(target));
        clicks.addAll(Candidates.around(target));

        boolean any = false, reachable = false, free = false;
        List<Candidates.Click> usable = new ArrayList<>(), onAir = new ArrayList<>();
        // nothing to lean on: click the empty cell itself, on the faces that look at the player, and the block replaces the air
        if (own && here.isAir()) for (Candidates.Click c : Candidates.onSelf(target)) if (towardEye(eye, c)) clicks.add(c);
        for (Candidates.Click c : clicks) {
            BlockState s = level.getBlockState(c.support());
            boolean self = c.support().equals(target);
            if (!self && (s.isAir() || s.getBlock() instanceof LiquidBlock || s.canBeReplaced() || interactive(level, c.support(), s))) continue;
            if (self && !incremental && !s.canBeReplaced()) continue;
            if (self && interactive(level, c.support(), s)) continue;
            any = true;
            Vec3 hit = c.point();
            if (hit.distanceTo(eye) > reach) continue;
            reachable = true;
            if (!own && !sees(level, player, eye, c.support(), hit)) continue;
            free = true;
            (self && here.isAir() ? onAir : usable).add(c);
        }
        if (!any) return Result.no(Why.NO_SUPPORT);
        if (!reachable) return Result.no(Why.OUT_OF_REACH);
        if (!free) return Result.no(Why.BLOCKED);

        float yaw0 = player.getYRot(), pitch0 = player.getXRot();
        List<float[]> looks = new ArrayList<>();
        looks.add(new float[]{yaw0, pitch0});
        for (float y : YAWS) looks.add(new float[]{y, 0});
        looks.add(new float[]{yaw0, -90});
        looks.add(new float[]{yaw0, 90});
        looks.add(new float[]{yaw0, 45});
        looks.add(new float[]{yaw0, -45});
        boolean blocked = false;
        String needed = null;
        List<List<Candidates.Click>> passes = onAir.isEmpty() ? List.of(usable) : List.of(usable, onAir);
        float savedYaw = player.getYRot(), savedPitch = player.getXRot(), savedYawO = player.yRotO, savedPitchO = player.xRotO;
        try {
            for (List<Candidates.Click> pass : passes) {
                for (int i = 0; i < looks.size(); i++) {
                    float[] look = looks.get(i);
                    boolean current = i == 0;
                    player.setYRot(look[0]);
                    player.setXRot(look[1]);
                    for (Candidates.Click c : pass) {
                        int outcome = simulate(level, player, item, stack, c, target, wanted);
                        if (outcome == 1) {
                            if (current) return Result.ok(new Plan(c.support(), c.face(), c.point(), yaw0, pitch0, false));
                            if (own) return Result.ok(new Plan(c.support(), c.face(), c.point(), look[0], look[1], false));
                            if (allowTurn) return Result.ok(new Plan(c.support(), c.face(), c.point(), look[0], look[1], true));
                            needed = facingWords(look[0], look[1]);
                        } else if (outcome == -1) {
                            blocked = true;
                        }
                    }
                }
            }
        } finally {
            player.setYRot(savedYaw);
            player.setXRot(savedPitch);
            player.yRotO = savedYawO;
            player.xRotO = savedPitchO;
        }
        if (needed != null) return new Result(null, Why.WRONG_FACING, needed);
        return Result.no(blocked ? Why.BLOCKED : Why.CANNOT);
    }

    /** Whether the clicked face of a cell is one the eyes can see from the outside. */
    private static boolean towardEye(Vec3 eye, Candidates.Click c) {
        Direction d = c.face();
        Vec3 centre = Vec3.atCenterOf(c.support());
        return (eye.x - centre.x) * d.getStepX() + (eye.y - centre.y) * d.getStepY() + (eye.z - centre.z) * d.getStepZ() > 0;
    }

    /** 1 when this click gives the wanted block, -1 when something is in the way (an entity), 0 otherwise. */
    private static int simulate(Level level, Player player, BlockItem item, ItemStack stack, Candidates.Click c, BlockPos target, BlockState wanted) {
        BlockHitResult hit = new BlockHitResult(c.point(), c.face(), c.support(), false);
        BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
        ctx = item.updatePlacementContext(ctx);
        if (ctx == null || !ctx.getClickedPos().equals(target) || !ctx.canPlace()) return 0;
        BlockState state = placementState(item, ctx);
        if (state == null) return -1;
        return Matcher.LENIENT.matches(wanted, state) ? 1 : 0;
    }

    /** What the item would place for this context, with the game's own checks (it can stand there, nothing is in the way): null when it would not. */
    private static @Nullable BlockState placementState(BlockItem item, BlockPlaceContext ctx) {
        try {
            if (placementState == null) {
                placementState = BlockItem.class.getDeclaredMethod("getPlacementState", BlockPlaceContext.class);
                placementState.setAccessible(true);
            }
            return (BlockState) placementState.invoke(item, ctx);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the item cannot be asked: fall back on the block's own idea, without the checks of the item
            BlockState s = item.getBlock().getStateForPlacement(ctx);
            if (s == null || !s.canSurvive(ctx.getLevel(), ctx.getClickedPos())) return null;
            return ctx.getLevel().isUnobstructed(s, ctx.getClickedPos(), CollisionContext.placementContext(ctx.getPlayer())) ? s : null;
        }
    }

    /** Whether the straight line from the eyes to the click point meets the clicked block first. */
    static boolean sees(Level level, Player player, Vec3 eye, BlockPos support, Vec3 hit) {
        Vec3 dir = hit.subtract(eye);
        double len = dir.length();
        if (len < 1e-6) return false;
        Vec3 to = hit.add(dir.scale(0.04 / len));
        BlockHitResult r = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(support);
    }

    /** A block that does something when clicked (opens, toggles, takes the item) instead of letting a block be put against it. */
    public static boolean interactive(Level level, BlockPos pos, BlockState s) {
        var b = s.getBlock();
        if (s.getMenuProvider(level, pos) != null) return true;
        return b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock
            || b instanceof BedBlock || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock || b instanceof CakeBlock
            || b instanceof CandleCakeBlock || b instanceof LecternBlock || b instanceof RespawnAnchorBlock || b instanceof DragonEggBlock || b instanceof AnvilBlock
            || b instanceof BellBlock || b instanceof ChiseledBookShelfBlock || b instanceof DecoratedPotBlock || b instanceof JukeboxBlock || b instanceof FlowerPotBlock
            || b instanceof SignBlock || b instanceof net.minecraft.world.level.block.AbstractCauldronBlock;
    }

    /** "east", "north and a little down": how to face, in words. */
    static String facingWords(float yaw, float pitch) {
        String dir;
        float y = ((yaw % 360) + 360) % 360;
        if (y < 45 || y >= 315) dir = "south";
        else if (y < 135) dir = "west";
        else if (y < 225) dir = "north";
        else dir = "east";
        if (pitch <= -60) return "up";
        if (pitch >= 60) return "down";
        if (pitch <= -30) return dir + " and a little up";
        if (pitch >= 30) return dir + " and a little down";
        return dir;
    }
}
