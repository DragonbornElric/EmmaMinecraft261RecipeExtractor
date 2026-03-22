package com.emma.bridge.goap.reflex;

import com.emma.bridge.goap.GoapReflex;
import com.emma.bridge.goap.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;

/**
 * Auto-respawn reflex: clicks respawn when death screen is detected.
 *
 * Trigger: death screen is showing
 * Action: wait 2 ticks, then click respawn button
 * Suppresses scoring: Yes (can't do anything while dead)
 */
public class AutoRespawnReflex extends GoapReflex {

    private int deathTicks = 0;
    private static final int RESPAWN_DELAY = 2;  // ticks before clicking respawn

    @Override
    public String getName() {
        return "AutoRespawn";
    }

    @Override
    public boolean suppressesScoring() {
        return true;
    }

    @Override
    public boolean shouldFire(WorldState state, Minecraft client) {
        return client.screen instanceof DeathScreen;
    }

    @Override
    public void fire(Minecraft client) {
        deathTicks++;

        if (deathTicks >= RESPAWN_DELAY) {
            // Request respawn
            if (client.player != null) {
                client.player.respawn();
            }
            // Close the death screen
            client.setScreen(null);
        }
    }

    @Override
    public void release(Minecraft client) {
        deathTicks = 0;
    }
}
