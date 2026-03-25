package com.emma.endinv.commands;

import com.emma.endinv.EndlessInventory;
import com.emma.endinv.ModRegistries;
import com.emma.endinv.ServerLevelEndInv;
import com.emma.endinv.data.EndlessInventoryData;
import com.emma.endinv.menu.EndlessInventoryMenu;
import com.emma.endinv.util.Accessibility;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

public class EndInvCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("endinv").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("backup")
                        .executes(context -> {
                            var result = EndlessInventoryData.backup(context.getSource().getLevel());
                            if(result.success()){
                                context.getSource().sendSuccess(() -> Component.literal("Backed up at "+result.message()), true);
                                return 1;
                            } else {
                                context.getSource().sendFailure(Component.literal("Cannot backup as "+result.message()));
                                return -1;
                            }
                        })
                )
                .then(Commands.literal("ofIndex")
                        .executes(context -> getCurrentIndex(context.getSource()))
                        .then(Commands.argument("index", IntegerArgumentType.integer())
                                .executes(context -> byIndexGet(context.getSource(), IntegerArgumentType.getInteger(context,"index")))
                                .then(Commands.literal("open")
                                        .executes(context -> byIndexOpen(context.getSource(),IntegerArgumentType.getInteger(context,"index")))
                                )
                                .then(Commands.literal("setDefault")
                                        .executes(context -> byIndexSetDefault(context.getSource(),IntegerArgumentType.getInteger(context,"index")))
                                )
                                .then(Commands.literal("setOwner")
                                        .executes(context -> byIndexSetOwner(context.getSource(),IntegerArgumentType.getInteger(context,"index")))
                                )
                                .then(Commands.literal("addWhitelist")
                                        .executes(context -> byIndexAddWhitelist(context.getSource(),IntegerArgumentType.getInteger(context,"index")))
                                )
                                .then(Commands.literal("removeWhitelist")
                                        .executes(context -> byIndexRemoveWhitelist(context.getSource(),IntegerArgumentType.getInteger(context,"index")))
                                )
                                .then(Commands.literal("setAccessibility")
                                        .then(Commands.literal("public")
                                                .executes(context -> byIndexSetAccessibility(context.getSource(),IntegerArgumentType.getInteger(context,"index"), Accessibility.PUBLIC)))
                                        .then(Commands.literal("restricted")
                                                .executes(context -> byIndexSetAccessibility(context.getSource(),IntegerArgumentType.getInteger(context,"index"), Accessibility.RESTRICTED)))
                                        .then(Commands.literal("private")
                                                .executes(context -> byIndexSetAccessibility(context.getSource(),IntegerArgumentType.getInteger(context,"index"), Accessibility.PRIVATE)))
                                )
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("forceRemove", BoolArgumentType.bool())
                                                .executes(context -> byIndexRemove(context.getSource(),IntegerArgumentType.getInteger(context,"index"), BoolArgumentType.getBool(context,"forceRemove"))))
                                )
                        )
                )
                .then(Commands.literal("new")
                        .executes(context -> createNew(context.getSource(),Accessibility.PUBLIC))
                        .then(Commands.literal("public")
                                .executes(context -> createNew(context.getSource(),Accessibility.PUBLIC))
                        ).then(Commands.literal("restricted")
                                .executes(context -> createNew(context.getSource(),Accessibility.RESTRICTED))
                        ).then(Commands.literal("private")
                                .executes(context -> createNew(context.getSource(),Accessibility.PRIVATE))
                        )
                )
        );
    }

    private static int byIndexRemove(CommandSourceStack source, int index, boolean forced) {
        EndlessInventory endlessInventory = ServerLevelEndInv.levelEndInvData.fromIndex(index);
        if(endlessInventory==null){
            source.sendFailure(Component.literal("Cannot get EndInv by index "+index));
            return -1;
        }
        EndlessInventoryData.BackupResult result = EndlessInventoryData.backup(source.getLevel());
        if(!result.success() && !forced){
            source.sendFailure(Component.literal("Cannot backup as "+ result.message()));
            return -1;
        }
        ServerLevelEndInv.levelEndInvData.byIndexRemove(index);
        source.sendSuccess(() -> Component.literal("Removed " + endlessInventory.getUuid()), true);
        return index;
    }

    private static int byIndexAddWhitelist(CommandSourceStack source, int index) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            EndlessInventory endlessInventory = ServerLevelEndInv.levelEndInvData.fromIndex(index);
            if(endlessInventory==null){
                source.sendFailure(Component.literal("Cannot get EndInv by index "+index));
                return -1;
            }
            if(endlessInventory.isOwner(player) || Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
                endlessInventory.white_list.add(player.getUUID());
                source.sendSuccess(() -> Component.literal("Add " + player.getName().getString() + " to " + endlessInventory.getUuid() + "'s whitelist."), true);
                return index;
            } else {
                source.sendFailure(Component.translatable("endinv.callback.not_owner"));
                return -1;
            }
        }catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("A player must execute this command."));
            return -1;
        }
    }

    private static int byIndexRemoveWhitelist(CommandSourceStack source, int index) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            EndlessInventory endlessInventory = ServerLevelEndInv.levelEndInvData.fromIndex(index);
            if(endlessInventory==null){
                source.sendFailure(Component.literal("Cannot get EndInv by index "+index));
                return -1;
            }
            if(endlessInventory.isOwner(player) || Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
                if(endlessInventory.white_list.remove(player.getUUID())) {
                    source.sendSuccess(() -> Component.literal("Remove " + player.getName().getString() + " from " + endlessInventory.getUuid() + "'s whitelist."), true);
                } else {
                    source.sendFailure(Component.literal(player.getName().getString() + " is not in " + endlessInventory.getUuid() + "'s whitelist."));
                    return -1;
                }
            } else {
                source.sendFailure(Component.translatable("endinv.callback.not_owner"));
                return -1;
            }
            return index;
        }catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("A player must execute this command."));
            return -1;
        }
    }

    private static int byIndexSetAccessibility(CommandSourceStack source, int index, Accessibility accessibility) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            EndlessInventory endlessInventory = ServerLevelEndInv.levelEndInvData.fromIndex(index);

            if(endlessInventory==null){
                source.sendFailure(Component.literal("Cannot get EndInv by index "+index));
                return -1;
            }
            if(endlessInventory.isOwner(player) || Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
                endlessInventory.setAccessibility(accessibility);
                source.sendSuccess(()->Component.literal("Set "+endlessInventory.getUuid()+"'s accessibility to "+accessibility),true);
                return 1;
            } else {
                source.sendFailure(Component.translatable("endinv.callback.not_owner"));
                return -1;
            }
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("A player must execute this command."));
            return -1;
        }
    }

    private static int createNew(CommandSourceStack source, Accessibility accessibility) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            EndlessInventory endInv;
            switch (accessibility) {
                case PUBLIC -> {
                    endInv = ServerLevelEndInv.createPublicEndInv();
                    endInv.setAccessibility(Accessibility.PUBLIC);
                    source.sendSuccess(() -> Component.literal("Created a new public endInv with uuid: "+endInv.getUuid()),true);
                }
                case RESTRICTED -> {
                    endInv = ServerLevelEndInv.createPublicEndInv();
                    endInv.setAccessibility(Accessibility.RESTRICTED);
                    source.sendSuccess(()->Component.literal("Created a new white_list endInv with uuid: "+endInv.getUuid()),true);
                    endInv.white_list.add(player.getUUID());
                    source.sendSuccess(()->Component.literal("Add current player to white list"),true);
                }
                case PRIVATE -> {
                    endInv = ServerLevelEndInv.createPublicEndInv();
                    endInv.setAccessibility(Accessibility.PRIVATE);
                    endInv.setOwner(player.getUUID());
                    source.sendSuccess(() -> Component.literal("Created a new private endInv with uuid: "+endInv.getUuid()
                            +", with owner : "+player.getName().getString()), true);
                }
            }
            return 1;
        }catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("A player must execute this command."));
            return -1;
        }
    }

    private static int byIndexSetDefault(CommandSourceStack source, int index) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            EndlessInventory endlessInventory = ServerLevelEndInv.levelEndInvData.fromIndex(index);

            if(endlessInventory==null){
                source.sendFailure(Component.literal("Cannot get EndInv by index "+index));
                return -1;
            }
            ModRegistries.NbtAttachments.getEndInvUUID().setTo(player,endlessInventory.getUuid());
            source.sendSuccess(()->Component.literal("Set player's default endInv with uuid: "+endlessInventory.getUuid()),true);
            return index;
        }catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("A player must execute this command."));
            return -1;
        }
    }

    private static int byIndexOpen(CommandSourceStack source, int index) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            EndlessInventory endlessInventory = ServerLevelEndInv.levelEndInvData.fromIndex(index);
            if(endlessInventory==null){
                source.sendFailure(Component.literal("Cannot get EndInv by index "+index));
                return -1;
            }
            if(endlessInventory.accessible(player)){
                ServerLevelEndInv.TEMP_ENDINV_REG.put(player, endlessInventory);
                player.openMenu(new SimpleMenuProvider(EndlessInventoryMenu::createWithTemp, Component.empty()));
            } else if(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)){
                ServerLevelEndInv.TEMP_ENDINV_REG.put(player, endlessInventory);
                player.openMenu(new SimpleMenuProvider(EndlessInventoryMenu::createWithTemp, Component.empty()));
                source.sendSuccess(()->Component.literal("Opened an unaccessible endInv for op"),true);
            } else {
                source.sendFailure(Component.translatable("endinv.callback.no_access"));
                return -1;
            }
            return index;
        }catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("A player must execute this command."));
            return -1;
        }
    }

    private static int byIndexGet(CommandSourceStack source, int index) {
        try{
            EndlessInventory endlessInventory = ServerLevelEndInv.levelEndInvData.fromIndex(index);
            if(endlessInventory==null){
                source.sendFailure(Component.literal("Cannot get EndInv by index "+index));
                return -1;
            }
            source.sendSuccess(()->Component.literal("Found endInv with uuid: "+endlessInventory.getUuid()),true);
            return index;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int getCurrentIndex(CommandSourceStack source) {
        try {
            ServerPlayer serverPlayer = source.getPlayerOrException();
            if (!ServerLevelEndInv.hasEndInvUuid(serverPlayer)) {
                source.sendFailure(Component.literal("This player has not EndInv."));
                return -1;
            }
            var optional = ServerLevelEndInv.getEndInvForPlayer(serverPlayer);
            if(optional.isPresent()){
                EndlessInventory endlessInventory = optional.get();
                int index = ServerLevelEndInv.levelEndInvData.getIndex(endlessInventory);
                source.sendSuccess(() -> Component.literal("EndInv index: " + index), true);
                return index;
            } else {
                source.sendFailure(Component.literal("Cannot get EndInv for player."));
                return -1;
            }
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("A player must execute this command."));
            return -1;
        }
    }

    private static int byIndexSetOwner(CommandSourceStack source, int index) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            EndlessInventory endInv = ServerLevelEndInv.levelEndInvData.fromIndex(index);
            if (endInv == null) {
                source.sendFailure(Component.literal("Cannot get EndInv by index " + index));
                return -1;
            }
            if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
                source.sendFailure(Component.translatable("commands.generic.permission"));
                return -1;
            }
            endInv.setOwner(player.getUUID());
            source.sendSuccess(() -> Component.literal("Set owner for endInv " + endInv.getUuid() + " to " + player.getName().getString()), true);
            return 1;
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("A player must execute this command."));
            return -1;
        }
    }
}
