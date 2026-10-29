package io.github.profetgit.buildbuddy.mixin;

import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
//? if <26.3 {
/*import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/** 26.2 only (26.3 uses {@link LevelRendererMixin}): the ghost goes in with the feature frame's outline step. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFrameMixin {
    //? if <26.3 {
    /*/^*
     * Between the translucent features and the water, so the water blends over the blocks that stand in it. It is also
     * the only point of the main pass where Iris keeps what a pass of our own draws: before it or after the water the
     * ghost is lost under a shader pack.
     ^/
    @Inject(method = "executeOutline", at = @At("HEAD"))
    private void buildbuddy$ghost(CallbackInfo ci) {
        GhostRenderer.renderInLevel(Minecraft.getInstance(), Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }
    *///?}
}
