package io.github.profetgit.cyanotype.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.profetgit.cyanotype.TestBootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class GroupsCodeTest {
    static {
        TestBootstrap.init();
    }

    @Test
    void realBlocksAreSortedIntoTheKindsTheGroupsUse() {
        assertEquals(Groups.TERRAIN, Groups.code(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertEquals(Groups.TERRAIN, Groups.code(Blocks.DIRT.defaultBlockState()));
        assertEquals(Groups.LOG, Groups.code(Blocks.OAK_LOG.defaultBlockState()));
        assertEquals(Groups.LEAF, Groups.code(Blocks.OAK_LEAVES.defaultBlockState()));
        assertEquals(Groups.PLANT, Groups.code(Blocks.SHORT_GRASS.defaultBlockState()));
        assertEquals(Groups.FLUID, Groups.code(Blocks.WATER.defaultBlockState()));
        assertEquals(Groups.BUILT, Groups.code(Blocks.OAK_PLANKS.defaultBlockState()));
        assertEquals(Groups.BUILT, Groups.code(Blocks.STRIPPED_OAK_LOG.defaultBlockState()));
        assertEquals(Groups.AIR, Groups.code(Blocks.AIR.defaultBlockState()));
    }
}
