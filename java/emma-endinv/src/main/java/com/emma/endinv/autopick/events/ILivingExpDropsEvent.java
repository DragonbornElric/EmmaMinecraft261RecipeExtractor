package com.emma.endinv.autopick.events;

import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

public interface ILivingExpDropsEvent extends ICancelable{

    int getDroppedExperience();

    void setDroppedExperience(int droppedExperience);

    @Nullable
    Player getAttackingPlayer();
}
