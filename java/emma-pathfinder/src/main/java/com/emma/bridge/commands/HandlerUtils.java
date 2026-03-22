package com.emma.bridge.commands;

import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Shared utilities for bridge command handlers.
 * Eliminates duplicated boilerplate across PlaceBlockHandler,
 * BreakBlockHandler, InteractBlockHandler, and others.
 * <p>
 * Behavioral fixes to coordinate extraction, range checks, direction parsing,
 * hit result construction, or sign text extraction only need to happen here.
 */
public final class HandlerUtils {

    private HandlerUtils() {}

    /**
     * Get the player entity, or null if not in-game.
     * Caller should check for null and return an appropriate error JsonObject
     * (each handler uses a different result key, so error building stays handler-side).
     */
    @Nullable
    public static LocalPlayer requirePlayer() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return null;
        }
        return client.player;
    }

    /**
     * Extract a BlockPos from "x", "y", "z" integer fields in params.
     * Returns null if any field is missing.
     */
    @Nullable
    public static BlockPos extractBlockPos(JsonObject params) {
        if (!params.has("x") || !params.has("y") || !params.has("z")) {
            return null;
        }
        return new BlockPos(
                params.get("x").getAsInt(),
                params.get("y").getAsInt(),
                params.get("z").getAsInt()
        );
    }

    /**
     * Check if a block position is within reach of the player.
     * Returns the distance if within range, or -1 if out of range.
     *
     * @param player  the player entity
     * @param pos     the target block position
     * @param maxDist maximum interaction distance (typically 6.0)
     */
    public static double checkRange(LocalPlayer player, BlockPos pos, double maxDist) {
        double dist = Math.sqrt(player.blockPosition().distSqr(pos));
        return dist <= maxDist ? dist : -1;
    }

    /**
     * Parse a Direction from a named field in params.
     * Returns defaultDir if the field is missing or invalid.
     */
    public static Direction parseDirection(JsonObject params, String field, Direction defaultDir) {
        if (!params.has(field)) return defaultDir;
        try {
            return Direction.valueOf(params.get(field).getAsString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultDir;
        }
    }

    /**
     * Get the registry ID string for a block state's block.
     * Shorthand for BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().
     */
    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * Extract readable text from a sign block entity (front + back).
     * Returns null if no text content or not a sign.
     */
    @Nullable
    public static String extractSignText(Level world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) return null;

        StringBuilder sb = new StringBuilder();
        appendSignSide(sb, sign.getFrontText(), "");
        appendSignSide(sb, sign.getBackText(), " | ");

        if (sb.length() == 0) return null;
        return sb.toString();
    }

    /**
     * Parse a InteractionHand from the "hand" field in params.
     * Returns MAIN_HAND unless "off" is specified.
     */
    public static InteractionHand parseHand(JsonObject params) {
        String handStr = params.has("hand") ? params.get("hand").getAsString() : "main";
        return handStr.equalsIgnoreCase("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /**
     * Find the nearest entity matching a predicate within a radius.
     * Returns null if no matching entity is found.
     *
     * @param player  the player entity
     * @param radius  scan radius (bounding box expansion)
     * @param filter  predicate to test each entity (e.g., alive + type check)
     */
    @Nullable
    public static Entity findNearestEntity(LocalPlayer player, int radius,
                                            Predicate<Entity> filter) {
        AABB scanBox = player.getBoundingBox().inflate(radius);
        List<Entity> entities = player.level().getEntities(player, scanBox);
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : entities) {
            if (!filter.test(entity)) continue;
            double dist = player.distanceTo(entity);
            if (dist < nearestDist) {
                nearest = entity;
                nearestDist = dist;
            }
        }
        return nearest;
    }

    private static void appendSignSide(StringBuilder sb, SignText signText, String separator) {
        StringBuilder side = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String line = signText.getMessage(i, false).getString().trim();
            if (!line.isEmpty()) {
                if (side.length() > 0) side.append(" / ");
                side.append(line);
            }
        }
        if (side.length() > 0) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(side);
        }
    }
}
