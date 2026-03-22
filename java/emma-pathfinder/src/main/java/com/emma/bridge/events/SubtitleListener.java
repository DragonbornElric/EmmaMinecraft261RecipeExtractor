package com.emma.bridge.events;

import com.emma.bridge.BridgeServer;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;

/**
 * Captures Minecraft's accessibility subtitle text for nearby sounds
 * and broadcasts them as WebSocket events. Uses the same system deaf
 * players use to read sounds on screen.
 *
 * Throttles to max 1 event per sound subtitle per 10 seconds.
 */
public class SubtitleListener implements SoundEventListener {

    private static final long THROTTLE_MS = 10_000;

    private final BridgeServer ws;
    private boolean registered = false;

    /** subtitle text → last broadcast timestamp */
    private final Map<String, Long> lastBroadcast = new HashMap<>();

    public SubtitleListener(BridgeServer ws) {
        this.ws = ws;
    }

    /**
     * Called every tick by EventReporter. Registers the listener on the
     * SoundManager once the client is ready, and prunes stale throttle entries.
     */
    public void tick() {
        if (!registered) {
            Minecraft client = Minecraft.getInstance();
            if (client.getSoundManager() != null) {
                client.getSoundManager().addListener(this);
                registered = true;
                EmmaBridgeMod.LOGGER.info("[Emma Bridge] SubtitleListener registered on SoundManager");
            }
        }

        // Prune old throttle entries every ~30s worth of ticks
        long now = System.currentTimeMillis();
        lastBroadcast.entrySet().removeIf(e -> now - e.getValue() > THROTTLE_MS * 3);
    }

    /**
     * Called by SoundManager when any sound is played. We filter to
     * mob/environment sounds and broadcast the subtitle text.
     */
    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents soundSet, float range) {
        if (!ws.hasConnections()) return;

        // Only care about mob/environment sounds
        SoundSource category = sound.getSource();
        if (category != SoundSource.HOSTILE
                && category != SoundSource.NEUTRAL
                && category != SoundSource.AMBIENT
                && category != SoundSource.WEATHER
                && category != SoundSource.BLOCKS) {
            return;
        }

        // Get subtitle text — skip if none
        Component subtitle = soundSet.getSubtitle();
        if (subtitle == null) return;
        String subtitleText = subtitle.getString();
        if (subtitleText == null || subtitleText.isEmpty()) return;

        // Throttle: max 1 event per subtitle text per THROTTLE_MS
        long now = System.currentTimeMillis();
        Long lastTime = lastBroadcast.get(subtitleText);
        if (lastTime != null && now - lastTime < THROTTLE_MS) return;
        lastBroadcast.put(subtitleText, now);

        // Calculate distance and direction from player
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        double dx = sound.getX() - player.getX();
        double dy = sound.getY() - player.getY();
        double dz = sound.getZ() - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        String direction = getDirection(dx, dz);

        // Broadcast event
        JsonObject data = new JsonObject();
        data.addProperty("text", subtitleText);
        data.addProperty("category", category.getName());
        data.addProperty("distance", Math.round(distance * 10.0) / 10.0);
        data.addProperty("direction", direction);

        ws.broadcastEvent(JsonProtocol.event("sound_subtitle", data));
    }

    /**
     * Unregisters from SoundManager (call on shutdown).
     */
    public void unregister() {
        if (registered) {
            Minecraft client = Minecraft.getInstance();
            if (client.getSoundManager() != null) {
                client.getSoundManager().removeListener(this);
            }
            registered = false;
        }
    }

    private static String getDirection(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) angle += 360;

        if (angle < 22.5 || angle >= 337.5) return "south";
        if (angle < 67.5) return "southwest";
        if (angle < 112.5) return "west";
        if (angle < 157.5) return "northwest";
        if (angle < 202.5) return "north";
        if (angle < 247.5) return "northeast";
        if (angle < 292.5) return "east";
        return "southeast";
    }
}
