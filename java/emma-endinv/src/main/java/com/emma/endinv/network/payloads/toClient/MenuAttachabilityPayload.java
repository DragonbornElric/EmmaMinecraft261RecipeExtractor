package com.emma.endinv.network.payloads.toClient;

import com.emma.endinv.AbstractModInitializer;
import com.emma.endinv.client.option.MenuAttachabilityCache;
import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.ModPacketPayload;
import com.emma.endinv.options.SpecifiedMenuAttachingConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;

import java.util.HashMap;
import java.util.Map;

/**
 * Server -> Client payload carrying effective menu attachability.
 * Clients use it to decide whether to create AttachingScreen for a menu.
 */
public record MenuAttachabilityPayload(
        boolean defaultAttach,
        boolean inventoryAttach,
        Map<MenuType<?>, Boolean> perMenu
) implements ModPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, MenuAttachabilityPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, MenuAttachabilityPayload>() {
        @Override
        public MenuAttachabilityPayload decode(RegistryFriendlyByteBuf registryFriendlyByteBuf) {
            return MenuAttachabilityPayload.decode(registryFriendlyByteBuf);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf o, MenuAttachabilityPayload menuAttachabilityPayload) {
            MenuAttachabilityPayload.encode(menuAttachabilityPayload, o);
        }
    };

    public static final Type<MenuAttachabilityPayload> TYPE = new Type<>(AbstractModInitializer.withModLocation("menu_attachability"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static MenuAttachabilityPayload of(boolean defaultAttach, SpecifiedMenuAttachingConfig config) {
        return new MenuAttachabilityPayload(defaultAttach, config.isInventoryAttachable(), new HashMap<>(config.getConfigs()));
    }

    public static void encode(MenuAttachabilityPayload payload, FriendlyByteBuf buf) {
        buf.writeBoolean(payload.defaultAttach);
        buf.writeBoolean(payload.inventoryAttach);
        buf.writeVarInt(payload.perMenu.size());
        for (var e : payload.perMenu.entrySet()) {
            Identifier id = BuiltInRegistries.MENU.getKey(e.getKey());
            if (id == null) continue;
            buf.writeIdentifier(id);
            buf.writeBoolean(Boolean.TRUE.equals(e.getValue()));
        }
    }

    public static MenuAttachabilityPayload decode(FriendlyByteBuf buf) {
        boolean def = buf.readBoolean();
        boolean inv = buf.readBoolean();
        int size = buf.readVarInt();
        Map<MenuType<?>, Boolean> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            Identifier id = buf.readIdentifier();
            boolean val = buf.readBoolean();
            BuiltInRegistries.MENU.get(id).map(Holder::value).ifPresent(type -> map.put(type, val));
        }
        return new MenuAttachabilityPayload(def, inv, map);
    }

    @Override
    public String id() {
        return "menu_attachability";
    }

    @Override
    public void handle(ModPacketContext context) {
        // Client-side cache update
        if (context.player() == null) return;
        MenuAttachabilityCache.accept(this);
    }
}
