package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.InventoryScanner;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;

/**
 * GOAP Action: Eat food when hungry.
 *
 * Preconditions: has food in inventory + hunger < 18
 * Score: stay_fed goal priority x hunger urgency (1.0 when starving, 0.0 when full)
 * Collateral: 0.3 relevance to "survive" goal (eating helps survival)
 *
 * Mechanics:
 *   1. BlockInteraction.forceEquipItem to main hand
 *   2. interactItem to start eating
 *   3. DirectInput.setUseHeld(true) to keep eating
 *   4. Re-fires interactItem if interrupted (damage, knockback)
 */
public class EatFoodAction extends GoapAction {

    /** Don't bother eating until hunger drops below this. */
    private static final int HUNGER_THRESHOLD = 18;

    /** Critical hunger level -- score spikes. */
    private static final int HUNGER_CRITICAL = 6;

    private boolean eating = false;
    private Item targetFood = null;
    /** Ticks remaining to wait after equip for server sync (0 = not waiting). */
    private int equipWaitTicks = 0;
    /** Ticks since we started eating — prevents re-fire spam. */
    private int eatTicks = 0;

    @Override
    public String getName() {
        return "EatFood";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return state.hasFood() && state.hunger < HUNGER_THRESHOLD;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        float goalPriority = goals.getGoal("stay_fed")
                .map(g -> g.priority)
                .orElse(6.0f);

        // Urgency curve: 0.0 at full (20), 1.0 at starving (0)
        float urgency = 1.0f - (state.hunger / 20.0f);

        // Spike urgency when critical
        if (state.hunger <= HUNGER_CRITICAL) {
            urgency = Math.max(urgency, 0.8f);
        }

        // Status effect urgency boost
        // DOT effects drain health — eating maintains regen to counter damage
        if (state.hasDamageOverTimeEffect()) {
            urgency = Math.max(urgency, 0.6f);
        }
        // Hunger effect drains hunger directly — eating counters it
        if (state.hasEffect("minecraft:hunger")) {
            urgency = Math.max(urgency, 0.7f);
        }

        // Direct relevance to stay_fed goal
        float relevance = 1.0f;

        return goalPriority * relevance * urgency;
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        // Find best food in inventory
        targetFood = findBestFood(client);
        if (targetFood == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP EatFood] No food found in inventory");
            return;
        }

        EmmaBridgeMod.LOGGER.info("[GOAP EatFood] execute: target={}", BuiltInRegistries.ITEM.getKey(targetFood));

        // Stop blocking if shielding
        if (player.isBlocking()) {
            player.stopUsingItem();
        }

        // Equip food — wait 2 ticks for server sync before eating
        boolean equipped = BlockInteraction.forceEquipItem(targetFood);
        EmmaBridgeMod.LOGGER.info("[GOAP EatFood] forceEquipItem={}, selectedSlot={}",
                equipped, player.getInventory().getSelectedSlot());
        equipWaitTicks = 2;
        eating = true;
        GoapStateFlags.get().isEating = true;
    }

    @Override
    public void tick(Minecraft client) {
        if (!eating) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        // Wait for server to sync held item after equip
        if (equipWaitTicks > 0) {
            equipWaitTicks--;
            if (equipWaitTicks == 0) {
                // Verify correct item is held
                if (targetFood != null && player.getMainHandItem().is(targetFood)) {
                    client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                    DirectInput.setUseHeld(true);
                    eatTicks = 0;
                    EmmaBridgeMod.LOGGER.info("[GOAP EatFood] Started eating {}", BuiltInRegistries.ITEM.getKey(targetFood));
                } else if (targetFood != null) {
                    // Wrong item — re-equip, try again
                    EmmaBridgeMod.LOGGER.warn("[GOAP EatFood] Wrong item held: {}, re-equipping {}",
                            BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()),
                            BuiltInRegistries.ITEM.getKey(targetFood));
                    BlockInteraction.forceEquipItem(targetFood);
                    equipWaitTicks = 2;
                }
            }
            return;
        }

        eatTicks++;

        // Check if we're full -- let GOAP naturally deactivate us
        if (player.getFoodData().getFoodLevel() >= 20) {
            return; // Score will drop to 0, another action will win
        }

        // Re-fire eating if interrupted (damage, knockback, etc.)
        // Only check after enough ticks for one eating cycle (32 ticks + buffer)
        if (!player.isUsingItem() && eatTicks > 36) {
            if (targetFood != null) {
                EmmaBridgeMod.LOGGER.info("[GOAP EatFood] Re-firing eat after interruption at tick {}", eatTicks);
                BlockInteraction.forceEquipItem(targetFood);
                equipWaitTicks = 2;
                return;
            }
        }

        // Keep use key held (only when actively eating, not during re-equip)
        DirectInput.setUseHeld(true);
    }

    @Override
    public void onDeactivated(Minecraft client) {
        if (eating) {
            DirectInput.setUseHeld(false);
            if (client.player != null) {
                client.player.stopUsingItem();
            }
            eating = false;
            GoapStateFlags.get().isEating = false;
            targetFood = null;
            equipWaitTicks = 0;
            eatTicks = 0;
        }
    }

    @Override
    public boolean isActive() {
        return eating;
    }

    @Override
    public int getMinimumActiveTicks() {
        return 40;  // eating takes 32 server ticks + 2 equip + buffer
    }

    // -- Collateral + personality -----------------------------------------

    @Override
    public String getPrimaryGoalId() {
        return "stay_fed";
    }

    @Override
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        if ("survive".equals(goal.id)) return 0.3f;  // eating helps survival
        return 0.0f;
    }

    @Override
    public String personalityCategory() {
        return "neutral";  // everyone needs to eat
    }

    // -- Debug ------------------------------------------------------------

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("hunger", state.hunger);
        bd.addProperty("urgency", 1.0f - (state.hunger / 20.0f));
        bd.addProperty("has_food", state.hasFood());
        bd.addProperty("target_food", targetFood != null
                ? BuiltInRegistries.ITEM.getKey(targetFood).toString() : "none");
        bd.addProperty("eating", eating);
        return bd;
    }

    // -- Food selection ---------------------------------------------------

    /**
     * Find the best food item in the player's inventory.
     * Prioritizes highest hunger restoration value.
     */
    private Item findBestFood(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return null;

        Item bestFood = null;
        int bestHunger = 0;
        for (var ss : InventoryScanner.findAll(player.getInventory(),
                stack -> stack.has(DataComponents.FOOD))) {
            FoodProperties foodComp = ss.stack().get(DataComponents.FOOD);
            if (foodComp == null) continue;
            int hunger = foodComp.nutrition();
            if (hunger > bestHunger) {
                bestHunger = hunger;
                bestFood = ss.stack().getItem();
            }
        }
        return bestFood;
    }
}
