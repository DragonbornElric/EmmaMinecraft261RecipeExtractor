package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Handles "attack" command — faces and swings at the nearest hostile mob.
 * Player-mode only (requires physical presence).
 *
 * Params: { "radius": int (optional, default 5) }
 * Returns: { "attacked": bool, "target_type": string, "distance": float }
 */
public class AttackHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "attack";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("attacked", false);
            result.addProperty("reason", "no_player");
            return result;
        }
        Minecraft client = Minecraft.getInstance();

        int radius = params.has("radius") ? params.get("radius").getAsInt() : 5;

        // Find nearest hostile
        Entity target = HandlerUtils.findNearestEntity(player, radius,
                entity -> entity instanceof Monster && entity.isAlive());
        Monster nearest = (Monster) target;

        if (nearest == null) {
            result.addProperty("attacked", false);
            result.addProperty("reason", "no_hostiles_nearby");
            return result;
        }

        // Face the target (simple aim at entity center — no lead prediction needed for melee)
        Vec3 targetPos = nearest.position().add(0, nearest.getBbHeight() * 0.5, 0);
        BlockInteraction.lookAt(targetPos);

        // Attack the entity
        String targetType = BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType()).toString();
        double nearestDist = player.distanceTo(nearest);

        if (nearestDist <= 4.0) {
            // Within melee range — direct attack
            client.gameMode.attack(player, nearest);
            player.swing(InteractionHand.MAIN_HAND);
        } else {
            // Too far for melee — just face it (caller should close distance first)
            result.addProperty("attacked", false);
            result.addProperty("reason", "out_of_range");
            result.addProperty("target_type", targetType);
            result.addProperty("distance", nearestDist);
            return result;
        }

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Attack: hit {} at distance {:.1f}",
                targetType, nearestDist);

        result.addProperty("attacked", true);
        result.addProperty("target_type", targetType);
        result.addProperty("distance", nearestDist);
        result.addProperty("target_health", nearest.getHealth());
        result.addProperty("target_max_health", nearest.getMaxHealth());
        return result;
    }
}
