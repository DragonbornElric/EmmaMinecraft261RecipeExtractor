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

package emmatone.api;

import emmatone.api.cache.IWorldScanner;
import emmatone.api.command.ICommand;
import emmatone.api.command.ICommandSystem;
import emmatone.api.schematic.ISchematicSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;
import java.util.Objects;

/**
 * Provides the present {@link IEmmatone} instances, as well as non-emmatone instance related APIs.
 *
 * @author leijurv
 */
public interface IEmmatoneProvider {

    /**
     * Returns the primary {@link IEmmatone} instance. This instance is persistent, and
     * is represented by the local player that is created by the game itself, not a "bot"
     * player through Emmatone.
     *
     * @return The primary {@link IEmmatone} instance.
     */
    IEmmatone getPrimaryEmmatone();

    /**
     * Returns all of the active {@link IEmmatone} instances. This includes the local one
     * returned by {@link #getPrimaryEmmatone()}.
     *
     * @return All active {@link IEmmatone} instances.
     * @see #getEmmatoneForPlayer(LocalPlayer)
     */
    List<IEmmatone> getAllEmmatones();

    /**
     * Provides the {@link IEmmatone} instance for a given {@link LocalPlayer}.
     *
     * @param player The player
     * @return The {@link IEmmatone} instance.
     */
    default IEmmatone getEmmatoneForPlayer(LocalPlayer player) {
        for (IEmmatone emmatone : this.getAllEmmatones()) {
            if (Objects.equals(player, emmatone.getPlayerContext().player())) {
                return emmatone;
            }
        }
        return null;
    }

    /**
     * Provides the {@link IEmmatone} instance for a given {@link Minecraft}.
     *
     * @param minecraft The minecraft
     * @return The {@link IEmmatone} instance.
     */
    default IEmmatone getEmmatoneForMinecraft(Minecraft minecraft) {
        for (IEmmatone emmatone : this.getAllEmmatones()) {
            if (Objects.equals(minecraft, emmatone.getPlayerContext().minecraft())) {
                return emmatone;
            }
        }
        return null;
    }

    /**
     * Provides the {@link IEmmatone} instance for the player with the specified connection.
     *
     * @param connection The connection
     * @return The {@link IEmmatone} instance.
     */
    default IEmmatone getEmmatoneForConnection(ClientPacketListener connection) {
        for (IEmmatone emmatone : this.getAllEmmatones()) {
            final LocalPlayer player = emmatone.getPlayerContext().player();
            if (player != null && player.connection == connection) {
                return emmatone;
            }
        }
        return null;
    }

    /**
     * Creates and registers a new {@link IEmmatone} instance using the specified {@link Minecraft}. The existing
     * instance is returned if already registered.
     *
     * @param minecraft The minecraft
     * @return The {@link IEmmatone} instance
     */
    IEmmatone createEmmatone(Minecraft minecraft);

    /**
     * Destroys and removes the specified {@link IEmmatone} instance. If the specified instance is the
     * {@link #getPrimaryEmmatone() primary emmatone}, this operation has no effect and will return {@code false}.
     *
     * @param emmatone The emmatone instance to remove
     * @return Whether the emmatone instance was removed
     */
    boolean destroyEmmatone(IEmmatone emmatone);

    /**
     * Returns the {@link IWorldScanner} instance. This is not a type returned by
     * {@link IEmmatone} implementation, because it is not linked with {@link IEmmatone}.
     *
     * @return The {@link IWorldScanner} instance.
     */
    IWorldScanner getWorldScanner();

    /**
     * Returns the {@link ICommandSystem} instance. This is not bound to a specific {@link IEmmatone}
     * instance because {@link ICommandSystem} itself controls global behavior for {@link ICommand}s.
     *
     * @return The {@link ICommandSystem} instance.
     */
    ICommandSystem getCommandSystem();

    /**
     * @return The {@link ISchematicSystem} instance.
     */
    ISchematicSystem getSchematicSystem();
}
