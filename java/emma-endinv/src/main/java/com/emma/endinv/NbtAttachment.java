package com.emma.endinv;

import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

public interface NbtAttachment<T> {
    /**
     * Get the attachment for the player.
     * If the player's such data is never initialized, it should return null.
     * @param player the player
     * @return the attachment or null if never initialized
     */
    @Nullable
    T getWith(Player player);

    void setTo(Player player, T t);

    /**
     * Get the attachment for the player or compute a new one if absent.
     * @param player the player
     * @return the attachment
     */
    T computeIfAbsent(Player player);
}
