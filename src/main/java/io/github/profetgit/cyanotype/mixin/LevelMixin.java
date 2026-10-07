package io.github.profetgit.cyanotype.mixin;

import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    /**
     * Every block change of the client's world (a placed block, a broken one, an update from the server) tells the
     * verifiers which cell to look at again. The integrated server shares this class, so only the client side counts.
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void cyanotype$changed(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && ((Level) (Object) this).isClientSide()) {
            GhostRenderer.onBlockChanged(pos.asLong());
            if (io.github.profetgit.cyanotype.ponder.PonderRecorder.active) io.github.profetgit.cyanotype.ponder.PonderRecorder.onBlock(pos.asLong());
        }
    }
}
