package com.emma.logger.mixin;

import com.emma.logger.GameplayLoggerMod;
import com.emma.logger.PlayerLogger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side mixin to detect when a tracked player takes damage.
 */
@Mixin(LivingEntity.class)
public abstract class ServerPlayerDamageMixin {

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void onDamage(ServerLevel world, DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayer player)) return;

        PlayerLogger logger = GameplayLoggerMod.getLogger(player);
        if (logger == null) return;

        long tick = player.level().getServer().getTickCount();
        float healthAfter = player.getHealth();

        // Determine source type
        String sourceType = source.type().msgId();

        // Attacker info
        String attacker = null;
        float attackerDist = -1;
        Entity attackerEntity = source.getEntity();
        if (attackerEntity != null) {
            attacker = BuiltInRegistries.ENTITY_TYPE.getKey(attackerEntity.getType()).toString();
            attackerDist = (float) attackerEntity.distanceTo(player);
        }

        logger.onDamageTaken(tick, amount, healthAfter, sourceType, attacker, attackerDist);

        // Check for death
        if (healthAfter <= 0) {
            String deathMsg = source.getLocalizedDeathMessage(player).getString();
            logger.onDeath(tick, deathMsg);
        }
    }
}
