package com.emma.logger.mixin;

import com.emma.logger.GameplayLoggerMod;
import com.emma.logger.PlayerLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts chat messages starting with "!note " to log annotations.
 * The message is consumed and NOT broadcast to other players.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerChatMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void onChatMessage(ServerboundChatPacket packet, CallbackInfo ci) {
        String message = packet.message();
        if (message == null || !message.startsWith("!note")) return;

        PlayerLogger logger = GameplayLoggerMod.getLogger(player);
        if (logger == null) return;

        // Cancel the chat message so it doesn't broadcast
        ci.cancel();

        long tick = player.level().getServer().getTickCount();
        String noteText = message.length() > 6 ? message.substring(6).trim() : "end";

        logger.onAnnotation(tick, noteText, player);

        // Send feedback to the annotating player only
        player.sendSystemMessage(
                Component.literal("[Logger] Annotation: " + noteText));
    }
}
