package com.emma.logger.mixin;

import com.emma.logger.GameplayLoggerMod;
import com.emma.logger.PlayerLogger;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects when a living entity dies near a tracked player.
 * Used to log kills and entity deaths for combat analysis.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void onEntityDeath(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer) return; // player deaths handled by damage mixin
        if (self.level().isClientSide()) return;

        ServerLevel world = (ServerLevel) self.level();
        long tick = world.getServer().getTickCount();
        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(self.getType()).toString();

        // Check if the attacker was a tracked player
        if (source.getEntity() instanceof ServerPlayer player) {
            PlayerLogger logger = GameplayLoggerMod.getLogger(player);
            if (logger == null) return;

            JsonObject event = new JsonObject();
            event.addProperty("tick", tick);
            event.addProperty("type", "kill");
            event.addProperty("entity", entityType);
            event.addProperty("dist", Math.round(self.distanceTo(player) * 10.0) / 10.0);

            // Weapon used
            net.minecraft.world.item.ItemStack weapon = player.getMainHandItem();
            if (!weapon.isEmpty()) {
                event.addProperty("weapon",
                        BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString());
            }

            logger.logEvent(event);
        }
    }
}
