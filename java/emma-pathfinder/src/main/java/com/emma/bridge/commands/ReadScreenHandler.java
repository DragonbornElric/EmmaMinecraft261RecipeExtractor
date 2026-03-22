package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Handles "read_screen" command — reads the currently open screen/GUI.
 * Returns the screen type, all slot contents, and type-specific data
 * (e.g. trade offers for merchant screens).
 *
 * Params: {} (none)
 * Returns: { "screen_type": string, "slots": [...], "trades": [...] (if merchant) }
 */
public class ReadScreenHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "read_screen";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("screen_type", "none");
            result.addProperty("reason", "no_player");
            return result;
        }

        Screen currentScreen = client.screen;
        if (currentScreen == null) {
            result.addProperty("screen_type", "none");
            return result;
        }

        // Identify screen type
        String screenType = identifyScreen(currentScreen);
        result.addProperty("screen_type", screenType);
        result.addProperty("title", currentScreen.getTitle().getString());

        // Read all slots from the screen handler
        AbstractContainerMenu handler = player.containerMenu;
        if (handler != null) {
            JsonArray slots = new JsonArray();
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    JsonObject slotObj = new JsonObject();
                    slotObj.addProperty("slot", i);
                    slotObj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    slotObj.addProperty("count", stack.getCount());
                    if (stack.getDamageValue() > 0) {
                        slotObj.addProperty("damage", stack.getDamageValue());
                        slotObj.addProperty("max_damage", stack.getMaxDamage());
                    }
                    slots.add(slotObj);
                }
            }
            result.add("slots", slots);
            result.addProperty("slot_count", handler.slots.size());
        }

        // Type-specific data
        if (currentScreen instanceof MerchantScreen merchantScreen) {
            addTradeOffers(result, merchantScreen);
        }

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] read_screen: type={}", screenType);
        return result;
    }

    private String identifyScreen(Screen screen) {
        if (screen instanceof MerchantScreen) return "merchant";
        if (screen instanceof CraftingScreen) return "crafting_table";
        if (screen instanceof InventoryScreen) return "player_inventory";
        if (screen instanceof ContainerScreen) return "chest";
        if (screen instanceof FurnaceScreen) return "furnace";
        if (screen instanceof BlastFurnaceScreen) return "blast_furnace";
        if (screen instanceof SmokerScreen) return "smoker";
        if (screen instanceof BrewingStandScreen) return "brewing_stand";
        if (screen instanceof EnchantmentScreen) return "enchanting_table";
        if (screen instanceof AnvilScreen) return "anvil";
        if (screen instanceof SmithingScreen) return "smithing_table";
        if (screen instanceof LoomScreen) return "loom";
        if (screen instanceof CartographyTableScreen) return "cartography_table";
        if (screen instanceof GrindstoneScreen) return "grindstone";
        if (screen instanceof StonecutterScreen) return "stonecutter";
        if (screen instanceof ShulkerBoxScreen) return "shulker_box";
        if (screen instanceof HopperScreen) return "hopper";
        if (screen instanceof BeaconScreen) return "beacon";
        if (screen instanceof HorseInventoryScreen) return "horse";
        // Fallback
        return screen.getClass().getSimpleName().toLowerCase().replace("screen", "");
    }

    private void addTradeOffers(JsonObject result, MerchantScreen screen) {
        try {
            MerchantOffers offers = screen.getMenu().getOffers();
            if (offers == null || offers.isEmpty()) return;

            JsonArray trades = new JsonArray();
            for (int i = 0; i < offers.size(); i++) {
                MerchantOffer offer = offers.get(i);
                JsonObject trade = new JsonObject();
                trade.addProperty("index", i);

                // First input
                ItemCost traded1 = offer.getItemCostA();
                ItemStack input1 = traded1.itemStack();
                trade.addProperty("input1", BuiltInRegistries.ITEM.getKey(input1.getItem()).toString());
                trade.addProperty("input1_count", input1.getCount());

                // Second input (optional)
                offer.getItemCostB().ifPresent(traded2 -> {
                    ItemStack input2 = traded2.itemStack();
                    trade.addProperty("input2", BuiltInRegistries.ITEM.getKey(input2.getItem()).toString());
                    trade.addProperty("input2_count", input2.getCount());
                });

                // Output
                ItemStack output = offer.getResult();
                trade.addProperty("output", BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
                trade.addProperty("output_count", output.getCount());

                trade.addProperty("uses", offer.getUses());
                trade.addProperty("max_uses", offer.getMaxUses());
                trade.addProperty("disabled", offer.isOutOfStock());

                trades.add(trade);
            }
            result.add("trades", trades);
        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Failed to read trade offers: {}", e.getMessage());
        }
    }
}
