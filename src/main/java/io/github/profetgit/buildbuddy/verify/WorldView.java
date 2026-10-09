package io.github.profetgit.buildbuddy.verify;

import net.minecraft.world.level.block.state.BlockState;

/** What the verifier needs to know about the world: blocks, and whether a chunk column is loaded. */
public interface WorldView {
    BlockState get(int x, int y, int z);

    /** Whether the chunk column holding block column (x, z) is loaded: blocks of unloaded chunks cannot be judged. */
    boolean loaded(int chunkX, int chunkZ);
}
