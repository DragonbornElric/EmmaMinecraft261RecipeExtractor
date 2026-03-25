package com.emma.endinv.autopick.events;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

public interface IBlockBreakEvent extends ICancelable{

    LevelAccessor getLevel();

    BlockPos getPos();

    BlockState getState();

    Player getPlayer();

    int getExpToDrop();

    void setExpToDrop(int exp);
}
