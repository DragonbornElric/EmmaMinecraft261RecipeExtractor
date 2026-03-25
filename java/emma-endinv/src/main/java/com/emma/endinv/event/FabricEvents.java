package com.emma.endinv.event;

public final class FabricEvents {

    private FabricEvents() {
    }

    public static void init() {
        Commands.register();
        LevelEvents.register();
        PlayerEvents.register();
        //LootEvent.register();
        BlockBreakRedirect.register();
    }
}
