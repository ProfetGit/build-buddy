package io.github.profetgit.buildbuddy.pick;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Sorting blocks into the kinds Smart Pick works with (PRD 7.11). By class and by name rather than by block tag, so
 * it behaves the same in a test and in the game. When in doubt a block is {@link Kind#BUILT}: a block the picker wrongly
 * thinks is part of a build is easy to see and take away, one it wrongly drops is a hole nobody notices.
 */
public final class BlockKinds {
    private static final Set<Block> TERRAIN = new HashSet<>();

    static {
        for (Block b : new Block[]{
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.ROOTED_DIRT, Blocks.MUD, Blocks.CLAY, Blocks.DIRT_PATH,
            Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL,
            Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE,
            Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.END_STONE, Blocks.BEDROCK, Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
            Blocks.MOSS_BLOCK, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.TERRACOTTA, Blocks.ANCIENT_DEBRIS,
            Blocks.SMOOTH_BASALT, Blocks.INFESTED_STONE}) {
            TERRAIN.add(b);
        }
    }

    private BlockKinds() {
    }

    public static Kind classify(BlockState s) {
        if (s.isAir()) return Kind.AIR;
        Block b = s.getBlock();
        if (b instanceof LiquidBlock) return Kind.FLUID;
        if (TERRAIN.contains(b)) return Kind.TERRAIN;
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (id.endsWith("_ore")) return Kind.TERRAIN;
        // a lone planted crop is part of a farm, which is a build; wild plants are not
        if (b instanceof CropBlock || b instanceof StemBlock || b instanceof AttachedStemBlock || b instanceof SweetBerryBushBlock) return Kind.BUILT;
        if (b instanceof LeavesBlock) return s.hasProperty(BlockStateProperties.PERSISTENT) && s.getValue(BlockStateProperties.PERSISTENT) ? Kind.BUILT : Kind.VEGETATION;
        if (b instanceof VegetationBlock || b instanceof VineBlock || b instanceof GrowingPlantBlock || b instanceof SnowLayerBlock || b instanceof MossyCarpetBlock
            || b instanceof HugeMushroomBlock || b instanceof FireBlock) return Kind.VEGETATION;
        if (id.equals("sugar_cane") || id.equals("cactus") || id.equals("lily_pad") || id.equals("bamboo") || id.equals("bamboo_sapling") || id.startsWith("hanging_roots") || id.equals("spore_blossom")
            || id.equals("moss_carpet") || id.equals("pale_moss_carpet") || id.equals("pale_hanging_moss") || id.equals("mangrove_roots") || id.equals("muddy_mangrove_roots") || id.equals("big_dripleaf")
            || id.equals("small_dripleaf") || id.equals("glow_lichen") || id.equals("sculk_vein") || id.equals("nether_wart_block") || id.equals("warped_wart_block") || id.equals("fire")
            || id.equals("soul_fire") || id.equals("cobweb") || id.endsWith("_coral") || id.endsWith("_coral_fan") || id.endsWith("_coral_wall_fan") || id.equals("seagrass") || id.equals("tall_seagrass")
            || id.equals("kelp") || id.equals("kelp_plant") || id.equals("turtle_egg") || id.equals("sniffer_egg") || id.equals("frogspawn")) {
            return Kind.VEGETATION;
        }
        // an unstripped trunk or stem; stripped ones are timber a player has worked
        if ((id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae")) && !id.startsWith("stripped_") && !id.startsWith("potted_")) return Kind.LOG;
        return Kind.BUILT;
    }

    /** Leaves a tree grew (or a nether tree's wart blocks): what makes a log a trunk. */
    public static boolean isNaturalLeaf(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof LeavesBlock) return !(s.hasProperty(BlockStateProperties.PERSISTENT) && s.getValue(BlockStateProperties.PERSISTENT));
        return b == Blocks.NETHER_WART_BLOCK || b == Blocks.WARPED_WART_BLOCK;
    }
}
