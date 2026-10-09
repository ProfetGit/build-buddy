package io.github.profetgit.buildbuddy.mixin;

import io.github.profetgit.buildbuddy.interaction.Interaction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /**
     * Remembers which modifier keys were down at the moment of a press, as the window system reported them with the click:
     * a click is read a tick later, and polling the keyboard then can miss a Shift or Ctrl that the event carried.
     */
    @Inject(method = "onButton", at = @At("HEAD"))
    private void buildbuddy$button(long handle, net.minecraft.client.input.MouseButtonInfo info, int action, CallbackInfo ci) {
        if (action == 1) Interaction.noteClick(info.modifiers());
    }

    /** While a ghost follows the crosshair the wheel turns it instead of changing the hotbar. */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void buildbuddy$scroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        if (handle != 0L && handle == Minecraft.getInstance().getWindow().handle() && Interaction.onScroll(yoffset)) ci.cancel();
    }
}
