package adris.altoclef.multiversion;

import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.world.World;

import java.util.List;

public class RecipeVer {


    /**
     * In 1.21.8, Recipe no longer has getResult(DynamicRegistryManager).
     * Use getDisplays() to obtain the result ItemStack via RecipeDisplay.
     */
    public static ItemStack getOutput(Recipe<?> recipe, World world) {
        List<RecipeDisplay> displays = recipe.getDisplays();
        if (displays.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ContextParameterMap params = SlotDisplayContexts.createParameters(world);
        return displays.getFirst().result().getFirst(params);
    }


}
