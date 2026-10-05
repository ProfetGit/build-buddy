package io.github.profetgit.cyanotype.mixin;

import io.github.profetgit.cyanotype.ui.CyanotypeHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
    /** The mod's panel and chips go on top of the game's own HUD. */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cyanotype$hud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        CyanotypeHud.draw(Minecraft.getInstance(), graphics);
    }
}
