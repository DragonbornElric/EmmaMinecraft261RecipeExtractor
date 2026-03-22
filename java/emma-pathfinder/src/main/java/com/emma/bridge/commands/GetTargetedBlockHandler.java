package com.emma.bridge.commands;

import com.google.gson.JsonObject;
import emmatone.api.utils.VecUtils;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Handles "get_targeted_block" command — returns what the crosshair is pointing at.
 * Uses Minecraft.hitResult (zero-cost, already computed each frame).
 * Available in BOTH player and camera modes.
 *
 * Params: {} (none)
 * Returns: { "type": "block"|"entity"|"miss", ... }
 */
public class GetTargetedBlockHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "get_targeted_block";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft mc = Minecraft.getInstance();
        HitResult target = mc.hitResult;

        JsonObject result = new JsonObject();

        if (target == null || target.getType() == HitResult.Type.MISS) {
            result.addProperty("type", "miss");
            return result;
        }

        if (target.getType() == HitResult.Type.BLOCK && target instanceof BlockHitResult blockHit) {
            result.addProperty("type", "block");
            BlockPos pos = blockHit.getBlockPos();
            result.addProperty("x", pos.getX());
            result.addProperty("y", pos.getY());
            result.addProperty("z", pos.getZ());
            result.addProperty("face", blockHit.getDirection().getSerializedName());

            if (mc.level != null) {
                BlockState state = mc.level.getBlockState(pos);
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                result.addProperty("block", blockId);

                // Block state properties
                if (!state.getProperties().isEmpty()) {
                    result.add("state", ScanHandler.statePropsToJson(state));
                }

                // Light at targeted block
                result.addProperty("light_level", mc.level.getMaxLocalRawBrightness(pos));

                // Sign text (always-on for targeted block)
                if (state.getBlock() instanceof SignBlock) {
                    String signText = HandlerUtils.extractSignText(mc.level, pos);
                    if (signText != null) {
                        result.addProperty("text", signText);
                    }
                }
            }

            // Distance from player
            if (mc.player != null) {
                double dist = VecUtils.entityDistanceToCenter(mc.player, pos);
                result.addProperty("distance", Math.round(dist * 100.0) / 100.0);
            }

        } else if (target.getType() == HitResult.Type.ENTITY && target instanceof EntityHitResult entityHit) {
            result.addProperty("type", "entity");
            Entity entity = entityHit.getEntity();
            String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            result.addProperty("entity_type", entityType);

            if (entity.hasCustomName()) {
                result.addProperty("name", entity.getCustomName().getString());
            }

            result.addProperty("x", entity.getX());
            result.addProperty("y", entity.getY());
            result.addProperty("z", entity.getZ());

            if (entity instanceof LivingEntity living) {
                result.addProperty("health", living.getHealth());
                result.addProperty("max_health", living.getMaxHealth());
            }

            // Distance from player
            if (mc.player != null) {
                result.addProperty("distance",
                        Math.round(mc.player.distanceTo(entity) * 100.0) / 100.0);
            }
        }

        return result;
    }

}
