package com.emma.bridge.commands;

import emmatone.api.EmmatoneAPI;
import emmatone.api.process.IFarmProcess;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Handles "create_farm" command — tills dirt/grass and plants seeds
 * via Emmatone's IFarmProcess.
 *
 * Params: { "range": int (default 4), "x","y","z" (optional center) }
 * Returns: { "task_id", "range", "center_x/y/z" }
 */
public class CreateFarmHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "create_farm";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        int range = params.has("range") ? params.get("range").getAsInt() : 4;

        BlockPos center = null;
        if (params.has("x") && params.has("z")) {
            int x = params.get("x").getAsInt();
            int y = params.has("y") ? params.get("y").getAsInt()
                    : (int) Minecraft.getInstance().player.getY();
            int z = params.get("z").getAsInt();
            center = new BlockPos(x, y, z);
        }

        IFarmProcess farm = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone().getFarmProcess();
        if (center != null) {
            farm.farm(range, center);
            EmmaBridgeMod.LOGGER.info("[Emma Bridge] CreateFarm: range={} center=({},{},{})",
                    range, center.getX(), center.getY(), center.getZ());
        } else {
            farm.farm(range);
            EmmaBridgeMod.LOGGER.info("[Emma Bridge] CreateFarm: range={} (player position)", range);
        }

        String taskId = TaskRegistry.registerTask("create_farm");

        JsonObject result = new JsonObject();
        result.addProperty("task_id", taskId);
        result.addProperty("command", "create_farm");
        result.addProperty("range", range);
        if (center != null) {
            result.addProperty("center_x", center.getX());
            result.addProperty("center_y", center.getY());
            result.addProperty("center_z", center.getZ());
        }
        return result;
    }
}
