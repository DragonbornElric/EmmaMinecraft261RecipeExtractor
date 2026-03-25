package com.emma.endinv.network.payloads.toServer;

import com.emma.endinv.AbstractModInitializer;
import com.emma.endinv.ModRegistries;
import com.emma.endinv.ServerLevelEndInv;
import com.emma.endinv.menu.EndlessInventoryMenu;
import com.emma.endinv.menu.page.pageManager.AttachingMonitor;
import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.ModPacketPayload;
import com.emma.endinv.network.payloads.SyncedConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;


/**Send to server,
 * to open {@link EndlessInventoryMenu} when player is not opening a menu,
 * or to mention server to attach an {@link AttachingMonitor} if player is opening a menu.
 * @param openNew true for opening a new EIM, false -> ignore rows
 * @param rows The row data to adjust position of EIM widgets
 * @param columns reserved data, default to 9
 */
public record OpenEndInvPayload(boolean openNew, int rows, int columns) implements ModPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public OpenEndInvPayload(boolean ofMenu, int rows){
        this(ofMenu,rows,9);
    }

    public OpenEndInvPayload(){
        this(false,6);
    }

    public static void encode(OpenEndInvPayload payload, FriendlyByteBuf o){
        o.writeBoolean(payload.openNew);o.writeInt(payload.rows);o.writeInt(payload.columns);
    }

    public static OpenEndInvPayload decode(FriendlyByteBuf o){
        return new OpenEndInvPayload(o.readBoolean(),o.readInt(),o.readInt());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenEndInvPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> encode(value, buf), OpenEndInvPayload::decode);

    public static final CustomPacketPayload.Type<OpenEndInvPayload> TYPE =
            new CustomPacketPayload.Type<>(AbstractModInitializer.withModLocation("open_endinv"));

    @Override
    public String id() {
        return "open_endinv";
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @Override
    public void handle(ModPacketContext iPayloadContext) {
        ServerPlayer player = (ServerPlayer) iPayloadContext.player();
        if(player==null) return;
        if(player.containerMenu == player.inventoryMenu && openNew()){
            player.openMenu(EndlessInventoryMenu.provide(rows));
        }else if(!openNew()){
            if(!checkAttachingStatus(player)) {
                LOGGER.warn("Player unable to open AttachingScreen sent open AS.");
                return;
            }
            ServerLevelEndInv.getEndInvForPlayer(player).ifPresent(endInv->{
                AttachingMonitor manager = new AttachingMonitor(player.containerMenu, endInv ,player);
                ServerLevelEndInv.PAGE_META_DATA_MANAGER.put(player,manager);
                //manager.sendEndInvData(); Deprecated: let page request data
            });

        }

    }

    /**
     */
    private boolean checkAttachingStatus(ServerPlayer player){
        SyncedConfig syncedConfig = ModRegistries.NbtAttachments.getSyncedConfig().computeIfAbsent(player);
        boolean nbtAttaching = syncedConfig.attaching();//presents player's client attaching config
        //boolean configAttaching = ModInfo.getServerConfig().enableAttaching().get();
        boolean menuAttachable = com.emma.endinv.options.ServerConfigs.SPECIFIED_ATTACHABILITY.get().attachable(player);

        if(!nbtAttaching){
            LOGGER.warn("Player with attaching=false nbt sent openAttaching payload.");
            //assume player's client attaching config is true.
            ModRegistries.NbtAttachments.getSyncedConfig().setTo(player, new SyncedConfig(true, syncedConfig.autoPicking()));
        }

        return menuAttachable;
    }

}
