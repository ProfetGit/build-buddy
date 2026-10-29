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

/** 26.2 only (26.3 uses {@link LevelRendererMixin}): the ghost goes in around the level's translucent phases. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFrameMixin {
    //? if <26.3 {
    /*/^* The translucent features come before the water: the blocks that stand in water are drawn here. ^/
    @Inject(method = "executeTranslucent", at = @At("HEAD"))
    private void buildbuddy$ghostInWater(CallbackInfo ci) {
        GhostRenderer.drawEarlyInLevel(Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }

    /^* The last phase of the main pass, after the water: the rest of the ghost goes in its own render pass. ^/
    @Inject(method = "executeTranslucentAfterTerrain", at = @At("RETURN"))
    private void buildbuddy$ghost(CallbackInfo ci) {
        GhostRenderer.renderInLevel(Minecraft.getInstance(), Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }
    *///?}
}
