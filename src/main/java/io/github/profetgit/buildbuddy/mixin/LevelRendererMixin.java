package io.github.profetgit.buildbuddy.mixin;

import io.github.profetgit.buildbuddy.ghost.GhostRenderer;
import io.github.profetgit.buildbuddy.interaction.Interaction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26.3 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
//?}
//? if <26.3 {
/*import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.util.Util;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
*///?}
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
        //? if <26.3 {
        /*if (buildbuddy$shaderPack()) {
            try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(buildbuddy$late)) {
                Interaction.frame(mc, state.cameraRenderState);
            }
        } else
        *///?}
        Interaction.frame(mc, state.cameraRenderState);
        GhostRenderer.prepare(mc, state.cameraRenderState);
    }

    //? if <26.3 {
    /*/^* The tools' gizmos while a shader pack is on: Iris 1.11.4 keeps the depth of the always-on-top pass and composites over it, so they go in after its final pass. ^/
    @org.spongepowered.asm.mixin.Unique
    private final SimpleGizmoCollector buildbuddy$late = new SimpleGizmoCollector();
    @org.spongepowered.asm.mixin.Unique
    private final SubmitNodeStorage buildbuddy$lateStorage = new SubmitNodeStorage();
    @org.spongepowered.asm.mixin.Unique
    private static java.lang.invoke.@org.jspecify.annotations.Nullable MethodHandle buildbuddy$inUse;
    @org.spongepowered.asm.mixin.Unique
    private static boolean buildbuddy$looked;

    @org.spongepowered.asm.mixin.Unique
    private static boolean buildbuddy$shaderPack() {
        if (!buildbuddy$looked) {
            buildbuddy$looked = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                buildbuddy$inUse = java.lang.invoke.MethodHandles.publicLookup().unreflect(api.getMethod("isShaderPackInUse")).bindTo(instance);
            } catch (ReflectiveOperationException | LinkageError e) {
                buildbuddy$inUse = null;
            }
        }
        if (buildbuddy$inUse == null) return false;
        try {
            return (boolean) buildbuddy$inUse.invoke();
        } catch (Throwable e) {
            return false;
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void buildbuddy$lateGizmos(GraphicsResourceAllocator allocator, DeltaTracker delta, boolean renderOutline, CameraRenderState camera,
                                       Matrix4fc modelView, GpuBufferSlice fog, Vector4f fogColor, boolean sky, CallbackInfo ci) {
        List<SimpleGizmoCollector.GizmoInstance> gizmos = buildbuddy$late.drainGizmos();
        if (gizmos.isEmpty()) return;
        DrawableGizmoPrimitives primitives = new DrawableGizmoPrimitives();
        long now = Util.getMillis();
        for (SimpleGizmoCollector.GizmoInstance g : gizmos) g.gizmo().emit(primitives, g.getAlphaMultiplier(now));
        primitives.submit(buildbuddy$lateStorage, camera, true);
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.mul(modelView);
        RenderSystem.setShaderFog(fog);
        RenderSystem.outputColorTextureOverride = main.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = main.getDepthTextureView();
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(main.getDepthTexture(), 0.0);
        try {
            mc.gameRenderer.featureRenderDispatcher().renderAllFeatures(buildbuddy$lateStorage);
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            stack.popMatrix();
        }
    }
    *///?}

    //? if >=26.3 {
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
    //?}
}
