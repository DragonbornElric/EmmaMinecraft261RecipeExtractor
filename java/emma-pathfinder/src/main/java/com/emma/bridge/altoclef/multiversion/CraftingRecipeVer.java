package adris.altoclef.multiversion;

import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.world.World;

import java.util.List;

public class CraftingRecipeVer {


    /**
     * In 1.21.8, CraftingRecipe no longer has getResult().
     * Use getDisplays() to obtain the result ItemStack via RecipeDisplay.
     * Falls back to ItemStack.EMPTY if no displays are available.
     */
    @Pattern
    public static ItemStack getOutput(CraftingRecipe craftingRecipe, World world) {
        List<RecipeDisplay> displays = craftingRecipe.getDisplays();
        if (displays.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ContextParameterMap params = SlotDisplayContexts.createParameters(world);
        return displays.getFirst().result().getFirst(params);
    }

}
