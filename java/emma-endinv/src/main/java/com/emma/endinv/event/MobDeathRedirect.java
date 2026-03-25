package com.emma.endinv.event;

import net.minecraft.server.level.ServerPlayer;

public final class MobDeathRedirect {
    private static final ThreadLocal<ServerPlayer> CURRENT_KILLER = new ThreadLocal<>();

    private MobDeathRedirect() {}

    public static void set(ServerPlayer killer) {
        CURRENT_KILLER.set(killer);
    }

    public static ServerPlayer get() {
        return CURRENT_KILLER.get();
    }

    public static void clear() {
        CURRENT_KILLER.remove();
    }
}

