package com.emma.bridge.commands;

import emmatone.api.EmmatoneAPI;
import emmatone.api.process.IBuilderProcess;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/**
 * Handles "clear_area" command — clears all blocks in a region via
 * Emmatone's IBuilderProcess.clearArea().
 *
 * Params: { "x1","y1","z1", "x2","y2","z2" }
 * Returns: { "task_id", "volume" }
 */
public class ClearAreaHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "clear_area";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        int x1 = params.get("x1").getAsInt(), y1 = params.get("y1").getAsInt(), z1 = params.get("z1").getAsInt();
        int x2 = params.get("x2").getAsInt(), y2 = params.get("y2").getAsInt(), z2 = params.get("z2").getAsInt();

        BlockPos from = new BlockPos(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
        BlockPos to   = new BlockPos(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));

        IBuilderProcess builder = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone().getBuilderProcess();
        builder.clearArea(from, to);

        int volume = (to.getX() - from.getX() + 1)
                   * (to.getY() - from.getY() + 1)
                   * (to.getZ() - from.getZ() + 1);

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] ClearArea: ({},{},{}) to ({},{},{}) — {} blocks",
                from.getX(), from.getY(), from.getZ(),
                to.getX(), to.getY(), to.getZ(), volume);

        String taskId = TaskRegistry.registerTask("clear_area");

        JsonObject result = new JsonObject();
        result.addProperty("task_id", taskId);
        result.addProperty("command", "clear_area");
        result.addProperty("volume", volume);
        return result;
    }
}
