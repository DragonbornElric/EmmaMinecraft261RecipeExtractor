package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * {@code @respawn} — Respawn after death.
 *
 * Calls {@code player.requestRespawn()} and closes the DeathScreen,
 * exactly like pressing the respawn button. Intended for use when
 * AutoRespawn is disabled so Emma (or the CLI agent) controls when
 * to respawn.
 */
public class RespawnCommand extends Command {

    public RespawnCommand() {
        super("respawn", "Respawn after death");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null && player.isDead()) {
            player.requestRespawn();
            MinecraftClient.getInstance().setScreen(null);
        }
        finish();
    }
}
