package com.emma.logger.mixin;

import com.emma.logger.GameplayLoggerMod;
import com.emma.logger.PlayerLogger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks container screen close events server-side.
 * Open events are handled in GameplayLoggerMod via the server player's
 * openHandledScreen call tracking.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "removed", at = @At("HEAD"))
    private void onScreenClosed(Player player, CallbackInfo ci) {
        if (player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        PlayerLogger logger = GameplayLoggerMod.getLogger(serverPlayer);
        if (logger == null) return;

        AbstractContainerMenu handler = (AbstractContainerMenu) (Object) this;
        long tick = serverPlayer.level().getServer().getTickCount();
        String containerType;
        try {
            var type = handler.getType();
            containerType = type != null ?
                    BuiltInRegistries.MENU.getKey(type).toString() :
                    "unknown";
        } catch (UnsupportedOperationException e) {
            // PlayerScreenHandler and some others don't have a registered type
            containerType = handler.getClass().getSimpleName();
        }

        // Duration tracked via GameplayLoggerMod's screen open time map
        long openTick = GameplayLoggerMod.getScreenOpenTick(serverPlayer);
        long duration = openTick > 0 ? tick - openTick : 0;

        logger.onContainerClosed(tick, containerType, duration);
        GameplayLoggerMod.clearScreenOpenTick(serverPlayer);
    }
}
