package com.emma.endinv.event;

import com.emma.endinv.commands.ConfigCommand;
import com.emma.endinv.commands.EndInvCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public final class Commands {

    private Commands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            EndInvCommand.register(dispatcher);
            ConfigCommand.register(dispatcher);
        });
    }
}
