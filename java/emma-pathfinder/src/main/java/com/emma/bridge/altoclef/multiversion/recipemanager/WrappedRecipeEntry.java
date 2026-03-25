package adris.altoclef.multiversion.recipemanager;

import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.registry.RegistryKey;

public record WrappedRecipeEntry(RegistryKey<Recipe<?>> id, Recipe<?> value) {

    public RecipeEntry<?> asRecipe() {
        return new RecipeEntry<>(id, value);
    }

}
