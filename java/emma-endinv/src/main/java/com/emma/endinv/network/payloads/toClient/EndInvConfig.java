package com.emma.endinv.network.payloads.toClient;

import com.emma.endinv.EndlessInventory;
import com.emma.endinv.ModInfo;
import com.emma.endinv.util.Accessibility;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 *
 * @param owner {@link ModInfo#DEFAULT_UUID} to present null uuid.
 */
public record EndInvConfig(Accessibility accessibility, UUID owner, List<UUID> white_list) {

    public static final EndInvConfig DEFAULT = new EndInvConfig(Accessibility.PUBLIC, ModInfo.DEFAULT_UUID,new ArrayList<>());


    public static EndInvConfig decode(FriendlyByteBuf friendlyByteBuf) {
        return new EndInvConfig(
                friendlyByteBuf.readEnum(Accessibility.class),
                friendlyByteBuf.readUUID(),
                friendlyByteBuf.readList(buf -> buf.readUUID())
        );
    }

    public static void encode(FriendlyByteBuf o, EndInvConfig endInvConfig) {
        o.writeEnum(endInvConfig.accessibility);
        o.writeUUID(endInvConfig.owner);
        o.writeCollection(endInvConfig.white_list, (buf, id) -> buf.writeUUID(id));
    }


    public static EndInvConfig getWith(EndlessInventory endInv){
        return new EndInvConfig(
                endInv.getAccessibility(),
                endInv.getOwnerUUID()!=null?endInv.getOwnerUUID():ModInfo.DEFAULT_UUID,
                endInv.white_list);
    }

    public String id() {
        return "endinv_config";
    }
}
