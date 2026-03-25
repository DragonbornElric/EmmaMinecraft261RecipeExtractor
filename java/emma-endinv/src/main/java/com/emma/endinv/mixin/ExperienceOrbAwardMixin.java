package com.emma.endinv.mixin;

import com.emma.endinv.event.MobDeathRedirect;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbAwardMixin {

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private static void endlessinv$redirectAward(ServerLevel level, Vec3 pos, int amount, CallbackInfo ci) {
        if (amount <= 0) return;

        // Only redirect XP when we have a captured killer (mob/block contexts).
        // For player death drops (no killer captured), let vanilla spawn XP orbs.
        ServerPlayer target = MobDeathRedirect.get();
        if (target == null) return;

        int repaired = eiRepairPlayerItems(target, amount);
        target.giveExperiencePoints(repaired);
        ci.cancel();
    }

    // Use a unique name to avoid clashing with ExperienceOrb's own methods.
    private static int eiRepairPlayerItems(ServerPlayer player, int value) {
        return value;
    }
}
