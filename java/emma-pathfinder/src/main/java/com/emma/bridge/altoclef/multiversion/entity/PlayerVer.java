package adris.altoclef.multiversion.entity;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

public class PlayerVer {


    public static void sendChatMessage(ClientPlayerEntity player,String content) {
        player.networkHandler.sendChatMessage(content);
    }

    public static void sendChatCommand(ClientPlayerEntity player,String content) {
        player.networkHandler.sendChatCommand(content);
    }

    @Pattern
    private static ItemStack getCursorStack(PlayerEntity player) {
        return player.currentScreenHandler.getCursorStack();
    }

    @Pattern
    private static Inventory getInventory(PlayerEntity player) {
        return player.getInventory();
    }

    public static boolean inPowderedSnow(PlayerEntity player) {
        return player.inPowderSnow;
    }



}
