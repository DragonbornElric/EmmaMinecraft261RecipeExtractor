package com.emma.endinv.network.payloads.toServer;

import com.emma.endinv.AbstractModInitializer;
import com.emma.endinv.EndlessInventory;
import com.emma.endinv.ServerLevelEndInv;
import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.ModPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Used when client item modified in ItemDisplay with {@link net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.ItemPickerMenu}
 * @param isAdding true for add item and false for take item.
 */
public record CreativeItemModPayload(ItemStack stack, boolean isAdding) implements ModPacketPayload {

    public static void encode(CreativeItemModPayload payload, RegistryFriendlyByteBuf o){
        ItemStack.STREAM_CODEC.encode(o, payload.stack);
        o.writeBoolean(payload.isAdding);
    }

    public static CreativeItemModPayload decode(RegistryFriendlyByteBuf o){
        return new CreativeItemModPayload(ItemStack.STREAM_CODEC.decode(o),o.readBoolean());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, CreativeItemModPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> encode(value, buf), CreativeItemModPayload::decode);

    public static final CustomPacketPayload.Type<CreativeItemModPayload> TYPE =
            new CustomPacketPayload.Type<>(AbstractModInitializer.withModLocation("item_modify"));


    @Override
    public String id() {
        return "item_modify";
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @Override
    public void handle(ModPacketContext iPayloadContext) {
        ServerPlayer player = (ServerPlayer) iPayloadContext.player();
        if(player==null)return;
        if(player.containerMenu.getCarried().isEmpty() && player.isCreative()){
            Optional<EndlessInventory> optional = ServerLevelEndInv.getEndInvForPlayer(player);
            if(optional.isPresent()) {
                EndlessInventory ei = optional.get();
                if(isAdding){
                    ei.addItem(stack);
                }else {
                    ItemStack taken = ei.takeItem(stack);
                    if(!taken.isEmpty()) player.containerMenu.setCarried(taken);
                }
            }

        }
    }
}
