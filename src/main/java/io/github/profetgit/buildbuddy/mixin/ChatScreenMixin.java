package io.github.profetgit.buildbuddy.mixin;

import io.github.profetgit.buildbuddy.command.DevCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    /** /buildbuddy ... stays on the client: it is handled here and never sent to the server. */
    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void buildbuddy$command(String msg, boolean addToRecent, CallbackInfo ci) {
        String trimmed = msg.trim();
        if (!DevCommands.handles(trimmed)) return;
        if (addToRecent) Minecraft.getInstance().gui.hud.getChat().addRecentChat(trimmed);
        DevCommands.run(trimmed);
        ci.cancel();
    }
}
