package com.emma.endinv.util.recipeTransferHelper;

import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

public interface RecipeItemProvider {

    boolean canExtract(List<Ingredient> items);

    List<ItemStack> doExtract(List<Ingredient> items);

    static void fillStackedContents(List<ItemStack> stackSource, StackedContents<ItemStack> stackedContents){
        stackSource.forEach(is->stackedContents.account(is,Integer.MAX_VALUE));
    }

    static void fillStackedItemContents(List<ItemStack> stackSource, StackedItemContents stackedContents){
        stackSource.forEach(is-> stackedContents.accountStack(is, Integer.MAX_VALUE));
    }
}
