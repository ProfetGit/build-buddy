package io.github.profetgit.cyanotype.mixin;

import io.github.profetgit.cyanotype.demo.Director;
import io.github.profetgit.cyanotype.interaction.Interaction;
import io.github.profetgit.cyanotype.placement.Session;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void cyanotype$tick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        Session.tick(mc);
        Interaction.tick(mc);
        io.github.profetgit.cyanotype.ui.Settings.tick();
        if (Director.ACTIVE) Director.onTick(mc);
    }

    /** The dev demo's frame grabber (inert unless the game runs with -Dcyanotype.demo). */
    @Inject(method = "runTick", at = @At("TAIL"))
    private void cyanotype$frame(boolean advanceGameTime, CallbackInfo ci) {
        io.github.profetgit.cyanotype.ui.Motion.endFrame();
        io.github.profetgit.cyanotype.paste.Paste.frameEnded();
        if (Director.ACTIVE) Director.onFrame((Minecraft) (Object) this);
    }

    /** A click that locks a ghost, or grabs a handle, is the mod's and not a swing or a block break. */
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void cyanotype$attack(CallbackInfoReturnable<Boolean> cir) {
        if (Interaction.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void cyanotype$use(CallbackInfo ci) {
        if (Interaction.onUse()) ci.cancel();
    }

    /** Holding the button after such a click must not start breaking the block behind it. */
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void cyanotype$hold(boolean down, CallbackInfo ci) {
        if (down && Interaction.suppressHold()) ci.cancel();
    }
}
