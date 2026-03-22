package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.events.PanicTeleport;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Handles "panic_teleport" command — configures and triggers the PanicTeleport system.
 *
 * Params:
 *   action: "enable" | "disable" | "configure" | "status" | "reset" | "trigger"
 *   threshold: float (health threshold in half-hearts, e.g. 4.0 = 2 hearts)
 *   safe_x, safe_y, safe_z: double (teleport destination)
 *   cooldown_ms: long (cooldown between automatic triggers)
 *
 * The "trigger" action force-fires teleport immediately, bypassing health check and cooldown.
 *
 * Response: { "enabled": bool, "threshold": float, "safe_x/y/z": double,
 *             "cooldown_ms": long, "triggered": bool }
 */
public class PanicTeleportHandler implements ICommandHandler {

    private final PanicTeleport panicTeleport;

    public PanicTeleportHandler(PanicTeleport panicTeleport) {
        this.panicTeleport = panicTeleport;
    }

    @Override
    public String getCommand() {
        return "panic_teleport";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        if (params.has("action")) {
            String action = params.get("action").getAsString();
            switch (action) {
                case "enable" -> panicTeleport.setEnabled(true);
                case "disable" -> panicTeleport.setEnabled(false);
                case "reset" -> panicTeleport.resetTrigger();
                case "trigger" -> {
                    // Force-fire teleport immediately (GUI "Panic!" button)
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player != null) {
                        panicTeleport.forceTrigger(player);
                        result.addProperty("triggered_manually", true);
                    } else {
                        result.addProperty("error", "No player entity available");
                    }
                }
                case "configure" -> applyConfigParams(params);
                case "status" -> { /* just return current state */ }
                default -> result.addProperty("warning", "unknown action: " + action);
            }
        }

        // Also support setting params without action
        if (!params.has("action")) {
            applyConfigParams(params);
        }

        // Build response with current state
        result.addProperty("enabled", panicTeleport.isEnabled());
        result.addProperty("threshold", panicTeleport.getHealthThreshold());
        result.addProperty("safe_x", panicTeleport.getSafeX());
        result.addProperty("safe_y", panicTeleport.getSafeY());
        result.addProperty("safe_z", panicTeleport.getSafeZ());
        result.addProperty("cooldown_ms", panicTeleport.getCooldownMs());
        result.addProperty("triggered", panicTeleport.isTriggered());

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] panic_teleport: enabled={}, threshold={}, coords=({},{},{})",
                panicTeleport.isEnabled(), panicTeleport.getHealthThreshold(),
                panicTeleport.getSafeX(), panicTeleport.getSafeY(), panicTeleport.getSafeZ());

        return result;
    }

    private void applyConfigParams(JsonObject params) {
        if (params.has("threshold"))
            panicTeleport.setHealthThreshold(params.get("threshold").getAsFloat());
        if (params.has("safe_x") || params.has("safe_y") || params.has("safe_z"))
            panicTeleport.setSafeCoords(
                    params.has("safe_x") ? params.get("safe_x").getAsDouble() : panicTeleport.getSafeX(),
                    params.has("safe_y") ? params.get("safe_y").getAsDouble() : panicTeleport.getSafeY(),
                    params.has("safe_z") ? params.get("safe_z").getAsDouble() : panicTeleport.getSafeZ());
        if (params.has("cooldown_ms"))
            panicTeleport.setCooldownMs(params.get("cooldown_ms").getAsLong());
    }
}
