package com.emma.logger.events;

import com.emma.logger.GameplayLoggerMod;
import com.emma.logger.PlayerLogger;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Logs block placement and breaking events via Fabric API callbacks.
 * Server-side: fires for all players automatically.
 */
public class BlockEventLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("EmmaLogger");

    /** Pending placement checks — deferred one tick like the bridge mod. */
    private record PendingPlacement(ServerPlayer player, BlockPos pos) {}
    private final Queue<PendingPlacement> pendingPlacements = new ConcurrentLinkedQueue<>();

    public void register() {
        // Block broken — server-side event
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide()) return;
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            PlayerLogger logger = GameplayLoggerMod.getLogger(serverPlayer);
            if (logger == null) return;

            long tick = world.getServer().getTickCount();
            String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

            // Tool used
            ItemStack heldItem = serverPlayer.getMainHandItem();
            String tool = heldItem.isEmpty() ? null :
                    BuiltInRegistries.ITEM.getKey(heldItem.getItem()).toString();

            logger.onBlockBroken(tick, block, pos.getX(), pos.getY(), pos.getZ(), tool);
        });

        // Block placement — record candidate, verify next tick
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

            PlayerLogger logger = GameplayLoggerMod.getLogger(serverPlayer);
            if (logger == null) return InteractionResult.PASS;

            // The block is placed at the offset side of the hit result
            BlockPos placedAt = hitResult.getBlockPos().relative(hitResult.getDirection());
            pendingPlacements.add(new PendingPlacement(serverPlayer, placedAt));

            return InteractionResult.PASS;
        });

        LOGGER.info("[EmmaLogger] Block event listeners registered");
    }

    /**
     * Called every server tick to verify pending placements.
     */
    public void tick() {
        PendingPlacement pending;
        while ((pending = pendingPlacements.poll()) != null) {
            ServerPlayer player = pending.player();
            if (player.hasDisconnected()) continue;

            ServerLevel world = (ServerLevel) player.level();
            BlockPos pos = pending.pos();
            BlockState state = world.getBlockState(pos);

            if (!state.isAir()) {
                PlayerLogger logger = GameplayLoggerMod.getLogger(player);
                if (logger == null) continue;

                long tick = world.getServer().getTickCount();
                String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                logger.onBlockPlaced(tick, block, pos.getX(), pos.getY(), pos.getZ(), null);
            }
        }
    }
}
