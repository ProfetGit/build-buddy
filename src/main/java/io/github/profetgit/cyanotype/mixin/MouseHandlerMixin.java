package io.github.profetgit.cyanotype.mixin;

import io.github.profetgit.cyanotype.interaction.Interaction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /** While a ghost follows the crosshair the wheel turns it instead of changing the hotbar. */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cyanotype$scroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        if (handle != 0L && handle == Minecraft.getInstance().getWindow().handle() && Interaction.onScroll(yoffset)) ci.cancel();
    }
}
