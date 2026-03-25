package com.emma.endinv.mixin;

import com.emma.endinv.options.ServerConfigs;
import com.emma.endinv.event.MobDeathRedirect;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDropMixin {

    @Inject(method = "dropFromLootTable", at = @At("HEAD"))
    private void endlessinv$captureKiller(DamageSource source, boolean hitByPlayer, CallbackInfo ci) {
        if (source == null) return;
        var attacker = source.getEntity();
        if (attacker instanceof ServerPlayer sp) {
            if (ServerConfigs.ENABLE_AUTOPICK.get()) {
                MobDeathRedirect.set(sp);
            }
        }
    }

    @Inject(method = "dropFromLootTable", at = @At("TAIL"))
    private void endlessinv$clearKiller(DamageSource source, boolean hitByPlayer, CallbackInfo ci) {
        MobDeathRedirect.clear();
    }
}

