package com.emma.endinv.network.payloads;

import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

public interface ModPacketContext {

    @Nullable
    Player player();
}
