package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Handles "place_block" command — places the held block at (x, y, z).
 * This is a convenience command that builds a BlockHitResult targeting the
 * adjacent block face, then calls interactBlock to place.
 *
 * Params: { "x": int, "y": int, "z": int,
 *           "face": "up"|"down"|"north"|"south"|"east"|"west" (default "up"),
 *           "facing": "north"|"south"|"east"|"west" (optional — sets player yaw
 *                     before placement so directional blocks like beds, stairs,
 *                     pistons face the correct direction. Restored after placement.) }
 * Returns: { "placed": bool, "block": string }
 */
public class PlaceBlockHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "place_block";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("placed", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        BlockPos targetPos = HandlerUtils.extractBlockPos(params);
        if (targetPos == null) {
            result.addProperty("placed", false);
            result.addProperty("reason", "missing_coordinates");
            return result;
        }

        Direction face = HandlerUtils.parseDirection(params, "face", Direction.UP);

        // The block we click on is the one adjacent in the opposite direction
        BlockPos clickPos = targetPos.relative(face.getOpposite());

        double dist = HandlerUtils.checkRange(player, targetPos, 6.0);
        if (dist < 0) {
            result.addProperty("placed", false);
            result.addProperty("reason", "out_of_range");
            result.addProperty("distance", Math.sqrt(player.blockPosition().distSqr(targetPos)));
            return result;
        }

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        String itemName = held.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();

        // Optional facing override — temporarily set player yaw so directional
        // blocks (beds, stairs, pistons, etc.) place in the desired orientation.
        float originalYaw = player.getYRot();
        boolean yawOverridden = false;
        if (params.has("facing")) {
            String facing = params.get("facing").getAsString().toLowerCase();
            float yaw = switch (facing) {
                case "south" -> 0f;
                case "west"  -> 90f;
                case "north" -> 180f;
                case "east"  -> -90f;
                default -> Float.NaN;
            };
            if (!Float.isNaN(yaw)) {
                player.setYRot(yaw);
                yawOverridden = true;
            } else {
                EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Unknown facing '{}', ignoring", facing);
            }
        }

        // Build hit result: clicking on the adjacent block's face
        BlockHitResult hitResult = BlockInteraction.createHitResult(clickPos, face);

        var actionResult = Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);

        // Restore original yaw
        if (yawOverridden) {
            player.setYRot(originalYaw);
        }

        result.addProperty("placed", actionResult.consumesAction());
        result.addProperty("block", itemName);
        result.addProperty("x", targetPos.getX());
        result.addProperty("y", targetPos.getY());
        result.addProperty("z", targetPos.getZ());

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] place_block: {} at ({},{},{}){}",
                itemName, targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                yawOverridden ? " facing=" + params.get("facing").getAsString() : "");
        return result;
    }
}
