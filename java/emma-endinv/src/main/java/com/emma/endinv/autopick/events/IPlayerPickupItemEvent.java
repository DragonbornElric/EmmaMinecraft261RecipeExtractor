package com.emma.endinv.autopick.events;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

public interface IPlayerPickupItemEvent {

    ItemEntity getItem();

    Player getPlayer();
}
