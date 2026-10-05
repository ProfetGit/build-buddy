package io.github.profetgit.cyanotype.interaction;

import io.github.profetgit.cyanotype.blueprint.Capture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.Nullable;

/** The client's world as a capture reads it: what the client knows, which is the loaded chunks and the data the server sent. */
public final class LevelSource implements Capture.Source, io.github.profetgit.cyanotype.pick.Picker.Field {
    private final ClientLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public LevelSource(ClientLevel level) {
        this.level = level;
    }

    @Override
    public BlockState state(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
    }

    @Override
    public boolean loaded(int x, int z) {
        return level.getChunkSource().hasChunk(x >> 4, z >> 4);
    }

    @Override
    public @Nullable CompoundTag blockEntity(int x, int y, int z) {
        BlockEntity be = level.getBlockEntity(pos.set(x, y, z));
        if (be == null) return null;
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        be.saveWithId(out);
        return out.buildResult();
    }
}
