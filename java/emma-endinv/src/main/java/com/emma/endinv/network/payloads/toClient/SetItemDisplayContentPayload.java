package com.emma.endinv.network.payloads.toClient;

import com.emma.endinv.AbstractModInitializer;
import com.emma.endinv.client.CachedSrcInv;
import com.emma.endinv.client.gui.page.ItemDisplay;
import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.ModPacketPayload;
import com.emma.endinv.util.ItemKey;
import com.emma.endinv.util.ItemState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**Packet that contains required {@link ItemDisplay}'s content view
 *  when {@code TransferMode==PART}
 * @param stacks
 */
public record SetItemDisplayContentPayload(List<ItemStack> stacks) implements ModPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, SetItemDisplayContentPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> encodeRegistry(value, buf), SetItemDisplayContentPayload::decodeRegistry);

    public static final CustomPacketPayload.Type<SetItemDisplayContentPayload> TYPE =
            new CustomPacketPayload.Type<>(AbstractModInitializer.withModLocation("itemdisplay_content"));

    public static void encode(SetItemDisplayContentPayload payload, FriendlyByteBuf o){
        o.writeCollection(payload.stacks,(buf, stack) -> ItemStack.OPTIONAL_STREAM_CODEC.encode((net.minecraft.network.RegistryFriendlyByteBuf) buf, stack));
    }

    public static SetItemDisplayContentPayload decode(FriendlyByteBuf o){
        return new SetItemDisplayContentPayload(o.readList(buf -> ItemStack.OPTIONAL_STREAM_CODEC.decode((net.minecraft.network.RegistryFriendlyByteBuf) buf)));
    }

    // Registry-friendly versions used by the typed StreamCodec to avoid any ambiguity
    private static void encodeRegistry(SetItemDisplayContentPayload payload, RegistryFriendlyByteBuf o){
        o.writeVarInt(payload.stacks.size());
        for (ItemStack stack : payload.stacks) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(o, stack);
        }
    }

    private static SetItemDisplayContentPayload decodeRegistry(RegistryFriendlyByteBuf o){
        int n = o.readVarInt();
        java.util.ArrayList<ItemStack> list = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(o));
        }
        return new SetItemDisplayContentPayload(list);
    }

    @Override
    public String id() {
        return "itemdisplay_content";
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ModPacketContext context) {
        var stackStream = stacks.stream();
        Map<ItemKey, ItemState> partlyMap = new HashMap<>();
        stacks.forEach(stack -> partlyMap.put(ItemKey.asKey(stack), new ItemState(stack.getCount(), -1)));
        CachedSrcInv.INSTANCE.getItemMap().putAll(partlyMap);
        ModPacketPayload.getClientPageMeta().ifPresent(mng->{
            if(mng.getDisplayingPage() instanceof ItemDisplay itemDisplay){
                itemDisplay.buildContentsWith(stackStream.map(ItemKey::asKey).toList());
            }
        });
    }
}
