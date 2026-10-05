package io.github.profetgit.cyanotype.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    @org.spongepowered.asm.mixin.Final
    private LevelRenderState levelRenderState;

    /**
     * The main pass has drawn terrain, entities and both transparency modes by the time the entity outline pass runs,
     * so the ghost goes in its own render pass here: the same for classic and improved transparency.
     */
    @Inject(method = "executeOutline", at = @At("HEAD"))
    private void cyanotype$ghost(FeatureRenderDispatcher.PreparedFrame featureFrame, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GhostRenderer.render(mc, levelRenderState.cameraRenderState, target);
    }
}
