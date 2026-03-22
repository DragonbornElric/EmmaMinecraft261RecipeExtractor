package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Handles "interact_block" command — right-clicks a block at (x, y, z).
 * Covers: opening chests/furnaces/brewing stands/enchanting tables/barrel/shulker,
 * pressing buttons, pulling levers, opening doors/trapdoors/gates, using beds,
 * interacting with crafting tables, anvils, looms, grindstones, etc.
 *
 * Params: { "x": int, "y": int, "z": int,
 *           "hand": "main"|"off" (default "main"),
 *           "face": "up"|"down"|"north"|"south"|"east"|"west" (default "up") }
 * Returns: { "interacted": bool, "block": string }
 */
public class InteractBlockHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "interact_block";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("interacted", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        BlockPos pos = HandlerUtils.extractBlockPos(params);
        if (pos == null) {
            result.addProperty("interacted", false);
            result.addProperty("reason", "missing_coordinates");
            return result;
        }

        InteractionHand hand = HandlerUtils.parseHand(params);

        Direction face = HandlerUtils.parseDirection(params, "face", Direction.UP);

        // Check distance
        double dist = HandlerUtils.checkRange(player, pos, 6.0);
        if (dist < 0) {
            String blockType = HandlerUtils.blockId(player.level().getBlockState(pos));
            result.addProperty("interacted", false);
            result.addProperty("reason", "out_of_range");
            result.addProperty("block", blockType);
            result.addProperty("distance", Math.sqrt(player.blockPosition().distSqr(pos)));
            return result;
        }

        // Build hit result
        BlockHitResult hitResult = BlockInteraction.createHitResult(pos, face);

        String blockType = HandlerUtils.blockId(player.level().getBlockState(pos));

        // Right-click the block
        var actionResult = Minecraft.getInstance().gameMode.useItemOn(player, hand, hitResult);

        result.addProperty("interacted", actionResult.consumesAction());
        result.addProperty("block", blockType);
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] interact_block: {} at ({},{},{})",
                blockType, pos.getX(), pos.getY(), pos.getZ());
        return result;
    }
}
