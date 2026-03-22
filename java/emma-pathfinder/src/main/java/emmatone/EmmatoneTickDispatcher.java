/*
 * This file is part of Emmatone.
 *
 * Emmatone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Emmatone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Emmatone.  If not, see <https://www.gnu.org/licenses/>.
 */

package emmatone;

import emmatone.api.EmmatoneAPI;
import emmatone.api.IEmmatone;
import emmatone.api.event.events.ChunkEvent;
import emmatone.api.event.events.PlayerUpdateEvent;
import emmatone.api.event.events.TickEvent;
import emmatone.api.event.events.WorldEvent;
import emmatone.api.event.events.type.EventState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.function.BiFunction;

/**
 * Dispatches Emmatone tick and world events via Fabric API callbacks,
 * replacing the old MixinMinecraft injection points.
 */
public final class EmmatoneTickDispatcher {

    private static BiFunction<EventState, TickEvent.Type, TickEvent> tickProvider;
    private static ClientLevel lastLevel;

    private EmmatoneTickDispatcher() {}

    public static void register() {
        // Trigger EmmatoneAPI static initialization (creates EmmatoneProvider + primary Emmatone)
        EmmatoneAPI.getProvider().getPrimaryEmmatone();

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            tickProvider = TickEvent.createNextProvider();

            for (IEmmatone emmatone : EmmatoneAPI.getProvider().getAllEmmatones()) {
                TickEvent.Type type = emmatone.getPlayerContext().player() != null
                        && emmatone.getPlayerContext().world() != null
                        ? TickEvent.Type.IN
                        : TickEvent.Type.OUT;
                emmatone.getGameEventHandler().onTick(tickProvider.apply(EventState.PRE, type));
            }

            // Fire PlayerUpdateEvent(PRE) — LookBehavior applies target rotation
            // (replaces MixinClientPlayerEntity injection after AbstractClientPlayer.tick())
            for (IEmmatone emmatone : EmmatoneAPI.getProvider().getAllEmmatones()) {
                if (emmatone.getPlayerContext().player() != null) {
                    emmatone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Fire PlayerUpdateEvent(POST) — LookBehavior restores/smooths rotation
            for (IEmmatone emmatone : EmmatoneAPI.getProvider().getAllEmmatones()) {
                if (emmatone.getPlayerContext().player() != null) {
                    emmatone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
                }
            }

            if (tickProvider == null) {
                return;
            }

            for (IEmmatone emmatone : EmmatoneAPI.getProvider().getAllEmmatones()) {
                TickEvent.Type type = emmatone.getPlayerContext().player() != null
                        && emmatone.getPlayerContext().world() != null
                        ? TickEvent.Type.IN
                        : TickEvent.Type.OUT;
                emmatone.getGameEventHandler().onPostTick(tickProvider.apply(EventState.POST, type));
            }

            tickProvider = null;

            // Tick-based world change detection (replaces MixinMinecraft.setLevel hooks)
            ClientLevel currentLevel = Minecraft.getInstance().level;
            if (currentLevel != lastLevel) {
                EmmatoneAPI.getProvider().getPrimaryEmmatone().getGameEventHandler().onWorldEvent(
                        new WorldEvent(currentLevel, EventState.POST)
                );
                lastLevel = currentLevel;
            }
        });

        // Chunk load/unload events (replaces MixinClientPlayNetHandler chunk injections)
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            for (IEmmatone emmatone : EmmatoneAPI.getProvider().getAllEmmatones()) {
                if (emmatone.getPlayerContext().player() != null) {
                    emmatone.getGameEventHandler().onChunkEvent(
                            new ChunkEvent(EventState.POST, ChunkEvent.Type.POPULATE_FULL,
                                    chunk.getPos().x(), chunk.getPos().z())
                    );
                }
            }
        });

        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            for (IEmmatone emmatone : EmmatoneAPI.getProvider().getAllEmmatones()) {
                if (emmatone.getPlayerContext().player() != null) {
                    // Pack chunk data while we still have the reference
                    // (Fabric passes the chunk object, so data is accessible even after cache removal)
                    emmatone.getWorldProvider().ifWorldLoaded(worldData ->
                            worldData.getCachedWorld().queueForPacking(chunk)
                    );
                    emmatone.getGameEventHandler().onChunkEvent(
                            new ChunkEvent(EventState.POST, ChunkEvent.Type.UNLOAD,
                                    chunk.getPos().x(), chunk.getPos().z())
                    );
                }
            }
        });
    }
}
