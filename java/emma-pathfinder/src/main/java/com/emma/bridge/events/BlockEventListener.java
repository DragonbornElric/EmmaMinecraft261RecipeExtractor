package com.emma.bridge.events;

import com.emma.bridge.BridgeServer;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Listens for block place/break events via Fabric API callbacks.
 *
 * Block break uses Fabric's {@code PlayerBlockBreakEvents.AFTER}.
 * Block placement uses a tick-deferred approach: {@code UseBlockCallback}
 * records candidate positions, and the next tick checks if a non-air block
 * now exists there.
 */
public class BlockEventListener {

    private final BridgeServer ws;

    /** Candidate positions from UseBlockCallback, checked on next tick. */
    private final Queue<BlockPos> pendingPlacements = new ConcurrentLinkedQueue<>();

    public BlockEventListener(BridgeServer ws) {
        this.ws = ws;
    }

    /**
     * Register Fabric API block event callbacks.
     * Call this during client initialization.
     */
    public void register() {
        // Block break — fires after a block is broken by the player
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register(
                (world, player, pos, state, blockEntity) -> {
                    if (world.isClientSide()) {
                        JsonObject data = new JsonObject();
                        data.addProperty("x", pos.getX());
                        data.addProperty("y", pos.getY());
                        data.addProperty("z", pos.getZ());
                        data.addProperty("block_type",
                                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());

                        // Include state properties if any
                        if (!state.getProperties().isEmpty()) {
                            data.addProperty("block_state",
                                    com.emma.bridge.commands.ScanHandler.stateToString(state));
                        }

                        ws.broadcastEvent(JsonProtocol.event("block_broken", data));
                    }
                }
        );

        // Block placement — record the position the player interacted with.
        // On the next tick, we check the offset position for a newly placed block.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(
                (player, world, hand, hitResult) -> {
                    if (world.isClientSide() && player.isLocalPlayer()) {
                        // The block is placed at the offset side of the hit result
                        BlockPos placedAt = hitResult.getBlockPos().relative(hitResult.getDirection());
                        pendingPlacements.add(placedAt);
                    }
                    return net.minecraft.world.InteractionResult.PASS;
                }
        );

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Block event listeners registered");
    }

    /**
     * Called every tick to check pending placements. If a non-air block now
     * exists at a candidate position, emit a block_placed event.
     */
    public void tick() {
        if (pendingPlacements.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            pendingPlacements.clear();
            return;
        }

        BlockPos pos;
        while ((pos = pendingPlacements.poll()) != null) {
            BlockState state = client.level.getBlockState(pos);
            if (!state.isAir()) {
                JsonObject data = new JsonObject();
                data.addProperty("x", pos.getX());
                data.addProperty("y", pos.getY());
                data.addProperty("z", pos.getZ());
                data.addProperty("block_type",
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());

                if (!state.getProperties().isEmpty()) {
                    data.addProperty("block_state",
                            com.emma.bridge.commands.ScanHandler.stateToString(state));
                }

                ws.broadcastEvent(JsonProtocol.event("block_placed", data));
            }
        }
    }
}
