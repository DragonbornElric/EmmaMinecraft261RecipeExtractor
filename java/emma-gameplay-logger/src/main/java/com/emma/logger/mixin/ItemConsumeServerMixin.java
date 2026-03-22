package com.emma.logger.mixin;

import com.emma.logger.GameplayLoggerMod;
import com.emma.logger.PlayerLogger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects when a player finishes consuming an item (food, potion, etc.).
 * Captures health/hunger before and after.
 *
 * MC 1.21.8: completeUsingItem() takes no parameters and returns void.
 * The active item is accessed via LivingEntity.getUseItem().
 */
@Mixin(LivingEntity.class)
public abstract class ItemConsumeServerMixin {

    @Inject(method = "completeUsingItem", at = @At("HEAD"))
    private void beforeEat(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;
        if (!(self instanceof ServerPlayer player)) return;

        PlayerLogger logger = GameplayLoggerMod.getLogger(player);
        if (logger == null) return;

        ItemStack stack = self.getUseItem();
        if (stack.isEmpty()) return;

        // Store pre-eat state for comparison in afterEat
        GameplayLoggerMod.storePreEatState(player, player.getHealth(),
                player.getFoodData().getFoodLevel(),
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    @Inject(method = "completeUsingItem", at = @At("RETURN"))
    private void afterEat(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;
        if (!(self instanceof ServerPlayer player)) return;

        PlayerLogger logger = GameplayLoggerMod.getLogger(player);
        if (logger == null) return;

        float[] preState = GameplayLoggerMod.getPreEatState(player);
        if (preState == null) return;

        long tick = player.level().getServer().getTickCount();
        String item = GameplayLoggerMod.getPreEatItem(player);

        logger.onFoodEaten(tick, item,
                preState[0], (int) preState[1],       // health_before, hunger_before
                player.getHealth(), player.getFoodData().getFoodLevel()); // after

        GameplayLoggerMod.clearPreEatState(player);
    }
}
