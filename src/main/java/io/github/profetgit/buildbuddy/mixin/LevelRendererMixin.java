package io.github.profetgit.buildbuddy.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.interaction.Interaction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
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
     * outlines and handles are added just before that, after the placement has followed the camera for this frame. The
     * ghost is brought up to date right after (no pass is open yet), so every draw of the frame works from one state.
     */
    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void buildbuddy$interact(LevelRenderState state, SubmitNodeCollector collector, boolean renderOutline, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        Interaction.frame(mc, state.cameraRenderState);
        GhostRenderer.prepare(mc, state.cameraRenderState);
    }

    /**
     * The water is drawn after the translucent features and writes depth, so a ghost drawn after it is hidden below the
     * surface. The blocks that stand in water are drawn here instead, before the water, like a real translucent block.
     */
    @Inject(
        method = "executeClassicTransparency",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V")
    )
    private void buildbuddy$ghostInWater(ChunkSectionsToRender chunkSectionsToRender, FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass, CallbackInfo ci) {
        GhostRenderer.drawEarly(renderPass);
    }

    /** With improved transparency the water goes in through its own passes: the blocks in water are drawn just before them. */
    @Inject(method = "executeOit", at = @At("HEAD"))
    private void buildbuddy$ghostInWaterOit(ChunkSectionsToRender chunkSectionsToRender, FeatureRenderDispatcher.PreparedFrame featureFrame, CallbackInfo ci) {
        GhostRenderer.drawEarly(Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }

    /**
     * The main pass has drawn terrain, entities and both transparency modes by the time the entity outline pass runs,
     * so the ghost goes in its own render pass here: the same for classic and improved transparency.
     */
    @Inject(method = "executeOutline", at = @At("HEAD"))
    private void buildbuddy$ghost(FeatureRenderDispatcher.PreparedFrame featureFrame, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GhostRenderer.render(mc, levelRenderState.cameraRenderState, target);
    }
}
