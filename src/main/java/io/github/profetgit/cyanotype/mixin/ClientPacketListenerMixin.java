package io.github.profetgit.cyanotype.mixin;

import io.github.profetgit.cyanotype.paste.Paste;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    /** The server's "Changed the block at ..." answers to an op paste's own commands are not shown in the chat. */
    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void cyanotype$quietPaste(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (Paste.isOwnCommandFeedback(packet.content())) ci.cancel();
    }
}
