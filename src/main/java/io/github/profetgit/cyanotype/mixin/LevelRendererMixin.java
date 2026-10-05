package io.github.profetgit.cyanotype.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.profetgit.cyanotype.ghost.GhostRenderer;
import io.github.profetgit.cyanotype.interaction.Interaction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
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
     * The per-frame gizmo collector is open while the level renders, and its contents are finalised in submitFeatures:
     * outlines and handles are added just before that, after the placement has followed the camera for this frame.
     */
    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void cyanotype$interact(LevelRenderState state, SubmitNodeCollector collector, boolean renderOutline, CallbackInfo ci) {
        Interaction.frame(Minecraft.getInstance(), state.cameraRenderState);
    }

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
