package io.github.profetgit.buildbuddy.verify;

import java.util.Set;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Whether the block in the world is the block the blueprint asks for. Strict means every property; lenient (the default)
 * leaves out what the game works out by itself or what players flip as they use a build: a door that is open, a lamp
 * that is lit, water in a stair, the leaf distance, the corner shape a stair takes from its neighbours, which sides a
 * fence connects to, how much a liquid flows. Air in all its kinds is one thing.
 */
public final class Matcher {
    public static final Matcher LENIENT = new Matcher(true), STRICT = new Matcher(false);

    /** Properties that never decide whether a block is right, whatever block it is. */
    private static final Set<String> LOOSE = Set.of(
        "waterlogged", "distance", "persistent", "powered", "lit", "open", "triggered", "power", "snowy", "shape",
        "bottom", "conditional", "in_wall", "attached", "disarmed", "hinge");
    private static final Set<String> SIDES = Set.of("north", "east", "south", "west", "up", "down");

    private final boolean lenient;

    private Matcher(boolean lenient) {
        this.lenient = lenient;
    }

    public boolean matches(BlockState expected, BlockState actual) {
        if (expected == actual) return true;
        if (expected.isAir() && actual.isAir()) return true;
        if (expected.getBlock() != actual.getBlock()) return false;
        if (!lenient) return false;
        // same block, different state: compare the properties that count
        if (expected.getBlock() instanceof LiquidBlock) return true;
        boolean connector = connects(expected);
        boolean chest = expected.getBlock() instanceof ChestBlock;
        for (Property<?> p : expected.getProperties()) {
            String name = p.getName();
            if (LOOSE.contains(name)) continue;
            // a chest's single/left/right follows its neighbour; a slab's bottom/top/double is the build itself
            if (chest && name.equals("type")) continue;
            if (connector && SIDES.contains(name)) continue;
            if (!expected.getValue(p).equals(actual.getValue(p))) return false;
        }
        return true;
    }

    /** Blocks whose sides follow their neighbours (fences, walls, panes, bars, redstone, chorus, tripwire). */
    private static boolean connects(BlockState s) {
        return s.getBlock() instanceof CrossCollisionBlock || s.getBlock() instanceof IronBarsBlock || s.getBlock() instanceof RedstoneWireBlock
            || s.getBlock() instanceof ChorusPlantBlock || s.getBlock() instanceof TripWireBlock || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCES);
    }
}
