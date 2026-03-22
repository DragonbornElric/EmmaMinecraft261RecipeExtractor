package com.emma.logger.state;

import com.emma.logger.LogConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.Comparator;
import java.util.List;

/**
 * Captures environmental awareness: nearby entities (with LOS),
 * crosshair target, blocks below/ahead, nearest hazard.
 */
public class EnvironmentCapture {

    public JsonObject capture(ServerPlayer player, ServerLevel world) {
        JsonObject obj = new JsonObject();

        // Nearby entities with LOS
        obj.add("nearby_entities", captureNearbyEntities(player, world));

        // Crosshair target (what the player is looking at)
        obj.add("crosshair_target", captureCrosshairTarget(player, world));

        // Blocks below (fall risk detection)
        obj.add("blocks_below", captureBlocksBelow(player, world));

        // Blocks ahead (obstacle awareness)
        obj.add("blocks_ahead", captureBlocksAhead(player, world));

        // Nearest hazard
        obj.add("nearest_hazard", captureNearestHazard(player, world));

        return obj;
    }

    private JsonArray captureNearbyEntities(ServerPlayer player, ServerLevel world) {
        JsonArray entities = new JsonArray();
        int radius = LogConfig.entityScanRadius;
        Vec3 playerPos = player.position();
        Vec3 eyePos = player.getEyePosition();
        float playerYaw = player.getYRot();

        AABB scanBox = player.getBoundingBox().inflate(radius);
        List<Entity> nearby = world.getEntities(player, scanBox);

        // Sort by distance, limit count
        nearby.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
        int count = Math.min(nearby.size(), LogConfig.maxEntitiesPerSnapshot);

        for (int i = 0; i < count; i++) {
            Entity entity = nearby.get(i);
            if (entity.isSpectator()) continue;

            JsonObject e = new JsonObject();
            String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            e.addProperty("type", entityType);

            double dist = entity.distanceTo(player);
            e.addProperty("dist", Math.round(dist * 10.0) / 10.0);

            // Relative direction from player's facing
            double dx = entity.getX() - playerPos.x;
            double dz = entity.getZ() - playerPos.z;
            float angleToEntity = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            float relativeAngle = Mth.wrapDegrees(angleToEntity - playerYaw);
            e.addProperty("dir", Math.round(relativeAngle * 10.0f) / 10.0f);

            // Health (for living entities)
            if (entity instanceof LivingEntity living) {
                e.addProperty("health", Math.round(living.getHealth() * 10.0f) / 10.0f);
            }

            // Line of sight — critical for threat assessment
            boolean los = hasLineOfSight(world, eyePos, entity);
            e.addProperty("los", los);

            // Is this mob targeting the player?
            if (entity instanceof Mob mob) {
                e.addProperty("targeting", mob.getTarget() == player);
            }

            // Movement speed
            Vec3 vel = entity.getDeltaMovement();
            double speed = vel.horizontalDistance();
            e.addProperty("vel", Math.round(speed * 100.0) / 100.0);

            entities.add(e);
        }

        return entities;
    }

    /**
     * LOS check via raytrace from player eyes to entity center.
     */
    private boolean hasLineOfSight(ServerLevel world, Vec3 eyePos, Entity target) {
        Vec3 targetPos = target.getBoundingBox().getCenter();
        BlockHitResult result = world.clip(new ClipContext(
                eyePos, targetPos,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()
        ));
        // If the ray hit something before reaching the target, no LOS
        if (result.getType() == HitResult.Type.MISS) return true;
        double distToBlock = result.getLocation().distanceToSqr(eyePos);
        double distToTarget = targetPos.distanceToSqr(eyePos);
        return distToBlock >= distToTarget * 0.95; // small tolerance
    }

    private JsonObject captureCrosshairTarget(ServerPlayer player, ServerLevel world) {
        JsonObject obj = new JsonObject();
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        double reach = 5.0; // standard interaction reach
        Vec3 endPos = eyePos.add(lookVec.scale(reach));

        // Check for entity first
        AABB entityBox = player.getBoundingBox().expandTowards(lookVec.scale(reach)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, eyePos, endPos,
                entityBox, e -> !e.isSpectator() && e.isPickable(), reach * reach);

        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            obj.addProperty("type", "entity");
            obj.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            obj.addProperty("dist", Math.round(entity.distanceTo(player) * 10.0) / 10.0);
            return obj;
        }

        // Block raycast
        BlockHitResult blockHit = world.clip(new ClipContext(
                eyePos, endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));

        if (blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = world.getBlockState(pos);
            obj.addProperty("type", "block");
            obj.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            obj.addProperty("x", pos.getX());
            obj.addProperty("y", pos.getY());
            obj.addProperty("z", pos.getZ());
        } else {
            obj.addProperty("type", "none");
        }

        return obj;
    }

    private JsonArray captureBlocksBelow(ServerPlayer player, ServerLevel world) {
        JsonArray blocks = new JsonArray();
        BlockPos pos = player.blockPosition();
        for (int dy = 1; dy <= 5; dy++) {
            BlockPos below = pos.below(dy);
            BlockState state = world.getBlockState(below);
            blocks.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        }
        return blocks;
    }

    private JsonArray captureBlocksAhead(ServerPlayer player, ServerLevel world) {
        JsonArray blocks = new JsonArray();
        Vec3 lookVec = player.getLookAngle();
        BlockPos playerPos = player.blockPosition();

        for (int i = 1; i <= 5; i++) {
            int x = playerPos.getX() + (int) Math.round(lookVec.x * i);
            int y = playerPos.getY() + (int) Math.round(lookVec.y * i);
            int z = playerPos.getZ() + (int) Math.round(lookVec.z * i);
            BlockState state = world.getBlockState(new BlockPos(x, y, z));
            blocks.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        }
        return blocks;
    }

    private JsonObject captureNearestHazard(ServerPlayer player, ServerLevel world) {
        JsonObject hazard = new JsonObject();
        Vec3 playerPos = player.position();
        float playerYaw = player.getYRot();

        // Check for nearest hostile with LOS
        double nearestHostileDist = Double.MAX_VALUE;
        Entity nearestHostile = null;

        AABB scanBox = player.getBoundingBox().inflate(LogConfig.entityScanRadius);
        for (Entity entity : world.getEntities(player, scanBox)) {
            if (entity instanceof Monster hostile) {
                double dist = hostile.distanceTo(player);
                if (dist < nearestHostileDist && hasLineOfSight(world, player.getEyePosition(), hostile)) {
                    nearestHostileDist = dist;
                    nearestHostile = hostile;
                }
            }
        }

        if (nearestHostile != null) {
            hazard.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(nearestHostile.getType()).toString());
            hazard.addProperty("dist", Math.round(nearestHostileDist * 10.0) / 10.0);

            double dx = nearestHostile.getX() - playerPos.x;
            double dz = nearestHostile.getZ() - playerPos.z;
            float angle = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            hazard.addProperty("dir", Math.round(Mth.wrapDegrees(angle - playerYaw) * 10.0f) / 10.0f);
        }
        // TODO: check for lava, void proximity in future iterations

        return hazard;
    }
}
