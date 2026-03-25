package com.emma.endinv.commands;

import com.emma.endinv.options.ServerConfigs;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Register Endless Inventory config commands
 *
 * @author Kay Zhang
 * @since 2025-10-17
 * @version 1.1.0
 */
public class ConfigCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("endinv").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                        Commands.literal("config")
                                .then(
                                        Commands.literal("autoPick")
                                                .then(
                                                        Commands.argument("enable", BoolArgumentType.bool())
                                                                .executes(
                                                                        context-> cmdSetAutoPick(context.getSource(),BoolArgumentType.getBool(context, "enable"))

                                                                )
                                                )
                                )
                )
        );
    }

    private static int cmdSetAutoPick(CommandSourceStack source,boolean enable){
        try {
            ServerConfigs.ENABLE_AUTOPICK.set(enable);
            source.sendSuccess(()-> Component.literal(enable ? "Enabled":"Disabled"+ " autoPick utility"), true);
            return 1;
        } catch (Exception e) {
            return 0;
        }

    }
}
