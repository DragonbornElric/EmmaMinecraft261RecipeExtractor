package com.emma.endinv.mixin;

import com.emma.endinv.ModInfo;
import com.emma.endinv.ModRegistries;
import com.emma.endinv.ServerLevelEndInv;
import com.emma.endinv.network.payloads.toClient.ItemPickedUpPayload;
import com.emma.endinv.options.ServerConfigs;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import org.slf4j.Logger;

import java.util.stream.Collectors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Throttle: only log once per second to avoid spam
    private static long lastLogTime = 0;

    @Shadow
    private UUID target;

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void endlessinv$autopick(Player player, CallbackInfo ci) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            boolean configEnabled = ServerConfigs.ENABLE_AUTOPICK.get();
            if (!configEnabled) {
                logThrottled("[endinv] autopick: server config disabled");
                return;
            }

            boolean playerEnabled = isPlayerEnabledAutoPick(serverPlayer);
            if (!playerEnabled) {
                logThrottled("[endinv] autopick: player config disabled for " + serverPlayer.getName().getString());
                return;
            }

            ItemEntity self = (ItemEntity) (Object) this;
            if (self.hasPickUpDelay()) return; // Normal — items just spawned

            // Respect target owner (items thrown to specific player)
            if (target != null && !target.equals(player.getUUID())) return;

            ItemStack stack = self.getItem();
            if (stack.isEmpty()) return;

            var endInvOpt = ServerLevelEndInv.getEndInvForPlayer(serverPlayer);
            if (endInvOpt.isEmpty()) {
                logThrottled("[endinv] autopick: no EndInv found for " + serverPlayer.getName().getString());
                return;
            }

            var endInv = endInvOpt.get();
            int originalCount = stack.getCount();
            ItemStack remain = endInv.addItem(stack.copy());

            int added = originalCount - remain.getCount();
            if (added > 0) {
                LOGGER.info("[endinv] autopick: {} x{} -> EndInv for {}",
                        stack.getItem(), added, serverPlayer.getName().getString());
                // Notify client about picked-up items
                ModInfo.getPacketDistributor().sendToPlayer(serverPlayer,
                        new ItemPickedUpPayload(stack.copyWithCount(added)));
                // Play pickup animation
                player.take(self, added);
                // Award crafting recipes unlocked by this item.
                // Vanilla does this via advancement triggers on normal pickup; EndInv bypasses
                // that flow entirely, so we replicate it here. awardRecipes is idempotent —
                // only newly-unlocked recipes generate a client packet.
                ItemStack stored = stack.copyWithCount(added);
                var toAward = ((ServerLevel) serverPlayer.level()).getServer().getRecipeManager()
                    .getRecipes().stream()
                    .filter(holder -> holder.value().getType() == RecipeType.CRAFTING)
                    .filter(holder -> holder.value().placementInfo().ingredients().stream()
                        .anyMatch(ing -> ing.test(stored)))
                        .collect(Collectors.toList());
                if (!toAward.isEmpty()) {
                    serverPlayer.awardRecipes(toAward);
                }
            }

            if (remain.isEmpty()) {
                // All items absorbed — remove entity and cancel vanilla pickup
                self.getItem().setCount(0);
                self.discard();
                ci.cancel();
            } else {
                // Partial absorption — update entity with remainder, let vanilla handle rest
                self.setItem(remain);
            }
        } catch (Exception e) {
            LOGGER.warn("[endinv] autopick error", e);
        }
    }

    private static void logThrottled(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 5000) {
            LOGGER.info(msg);
            lastLogTime = now;
        }
    }

    private static boolean isPlayerEnabledAutoPick(Player player) {
        return ModRegistries.NbtAttachments.getSyncedConfig().computeIfAbsent(player).autoPicking();
    }
}
