package com.emma.endinv.mixin;

import com.emma.endinv.client.CachedSrcInv;
import com.emma.endinv.util.recipeTransferHelper.RecipeItemProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.entity.player.StackedItemContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeBookComponent.class)
public class RecipeBookComponentMixin {

    @Final
    @Shadow
    private StackedItemContents stackedContents;
    @Shadow
    protected Minecraft minecraft;
    @Unique
    private final CachedSrcInv srcInv = CachedSrcInv.INSTANCE;

    @Inject(method = "initVisuals", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/RecipeBookMenu;fillCraftSlotsStackedContents(Lnet/minecraft/world/entity/player/StackedItemContents;)V"))
    private void fillEndInvStackedContents(CallbackInfo ci) {
        RecipeItemProvider.fillStackedItemContents(srcInv.getItemsAsList(), stackedContents);
    }

    @Inject(method = "updateStackedContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/RecipeBookMenu;fillCraftSlotsStackedContents(Lnet/minecraft/world/entity/player/StackedItemContents;)V"))
    private void updateStackedContentsOfEndInv(CallbackInfo ci) {
        RecipeItemProvider.fillStackedItemContents(srcInv.getItemsAsList(), stackedContents);
    }
}
