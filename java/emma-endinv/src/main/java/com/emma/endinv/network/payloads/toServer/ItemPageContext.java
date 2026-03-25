package com.emma.endinv.network.payloads.toServer;

import com.emma.endinv.AbstractModInitializer;
import com.emma.endinv.EndlessInventory;
import com.emma.endinv.ServerLevelEndInv;
import com.emma.endinv.menu.EndlessInventoryMenu;
import com.emma.endinv.menu.page.pageManager.AttachingMonitor;
import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.ModPacketPayload;
import com.emma.endinv.network.payloads.PageData;
import com.emma.endinv.network.payloads.toClient.EndInvContent;
import com.emma.endinv.network.payloads.toClient.EndInvMetadata;
import com.emma.endinv.network.payloads.toClient.SetItemDisplayContentPayload;
import com.emma.endinv.options.ContentTransferMode;
import com.emma.endinv.util.SortType;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

import static com.emma.endinv.ModInfo.getPacketDistributor;

/**
 * In page context used on Page operations.
 *
 * @param startIndex
 * @param length
 * @param pageData
 */
public record ItemPageContext(int startIndex, int length, PageData pageData) implements ModPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemPageContext> STREAM_CODEC =
            StreamCodec.of((buf, value) -> encode(value, buf), ItemPageContext::decode);

    public static final CustomPacketPayload.Type<ItemPageContext> TYPE =
            new CustomPacketPayload.Type<>(AbstractModInitializer.withModLocation("page_context"));

    public static void encode(ItemPageContext context, FriendlyByteBuf o) {
        o.writeInt(context.startIndex);
        o.writeInt(context.length);
        PageData.encode(o, context.pageData);
    }

    public static ItemPageContext decode(FriendlyByteBuf o) {
        return new ItemPageContext(o.readInt(), o.readInt(), PageData.decode(o));
    }

    public SortType sortType() {
        return pageData.sortType();
    }

    public String search() {
        return pageData.search();
    }


    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ItemPageContext context)) return false;
        return length == context.length && startIndex == context.startIndex && Objects.equals(pageData, context.pageData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startIndex, length, pageData);
    }

    @Override
    public String id() {
        return "page_context";
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ModPacketContext iPayloadContext) {
        ServerPlayer serverPlayer = (ServerPlayer) iPayloadContext.player();
        assert serverPlayer!=null;
        var optional = ServerLevelEndInv.checkAndGetManagerForPlayer(serverPlayer);
        optional.ifPresent(manager -> {

            if (!Objects.equals(manager.getPageData(), this.pageData)) {
                SortType sortType = pageData.sortType();
                boolean reverseSort = pageData.reverseSort();
                String search = pageData.search();

                if (manager instanceof AttachingMonitor attachingMonitor) {
                    attachingMonitor.applyPageData(pageData);
                } else if (manager instanceof EndlessInventoryMenu menuManager) {
                    menuManager.applyPageData(pageData);
                } else {
                    manager.setSortType(sortType);
                    manager.setSortReversed(reverseSort);
                    manager.setSearching(search);
                    manager.switchPageWithId(pageData().pageRegKey());
                }
            }

            //== ServerEndInv#sendEndInvContent
            EndlessInventory endInv = (EndlessInventory) manager.getSourceInventory();
            if (com.emma.endinv.options.ServerConfigs.ENDINV_BEHAVIOR.TransferMode.get() == ContentTransferMode.PART) {
                List<ItemStack> view = endInv.getSortedAndFilteredItemView(startIndex, length,
                        manager.sortType(), manager.isSortReversed(),
                        manager.getDisplayingPageType().itemClassify, manager.searching());

                NonNullList<ItemStack> stacks = NonNullList.withSize(length, ItemStack.EMPTY);
                for (int i = 0; i < view.size(); ++i) {
                    stacks.set(i, view.get(i));
                }
                getPacketDistributor().sendToPlayer(serverPlayer, EndInvMetadata.getWith(endInv));
                getPacketDistributor().sendToPlayer(serverPlayer, new SetItemDisplayContentPayload(stacks));
            } else if (com.emma.endinv.options.ServerConfigs.ENDINV_BEHAVIOR.TransferMode.get() == ContentTransferMode.ALL) {
                getPacketDistributor().sendToPlayer(serverPlayer, new EndInvContent(endInv.getItemMap()));
            }
        });
    }
}


