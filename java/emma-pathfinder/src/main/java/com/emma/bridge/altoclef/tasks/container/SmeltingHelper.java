package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.mixins.AbstractFurnaceScreenHandlerAccessor;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ContainerHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.PropertyDelegate;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared furnace-type logic for furnace, blast furnace, and smoker tasks.
 * <p>
 * All three furnace types use the same screen handler protocol and identical
 * slot layout (0=material, 1=fuel, 2=output). Only the ScreenHandler subclass
 * and the {@code Slot} subclass differ (FurnaceSlot vs BlastFurnaceSlot vs SmokerSlot).
 * <p>
 * Fixes applied here (diagnostic logging, PropertyDelegate clamping,
 * material.isEmpty() guard, fallback fuel injection) propagate to ALL
 * furnace types automatically.
 */
public final class SmeltingHelper {

    private SmeltingHelper() {}

    // ─── Cache ───────────────────────────────────────────────────────────

    /**
     * Furnace state cache — shared by furnace, blast furnace, smoker.
     * Replaces the per-task FurnaceCache, BlastFurnaceCache, SmokerCache classes.
     */
    public static class SmeltCache {
        public ItemStack materialSlot = ItemStack.EMPTY;
        public ItemStack fuelSlot = ItemStack.EMPTY;
        public ItemStack outputSlot = ItemStack.EMPTY;
        public double burningFuelCount = 0;
        public double burnPercentage = 0;
    }

    // ─── Config ──────────────────────────────────────────────────────────

    /**
     * Configuration for a specific furnace type.
     * Provides slot references and fuel/cook state queries.
     */
    public record FurnaceConfig(
            Slot materialSlot,
            Slot fuelSlot,
            Slot outputSlot,
            Supplier<Double> getFuel,
            Supplier<Double> getCookPercent,
            String name
    ) {}

    // ─── Cache Update ────────────────────────────────────────────────────

    /**
     * Update the cache from the currently open furnace screen.
     * Call from the task's tryUpdateOpen method.
     */
    public static void updateCache(SmeltCache cache, FurnaceConfig config) {
        cache.burnPercentage = config.getCookPercent().get();
        cache.burningFuelCount = config.getFuel().get();
        cache.fuelSlot = StorageHelper.getItemStackInSlot(config.fuelSlot());
        cache.materialSlot = StorageHelper.getItemStackInSlot(config.materialSlot());
        cache.outputSlot = StorageHelper.getItemStackInSlot(config.outputSlot());
    }

    // ─── Math ────────────────────────────────────────────────────────────

    /**
     * Calculate how many materials still need to be acquired.
     * <p>
     * Formula: mat_target - out_in_inventory - mat_in_furnace - out_in_furnace
     */
    public static int calculateMaterialsNeeded(AltoClef mod, SmeltTarget target,
                                                ItemTarget allMaterials, SmeltCache cache) {
        ItemTarget materialTarget = allMaterials;
        ItemTarget outputTarget = target.getItem();
        return materialTarget.getTargetCount()
                - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                - (materialTarget.matches(cache.materialSlot.getItem()) ? cache.materialSlot.getCount() : 0)
                - (outputTarget.matches(cache.outputSlot.getItem()) ? cache.outputSlot.getCount() : 0);
    }

    /**
     * Calculate how much fuel is still needed.
     */
    public static double calculateFuelNeeded(AltoClef mod, SmeltTarget target,
                                              ItemTarget allMaterials, SmeltCache cache,
                                              boolean ignoreMaterials) {
        ItemTarget materialTarget = allMaterials;
        ItemTarget outputTarget = target.getItem();
        double totalFuelInFurnace = ItemHelper.getFuelAmount(cache.fuelSlot)
                + cache.burningFuelCount + cache.burnPercentage;

        if (ignoreMaterials) {
            return Math.min(
                    materialTarget.matches(cache.materialSlot.getItem()) ? cache.materialSlot.getCount() : 0,
                    materialTarget.getTargetCount()
            ) - totalFuelInFurnace;
        }
        return materialTarget.getTargetCount()
                - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                - (outputTarget.matches(cache.outputSlot.getItem()) ? cache.outputSlot.getCount() : 0)
                - totalFuelInFurnace;
    }

    // ─── Fuel Selection ──────────────────────────────────────────────────

    /**
     * Find the best fuel stack in player inventory for a given need.
     * Prefers the smallest stack that covers the need; if none covers it,
     * picks the largest available stack.
     *
     * @return the best fuel stack, or null if none found
     */
    @Nullable
    public static ItemStack findBestFuel(AltoClef mod, double needs) {
        double closestDelta = Double.NEGATIVE_INFINITY;
        ItemStack bestStack = null;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (mod.getModSettings().isSupportedFuel(stack.getItem())) {
                double fuelAmount = ItemHelper.getFuelAmount(stack.getItem()) * stack.getCount();
                double delta = needs - fuelAmount;
                if ((bestStack == null)
                        || (closestDelta > 0 && delta < closestDelta)
                        || (delta < 0 && delta > closestDelta)) {
                    bestStack = stack;
                    closestDelta = delta;
                }
            }
        }
        return bestStack;
    }

    // ─── Diagnostics ─────────────────────────────────────────────────────

    /**
     * Log PropertyDelegate values for debugging furnace state.
     * Only logs if the current screen handler is an AbstractFurnaceScreenHandler.
     *
     * @param mod    the AltoClef instance
     * @param config the furnace config
     */
    public static void logDiagnostics(AltoClef mod, FurnaceConfig config) {
        if (mod.getPlayer().currentScreenHandler instanceof AbstractFurnaceScreenHandler furnaceHandler) {
            PropertyDelegate pd = ((AbstractFurnaceScreenHandlerAccessor) furnaceHandler).getPropertyDelegate();
            ItemStack material = StorageHelper.getItemStackInSlot(config.materialSlot());
            ItemStack fuel = StorageHelper.getItemStackInSlot(config.fuelSlot());
            ItemStack output = StorageHelper.getItemStackInSlot(config.outputSlot());
            double rawFuel = config.getFuel().get();
            double rawCook = config.getCookPercent().get();

            Debug.logInternal("[%s DIAG] PropertyDelegate: [0]=%d [1]=%d [2]=%d [3]=%d | fuel=%.3f cook=%.3f | material=%s x%d | fuel=%s x%d | output=%s x%d",
                    config.name().toUpperCase(),
                    pd.get(0), pd.get(1), pd.get(2), pd.get(3),
                    rawFuel, rawCook,
                    material.getItem(), material.getCount(),
                    fuel.getItem(), fuel.getCount(),
                    output.getItem(), output.getCount());
        }
    }

    /**
     * Get safe (clamped to 0) fuel value from the furnace config.
     * getFurnaceFuel() returns -1 if screen not ready.
     */
    public static double safeFuel(FurnaceConfig config) {
        return Math.max(config.getFuel().get(), 0);
    }

    /**
     * Get safe (clamped to 0) cook percent from the furnace config.
     */
    public static double safeCookPercent(FurnaceConfig config) {
        return Math.max(config.getCookPercent().get(), 0);
    }

    // ─── Container Sub-Task ─────────────────────────────────────────────

    /**
     * Shared container sub-task logic for all furnace types (furnace, smoker, blast furnace).
     * Handles output retrieval, material/fuel filling, and clean exit.
     *
     * @param mod            the AltoClef instance
     * @param config         furnace slot/state configuration
     * @param target         the smelt target (input → output)
     * @param allMaterials   combined material item target (primary + optional materials)
     * @param setDebugState  callback to set the caller task's debug state string
     * @return the next sub-task to run, or null if waiting
     */
    @Nullable
    public static Task containerSubTask(AltoClef mod, FurnaceConfig config,
                                         SmeltTarget target, ItemTarget allMaterials,
                                         Consumer<String> setDebugState) {
        ItemStack output = StorageHelper.getItemStackInSlot(config.outputSlot());
        ItemStack material = StorageHelper.getItemStackInSlot(config.materialSlot());
        ItemStack fuel = StorageHelper.getItemStackInSlot(config.fuelSlot());

        logDiagnostics(mod, config);
        double safeFuel = safeFuel(config);
        double safeCook = safeCookPercent(config);

        // 1. Recover excess fuel if smelting complete (material slot empty)
        double currentlyCachedWhileCooking = safeFuel + safeCook;
        double needsWhileCooking = material.getCount() - currentlyCachedWhileCooking;
        if (needsWhileCooking <= 0 && material.isEmpty()) {
            if (!fuel.isEmpty()) {
                if (!ContainerHelper.canFitInPlayerInventory(mod, config.fuelSlot())) {
                    setDebugState.accept("Inventory full, making space for fuel");
                    return new EnsureFreeInventorySlotTask();
                }
                Debug.logInternal("[%s] Recovering excess fuel (material slot empty)", config.name());
                ContainerHelper.quickMoveToPlayer(mod, config.fuelSlot());
                return null;
            }
        }

        // 2. Retrieve output via QUICK_MOVE
        if (!output.isEmpty()) {
            if (!ContainerHelper.canFitInPlayerInventory(mod, config.outputSlot())) {
                setDebugState.accept("Inventory full, making space for output");
                return new EnsureFreeInventorySlotTask();
            }
            setDebugState.accept("Receiving Output");
            ContainerHelper.quickMoveToPlayer(mod, config.outputSlot());
            return null;
        }

        // 3. Fill materials if needed — guard on neededMaterialsInSlot > 0 to prevent
        //    loop when all raw materials are consumed and material slot is empty (AIR).
        int neededMaterialsInSlot = allMaterials.getTargetCount()
                - mod.getItemStorage().getItemCountInventoryOnly(target.getItem().getMatches())
                - (target.getItem().matches(output.getItem()) ? output.getCount() : 0);
        if (neededMaterialsInSlot > 0
                && (!allMaterials.matches(material.getItem()) || neededMaterialsInSlot > material.getCount())) {
            int materialsAlreadyIn = (allMaterials.matches(material.getItem()) ? material.getCount() : 0);
            setDebugState.accept("Moving Materials");
            return new MoveItemToSlotFromInventoryTask(
                    new ItemTarget(allMaterials, neededMaterialsInSlot - materialsAlreadyIn),
                    config.materialSlot());
        }

        // 4. Fill fuel if needed
        if (fuel.isEmpty() || ItemHelper.isFuel(fuel.getItem())) {
            double currentlyCached = safeFuel + safeCook;
            double needs = material.getCount() - currentlyCached;
            if (needs > 0) {
                ItemStack bestStack = findBestFuel(mod, needs);
                if (bestStack != null) {
                    setDebugState.accept("Filling fuel");
                    Debug.logInternal("[%s] Filling fuel: %s x%d (needs=%.2f)",
                            config.name(), bestStack.getItem(), bestStack.getCount(), needs);
                    return new MoveItemToSlotFromInventoryTask(
                            new ItemTarget(bestStack.getItem(), bestStack.getCount()),
                            config.fuelSlot());
                }
            }
        }

        // 5. Fallback fuel injection to prevent infinite "Waiting..." hang
        if (fuel.isEmpty() && !material.isEmpty()) {
            ItemStack fallbackFuel = findBestFuel(mod, material.getCount());
            if (fallbackFuel != null) {
                Debug.logInternal("[%s] Fallback fuel injection: %s x%d",
                        config.name(), fallbackFuel.getItem(), fallbackFuel.getCount());
                setDebugState.accept("Filling fuel (fallback)");
                return new MoveItemToSlotFromInventoryTask(
                        new ItemTarget(fallbackFuel.getItem(), fallbackFuel.getCount()),
                        config.fuelSlot());
            }
        }

        // 6. If nothing left to do and not actively cooking, close the container.
        //    Next tick isContainerOpen() returns false, letting isFinished() trigger.
        if (material.isEmpty() && output.isEmpty() && safeCook <= 0) {
            setDebugState.accept("Smelting complete — closing container");
            StorageHelper.closeScreen();
            return null;
        }

        setDebugState.accept("Waiting...");
        return null;
    }
}
