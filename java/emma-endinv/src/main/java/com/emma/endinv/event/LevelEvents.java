package com.emma.endinv.event;

import com.emma.endinv.data.EndlessInventoryData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class LevelEvents {

    private LevelEvents() {
    }

    public static void register() {
        ServerLevelEvents.LOAD.register(LevelEvents::onLoad);
    }

    private static void onLoad(MinecraftServer server, ServerLevel level) {
        EndlessInventoryData.init(level);
        // Ensure player->endinv mapping SavedData is ready
        PlayerEvents.markPlayersForSync(server);
    }
}
