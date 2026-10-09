package io.github.profetgit.buildbuddy.mixin;

import io.github.profetgit.buildbuddy.auto.AutoBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    /** The sign editor the server opens for a sign auto-placing has just put down is not shown (its text is sent instead). */
    @Inject(method = "openTextEdit", at = @At("HEAD"), cancellable = true)
    private void buildbuddy$sign(SignBlockEntity sign, SignTextSlot slot, CallbackInfo ci) {
        if (AutoBuilder.takeSignEditor(Minecraft.getInstance(), sign, slot)) ci.cancel();
    }
}
