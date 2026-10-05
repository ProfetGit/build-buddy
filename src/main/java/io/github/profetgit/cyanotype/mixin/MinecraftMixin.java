package io.github.profetgit.cyanotype.mixin;

import io.github.profetgit.cyanotype.demo.Director;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void cyanotype$tick(CallbackInfo ci) {
        if (Director.ACTIVE) Director.onTick((Minecraft) (Object) this);
    }

    /** The dev demo's frame grabber (inert unless the game runs with -Dcyanotype.demo). */
    @Inject(method = "runTick", at = @At("TAIL"))
    private void cyanotype$frame(boolean advanceGameTime, CallbackInfo ci) {
        if (Director.ACTIVE) Director.onFrame((Minecraft) (Object) this);
    }
}
