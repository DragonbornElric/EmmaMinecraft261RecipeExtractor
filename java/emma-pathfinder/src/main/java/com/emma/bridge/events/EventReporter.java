package com.emma.bridge.events;

import com.emma.bridge.BridgeConfig;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.BridgeServer;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Central event dispatcher that coordinates all event reporters.
 * Called every client tick to check for state changes and emit events.
 *
 * Each sub-reporter has its own throttle/debounce logic to prevent flooding.
 */
public class EventReporter {

    private final BridgeServer ws;
    private final PositionTracker positionTracker;
    private final HealthTracker healthTracker;
    private final InventoryTracker inventoryTracker;
    private final EntityScanner entityScanner;
    private final SubtitleListener subtitleListener;
    private final RotationTracker rotationTracker;

    public EventReporter(BridgeServer ws) {
        this.ws = ws;
        this.positionTracker = new PositionTracker();
        this.healthTracker = new HealthTracker();
        this.inventoryTracker = new InventoryTracker();
        this.entityScanner = new EntityScanner();
        this.subtitleListener = new SubtitleListener(ws);
        this.rotationTracker = new RotationTracker();
    }

    /**
     * Called every client tick. Delegates to each sub-reporter.
     * Only emits events if a WebSocket client is connected.
     */
    public void tick() {
        // SubtitleListener registers itself on SoundManager on first tick
        subtitleListener.tick();

        if (!ws.hasConnections()) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;

        positionTracker.tick(player, ws);
        healthTracker.tick(player, ws);
        inventoryTracker.tick(player, ws);
        entityScanner.tick(player, client.level, ws);

        // Rotation tracking — records snaps to CombatLog (no WS broadcast)
        rotationTracker.tick(player);

        // Player statistics (Mojang Stats API) — requests every 5s, broadcasts on change
        StatsTracker.getInstance().tick(ws);
    }

    /**
     * Get the position tracker for external access (e.g., to force an update).
     */
    public PositionTracker getPositionTracker() {
        return positionTracker;
    }
}
