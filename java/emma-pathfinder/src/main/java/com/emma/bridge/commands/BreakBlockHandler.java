package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Handles "break_block" command — initiates breaking a single block at (x, y, z).
 * Uses the client interaction manager's attack/break API.
 *
 * Params: { "x": int, "y": int, "z": int }
 * Returns: { "breaking": bool, "block": string, "x": int, "y": int, "z": int }
 */
public class BreakBlockHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "break_block";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("breaking", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        BlockPos pos = HandlerUtils.extractBlockPos(params);
        if (pos == null) {
            result.addProperty("breaking", false);
            result.addProperty("reason", "missing_coordinates");
            return result;
        }

        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) {
            result.addProperty("breaking", false);
            result.addProperty("reason", "air_block");
            return result;
        }

        double dist = HandlerUtils.checkRange(player, pos, 6.0);
        if (dist < 0) {
            result.addProperty("breaking", false);
            result.addProperty("reason", "out_of_range");
            result.addProperty("block", HandlerUtils.blockId(state));
            result.addProperty("distance", Math.sqrt(player.blockPosition().distSqr(pos)));
            return result;
        }

        String blockType = HandlerUtils.blockId(state);
        float hardness = state.getDestroySpeed(player.level(), pos);

        // Start breaking the block
        Minecraft.getInstance().gameMode.startDestroyBlock(pos, Direction.UP);

        result.addProperty("breaking", true);
        result.addProperty("block", blockType);
        result.addProperty("hardness", hardness);
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] break_block: {} at ({},{},{})",
                blockType, pos.getX(), pos.getY(), pos.getZ());
        return result;
    }
}
