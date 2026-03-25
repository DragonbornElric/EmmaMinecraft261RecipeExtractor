package com.emma.endinv.event;

import com.emma.endinv.autopick.AutoPickHelper;
import com.emma.endinv.autopick.events.IBlockBreakEvent;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

public final class LootEvent {

    private LootEvent() {
    }

    public static void register() {

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            boolean[] cancelled = {false};
            AutoPickHelper.onBlockBreak(
                    new IBlockBreakEvent() {
                        @Override
                        public LevelAccessor getLevel() {
                            return world;
                        }

                        @Override
                        public BlockPos getPos() {
                            return pos;
                        }

                        @Override
                        public BlockState getState() {
                            return state;
                        }

                        @Override
                        public Player getPlayer() {
                            return player;
                        }

                        @Override
                        public int getExpToDrop() {
                            return 0;
                        }

                        @Override
                        public void setExpToDrop(int exp) {

                        }

                        @Override
                        public void setCanceled(boolean canceled) {
                            cancelled[0] = canceled;
                        }
                    }
            );
            return cancelled[0];
        });
    }

    // Drop-handling helpers for server loot were removed due to 1.21.4 API drift
    // and because Fabric currently wires autopick via PlayerBlockBreakEvents.BEFORE.
}
