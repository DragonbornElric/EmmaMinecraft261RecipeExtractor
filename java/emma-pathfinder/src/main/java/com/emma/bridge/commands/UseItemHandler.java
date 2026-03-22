package com.emma.bridge.commands;

import com.emma.bridge.control.DirectInput;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;

/**
 * Handles "use_item" command — uses the item currently held in the specified hand.
 * Covers: eating food, drinking potions, throwing ender pearls, shooting bows,
 * blocking with shields, using fishing rods, placing boats, etc.
 *
 * For charged items (bows, crossbows, shields), set duration_ticks to hold
 * before releasing. The release is handled via a tick counter on the client thread.
 *
 * Params: { "hand": "main"|"off" (default "main"), "duration_ticks": int (optional) }
 * Returns: { "used": bool, "item": string, "hand": string }
 */
public class UseItemHandler implements ICommandHandler {

    // Tick counter for charged/held items (bow draw, shield hold, eating)
    private static int holdTicksRemaining = 0;
    private static boolean holdingItem = false;

    @Override
    public String getCommand() {
        return "use_item";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("used", false);
            result.addProperty("reason", "no_player");
            return result;
        }
        Minecraft client = Minecraft.getInstance();

        InteractionHand hand = HandlerUtils.parseHand(params);
        String handStr = hand == InteractionHand.OFF_HAND ? "off" : "main";
        ItemStack stack = player.getItemInHand(hand);

        if (stack.isEmpty()) {
            result.addProperty("used", false);
            result.addProperty("reason", "empty_hand");
            result.addProperty("hand", handStr);
            return result;
        }

        String itemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

        int durationTicks = params.has("duration_ticks") ? params.get("duration_ticks").getAsInt() : 0;

        if (durationTicks > 0) {
            // Start holding — the tick handler will release after N ticks
            InteractionResult actionResult = client.gameMode.useItem(player, hand);
            holdTicksRemaining = durationTicks;
            holdingItem = true;

            result.addProperty("used", true);
            result.addProperty("item", itemName);
            result.addProperty("hand", handStr);
            result.addProperty("holding", true);
            result.addProperty("duration_ticks", durationTicks);
        } else {
            // Instant use
            InteractionResult actionResult = client.gameMode.useItem(player, hand);
            result.addProperty("used", actionResult.consumesAction());
            result.addProperty("item", itemName);
            result.addProperty("hand", handStr);
        }

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] use_item: {} (hand={}, duration={})",
                itemName, handStr, durationTicks);
        return result;
    }

    /**
     * Called every tick from EmmaBridgeClient to handle held-item release.
     */
    public static void tick() {
        if (!holdingItem) return;

        // Keep use key held so vanilla handleInputEvents() doesn't cancel eating/using.
        // Phase 52 removed setPressed() for movement keys (Emmatone conflict); use key is safe.
        DirectInput.setUseHeld(true);

        holdTicksRemaining--;
        if (holdTicksRemaining <= 0) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.stopUsingItem();
            }
            DirectInput.setUseHeld(false);
            holdingItem = false;
            EmmaBridgeMod.LOGGER.debug("[Emma Bridge] use_item: released held item");
        }
    }
}
