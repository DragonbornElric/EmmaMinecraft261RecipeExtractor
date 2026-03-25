package adris.altoclef.multiversion.recipemanager;

import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.ServerRecipeManager;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Wraps recipe manager access for 1.21.8.
 *
 * In 1.21.8, RecipeManager is a minimal interface (no values() method).
 * ServerRecipeManager has values() -- we cast when possible (integrated server),
 * otherwise return an empty collection from values().
 */
public class RecipeManagerWrapper {

    private final RecipeManager recipeManager;

    public static RecipeManagerWrapper of(RecipeManager recipeManager) {
        if (recipeManager == null) return null;

        return new RecipeManagerWrapper(recipeManager);
    }


    private RecipeManagerWrapper(RecipeManager recipeManager) {
        this.recipeManager = recipeManager;
    }

    /**
     * In 1.21.8 the client-side RecipeManager no longer exposes recipe values.
     * Only ServerRecipeManager (integrated server) has values().
     * Returns empty collection if not available.
     */
    public Collection<WrappedRecipeEntry> values() {
        if (recipeManager instanceof ServerRecipeManager serverRM) {
            return serverRM.values().stream()
                    .map(r -> new WrappedRecipeEntry(r.id(), r.value()))
                    .collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }

}
