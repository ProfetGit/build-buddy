package io.github.profetgit.cyanotype.ghost;

import io.github.profetgit.cyanotype.verify.WorldView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** The client's world as the verifier sees it. */
final class ClientWorldView implements WorldView {
    private final ClientLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    ClientWorldView(ClientLevel level) {
        this.level = level;
    }

    @Override
    public BlockState get(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
    }

    @Override
    public boolean loaded(int chunkX, int chunkZ) {
        return level.getChunkSource().hasChunk(chunkX, chunkZ);
    }
}
