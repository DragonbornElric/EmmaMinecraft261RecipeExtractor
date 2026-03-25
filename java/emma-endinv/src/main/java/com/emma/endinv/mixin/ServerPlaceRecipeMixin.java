package com.emma.endinv.mixin;

import com.emma.endinv.EndlessInventory;
import com.emma.endinv.ServerLevelEndInv;
import com.emma.endinv.util.recipeTransferHelper.RecipeItemProvider;
import net.minecraft.core.Holder;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.jetbrains.annotations.Nullable;

@Mixin(ServerPlaceRecipe.class)
public class ServerPlaceRecipeMixin<R extends Recipe<?>> {

    @Shadow
    private Inventory inventory;

    @Unique
    @Nullable
    private EndlessInventory ei$endInv;

    @Inject(method = "tryPlaceRecipe", at = @At("HEAD"))
    private void ei$fillContents(RecipeHolder<R> recipe, StackedItemContents contents, CallbackInfoReturnable<?> cir) {
        ei$endInv = null;
        if (inventory.player instanceof ServerPlayer sp) {
            ei$endInv = ServerLevelEndInv.getEndInvForPlayer(sp).orElse(null);
        }
        if (ei$endInv != null) {
            RecipeItemProvider.fillStackedItemContents(ei$endInv.getItemsAsList(), contents);
        }
    }

    @Inject(method = "moveItemToGrid", at = @At("RETURN"), cancellable = true)
    private void ei$moveFromEndInv(Slot slot, Holder<Item> item, int count, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValue() != -1) return;
        if (ei$endInv == null) return;

        ItemStack taken = ei$endInv.takeItem(new ItemStack(item), count);
        if (taken.isEmpty()) return;

        ItemStack cur = slot.getItem();
        if (cur.isEmpty()) {
            slot.set(taken);
        } else {
            cur.grow(taken.getCount());
        }
        cir.setReturnValue(count - taken.getCount());
    }

    @Inject(method = "tryPlaceRecipe", at = @At("RETURN"))
    private void ei$broadcastChanges(RecipeHolder<R> recipe, StackedItemContents contents, CallbackInfoReturnable<?> cir) {
        if (ei$endInv != null) {
            ei$endInv.broadcastChanges();
            ei$endInv = null;
        }
    }
}
