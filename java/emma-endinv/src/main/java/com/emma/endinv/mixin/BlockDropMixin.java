package com.emma.endinv.mixin;

import com.emma.endinv.ModInfo;
import com.emma.endinv.ServerLevelEndInv;
import com.emma.endinv.autopick.AutoPickHelper;
import com.emma.endinv.network.payloads.toClient.ItemPickedUpPayload;
import com.emma.endinv.event.BlockBreakRedirect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jetbrains.annotations.Nullable;

@Mixin(Block.class)
public abstract class BlockDropMixin {

    @Inject(method = "playerDestroy", at = @At("HEAD"))
    private void endlessinv$captureBreaker(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp && AutoPickHelper.isEnabled(sp)) {
            BlockBreakRedirect.pushBreaker(sp);
        } else {
            BlockBreakRedirect.clearBreaker();
        }
    }

    @Inject(method = "playerDestroy", at = @At("TAIL"))
    private void endlessinv$clearBreaker(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        BlockBreakRedirect.clearBreaker();
    }

    @Inject(method = "popResource*", at = @At("HEAD"), cancellable = true)
    private static void endlessinv$redirectPopResource(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        ServerPlayer breaker = BlockBreakRedirect.currentBreaker();
        if (!(level instanceof ServerLevel serverLevel) || breaker == null || stack.isEmpty()) {
            return;
        }

        ServerLevelEndInv.getEndInvForPlayer(breaker).ifPresent(endInv -> {
            ItemStack remain = endInv.addItem(stack.copy());
            int inserted = stack.getCount() - remain.getCount();
            if (inserted > 0) {
                ItemStack picked = stack.copy();
                picked.setCount(inserted);
                ModInfo.getPacketDistributor().sendToPlayer(breaker, new ItemPickedUpPayload(picked));
            }

            if (remain.isEmpty()) {
                ci.cancel();
            } else {
                // Spawn only the remaining part
                ItemEntity entity = new ItemEntity(serverLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, remain);
                serverLevel.addFreshEntity(entity);
                ci.cancel();
            }
        });
    }

    @Inject(method = "popExperience", at = @At("HEAD"), cancellable = true)
    private static void endlessinv$redirectPopExp(ServerLevel level, BlockPos pos, int amount, CallbackInfo ci) {
        ServerPlayer breaker = BlockBreakRedirect.currentBreaker();
        if (breaker == null || amount <= 0) {
            return;
        }
        int repaired = repairPlayerItems(breaker, amount);
        breaker.giveExperiencePoints(repaired);
        ci.cancel();
    }

    // Simplified for 1.21.8: let vanilla handle mending elsewhere
    private static int repairPlayerItems(ServerPlayer player, int value) {
        return value;
    }
}
