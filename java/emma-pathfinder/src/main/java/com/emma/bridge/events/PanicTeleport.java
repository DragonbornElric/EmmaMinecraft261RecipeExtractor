package com.emma.bridge.events;

import com.emma.bridge.BridgeServer;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;

/**
 * Last-resort safety net: when health drops below a threshold,
 * teleport to safe coordinates (forced overworld) and go idle.
 *
 * Runs every tick via EmmaBridgeClient's END_CLIENT_TICK callback,
 * BEFORE EmmaClef chain processing, for zero-latency response.
 *
 * Configurable via WebSocket "panic_teleport" command and emma_bridge.json.
 */
public class PanicTeleport {

    // ── Configurable state ──
    private boolean enabled = false;
    private float healthThreshold = 4.0f;  // 2 hearts (half-hearts)
    private double safeX = 0;
    private double safeY = 0;
    private double safeZ = 0;
    private long cooldownMs = 60_000;  // 60 seconds

    // ── Runtime state ──
    private long lastTriggerTime = 0;
    private boolean triggered = false;  // prevent re-trigger while at safe room
    private BridgeServer bridgeServer;  // stored for forceTrigger (set on first tick)

    /**
     * Check health every tick. If below threshold, teleport + idle.
     * Called from END_CLIENT_TICK, before all other tick handlers.
     */
    public void tick(LocalPlayer player, BridgeServer ws) {
        this.bridgeServer = ws;  // cache for forceTrigger
        if (!enabled) return;

        float health = player.getHealth();

        // Reset trigger flag when health recovers above threshold + buffer
        if (health > healthThreshold + 4.0f) {
            triggered = false;
        }

        // Already triggered and hasn't recovered yet
        if (triggered) return;

        // Don't trigger on death (health=0) — handled by DeathScreen/Respawn
        if (health <= 0) return;

        // Check health threshold
        if (health > healthThreshold) return;

        // Check cooldown
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < cooldownMs) return;

        // ── TRIGGER PANIC TELEPORT ──
        executeTeleport(player, ws, health);
    }

    /**
     * Force-trigger teleport immediately, bypassing health check and cooldown.
     * Used by the GUI "Panic!" button.
     */
    public void forceTrigger(LocalPlayer player) {
        float health = player != null ? player.getHealth() : -1;
        if (player != null && health > 0) {
            executeTeleport(player, bridgeServer, health);
        }
    }

    private void executeTeleport(LocalPlayer player, BridgeServer ws, float health) {
        triggered = true;
        lastTriggerTime = System.currentTimeMillis();

        // 1. Teleport via /execute — forces overworld dimension (works from Nether/End)
        String tpCmd = String.format(
                "execute in minecraft:overworld run tp @s %.0f %.0f %.0f",
                safeX, safeY, safeZ);
        player.connection.sendCommand(tpCmd);

        EmmaBridgeMod.LOGGER.warn(
                "[Emma Bridge] PANIC TELEPORT! health={}, threshold={}, dest=({},{},{})",
                health, healthThreshold, safeX, safeY, safeZ);

        // 2. Broadcast event to Python (GOAP handles task cancellation via the event)
        if (ws != null && ws.hasConnections()) {
            JsonObject data = new JsonObject();
            data.addProperty("health", health);
            data.addProperty("threshold", healthThreshold);
            data.addProperty("safe_x", safeX);
            data.addProperty("safe_y", safeY);
            data.addProperty("safe_z", safeZ);
            ws.broadcastEvent(JsonProtocol.event("panic_teleport", data));
        }
    }

    // ── Configuration ──

    public boolean isEnabled()                      { return enabled; }
    public void setEnabled(boolean enabled)         { this.enabled = enabled; }
    public float getHealthThreshold()               { return healthThreshold; }
    public void setHealthThreshold(float t)         { this.healthThreshold = t; }
    public double getSafeX()                        { return safeX; }
    public double getSafeY()                        { return safeY; }
    public double getSafeZ()                        { return safeZ; }
    public void setSafeCoords(double x, double y, double z) {
        this.safeX = x;
        this.safeY = y;
        this.safeZ = z;
    }
    public long getCooldownMs()                     { return cooldownMs; }
    public void setCooldownMs(long ms)              { this.cooldownMs = ms; }
    public boolean isTriggered()                    { return triggered; }
    public void resetTrigger()                      { triggered = false; }
}
