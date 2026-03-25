package adris.altoclef.util;

import adris.altoclef.multiversion.RecipeVer;
import adris.altoclef.multiversion.recipemanager.RecipeManagerWrapper;
import adris.altoclef.multiversion.recipemanager.WrappedRecipeEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.context.ContextParameterMap;

import java.util.*;
import java.util.stream.Collectors;

/**
 * For crafting table/inventory recipe book crafting, we need to figure out identifiers given a recipe.
 */
public class JankCraftingRecipeMapping {
    private static final HashMap<Item, List<WrappedRecipeEntry>> recipeMapping = new HashMap<>();

    /**
     * Reloads the recipe mapping.
     */
    private static void reloadRecipeMapping() {
        MinecraftClient client = MinecraftClient.getInstance();

        // Check if the network handler is available
        if (client.getNetworkHandler() != null) {
            RecipeManagerWrapper recipes = RecipeManagerWrapper.of(client.getNetworkHandler().getRecipeManager());
            ClientWorld world = client.world;

            // Check if the recipe manager is available
            if (recipes != null) {
                for (WrappedRecipeEntry recipe : recipes.values()) {
                    assert world != null;
                    Recipe<?> value = recipe.value();
                    Item output = RecipeVer.getOutput(value, world).getItem();
                    recipeMapping.computeIfAbsent(output, k -> new ArrayList<>()).add(recipe);
                }
            }
        }
    }

    /**
     * Retrieves the mapped recipe for a given output item from the Minecraft crafting recipe.
     *
     * @param recipe The crafting recipe to check against.
     * @param output The output item of the recipe.
     * @return An Optional containing the mapped recipe entry if found, or an empty Optional if not found.
     */
    public static Optional<WrappedRecipeEntry> getMinecraftMappedRecipe(CraftingRecipe recipe, Item output) {
        reloadRecipeMapping();
        // Check if the output item is present in the recipe mapping
        if (recipeMapping.containsKey(output)) {
            // Iterate through all the recipes mapped to the output item
            for (WrappedRecipeEntry checkRecipe : recipeMapping.get(output)) {
                // Create a list of item targets to satisfy
                List<ItemTarget> toSatisfy = Arrays.stream(recipe.getSlots())
                        .filter(itemTarget -> itemTarget != null && !itemTarget.isEmpty())
                        .collect(Collectors.toList());
                // In 1.21.8, getIngredients() moved to getIngredientPlacement().getIngredients()
                List<Ingredient> ingredients = checkRecipe.value().getIngredientPlacement().getIngredients();
                // Check if the recipe has ingredients
                if (!ingredients.isEmpty()) {
                    // Iterate through the ingredients of the recipe
                    for (Ingredient ingredient : ingredients) {
                        // Skip empty ingredients
                        if (ingredient.isEmpty()) {
                            continue;
                        }
                        // Iterate through the items to satisfy
                        outer:
                        for (int i = 0; i < toSatisfy.size(); ++i) {
                            ItemTarget target = toSatisfy.get(i);
                            // In 1.21.8, getMatchingStacks() replaced with getMatchingItems()
                            // which returns Stream<RegistryEntry<Item>>
                            List<Item> matchingItems = ingredient.getMatchingItems()
                                    .map(RegistryEntry::value)
                                    .toList();
                            for (Item matchItem : matchingItems) {
                                if (target.matches(matchItem)) {
                                    toSatisfy.remove(i);
                                    break outer;
                                }
                            }
                        }
                    }
                }
                // Check if all the item targets have been satisfied
                if (toSatisfy.isEmpty()) {
                    return Optional.of(checkRecipe);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * In 1.21.8, clickRecipe() requires a NetworkRecipeId instead of RecipeEntry.
     * Search the ClientRecipeBook for a RecipeDisplayEntry whose result matches
     * the target output item, and return its NetworkRecipeId.
     *
     * @param output The target output item to find in the recipe book.
     * @return An Optional containing the NetworkRecipeId if found.
     */
    public static Optional<NetworkRecipeId> findNetworkRecipeId(Item output) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return Optional.empty();

        ClientRecipeBook recipeBook = player.getRecipeBook();
        ContextParameterMap params = SlotDisplayContexts.createParameters(client.world);

        for (RecipeResultCollection collection : recipeBook.getOrderedResults()) {
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                RecipeDisplay display = entry.display();
                // Only match crafting recipes — skip stonecutter, furnace, smithing etc.
                // Items like stone_bricks have both crafting AND stonecutter recipes;
                // returning a stonecutter NetworkRecipeId to a crafting handler silently fails.
                if (!(display instanceof ShapedCraftingRecipeDisplay)
                        && !(display instanceof ShapelessCraftingRecipeDisplay)) {
                    continue;
                }
                ItemStack resultStack = display.result().getFirst(params);
                if (!resultStack.isEmpty() && resultStack.getItem() == output) {
                    return Optional.of(entry.id());
                }
            }
        }
        return Optional.empty();
    }
}
