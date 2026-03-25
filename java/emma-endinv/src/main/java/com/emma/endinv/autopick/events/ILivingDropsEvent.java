package com.emma.endinv.autopick.events;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Collection;

public interface ILivingDropsEvent extends ICancelable{

    DamageSource getSource();

    Collection<ItemEntity> getDrops();
}
