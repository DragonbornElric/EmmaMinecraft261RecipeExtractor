package com.emma.endinv;

import com.emma.endinv.data.EndlessInventoryData;
import com.emma.endinv.menu.EndlessInventoryMenu;
import com.emma.endinv.menu.page.pageManager.AttachingMonitor;
import com.emma.endinv.menu.page.pageManager.PageMetaDataManager;
import com.emma.endinv.options.ServerConfigs;
import com.emma.endinv.util.Accessibility;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import org.jetbrains.annotations.Nullable;
import java.util.*;

public final class ServerLevelEndInv {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Map<ServerPlayer, PageMetaDataManager> PAGE_META_DATA_MANAGER = new HashMap<>();

    public static final Map<ServerPlayer, EndlessInventory> TEMP_ENDINV_REG = new HashMap<>();

    public static EndlessInventoryData levelEndInvData;

    private ServerLevelEndInv(){}

    public static Optional<PageMetaDataManager> checkAndGetManagerForPlayer(ServerPlayer player){
        if(player.containerMenu instanceof EndlessInventoryMenu menu) return Optional.of(menu);
        if(PAGE_META_DATA_MANAGER.get(player) instanceof AttachingMonitor manager){
            if(manager.getMenu()!=player.containerMenu) return Optional.empty();
            return Optional.of(manager);
        }else return Optional.empty();
    }

    /**
     * Get player's EndInv, if player has not or has invalid, create new.
     * @param player the player
     * @return Player's EndInv, original or created
     */
    public static Optional<EndlessInventory> getEndInvForPlayer(Player player){
        if (levelEndInvData == null) return Optional.empty();
        EndlessInventory endlessInventory = null;
        if(hasEndInvUuid(player)){
            endlessInventory = getPlayerDefaultEndInv(player);
        }
        if(endlessInventory==null){
            switch (ServerConfigs.CREATION_MODE.get()){
                case CREATE_PER_PLAYER -> {
                    endlessInventory = levelEndInvData.levelEndInvs
                            .stream()
                            .filter(endinv->endinv.owner!=null && endinv.owner.equals(player.getUUID()))
                            .findFirst()
                            .orElseGet(() -> createForPlayer(player));
                    ModRegistries.NbtAttachments.getEndInvUUID().setTo(player,endlessInventory.getUuid());
                }
                case USE_GLOBAL_SHARED -> {
                    endlessInventory = getFirstPublicEndInv();
                    ModRegistries.NbtAttachments.getEndInvUUID().setTo(player,endlessInventory.getUuid());
                }
            }
        }
        if(endlessInventory!=null) endlessInventory.viewers.add((ServerPlayer) player);
        return Optional.ofNullable(endlessInventory);
    }

    public static EndlessInventory createPublicEndInv(){
        EndlessInventory endlessInventory = new EndlessInventory();
        levelEndInvData.addEndInvToLevel(endlessInventory);
        endlessInventory.setAccessibility(Accessibility.PUBLIC);
        return endlessInventory;
    }

    public static EndlessInventory getFirstPublicEndInv(){
        var optional = levelEndInvData.levelEndInvs.stream()
                .filter(endInv->endInv.getOwnerUUID()==null&&endInv.getAccessibility()==Accessibility.PUBLIC)
                .findFirst();
        if(optional.isPresent()){
            return optional.get();
        }
        optional = levelEndInvData.levelEndInvs.stream()
                .filter(endInv->endInv.getAccessibility()==Accessibility.PUBLIC)
                .findFirst();
        return optional.orElseGet(ServerLevelEndInv::createPublicEndInv);
    }

    private static EndlessInventory createForPlayer(Player player){
        EndlessInventory endlessInventory = new EndlessInventory(ModRegistries.NbtAttachments.getEndInvUUID().computeIfAbsent(player));
        levelEndInvData.addEndInvToLevel(endlessInventory);
        endlessInventory.setAccessibility(com.emma.endinv.options.ServerConfigs.ENDINV_BEHAVIOR.Access.get());
        endlessInventory.setOwner(player.getUUID());
        ModRegistries.NbtAttachments.getEndInvUUID().setTo(player,endlessInventory.getUuid());
        return endlessInventory;
    }

    /**
     * Check whether player has a valid uuid, which is not {@link ModInfo#DEFAULT_UUID}
     * @param player player to check endInv uuid
     * @return true if player has uuid and the uuid is valid.
     */
    public static boolean hasEndInvUuid(Player player){
        UUID optional = ModRegistries.NbtAttachments.getEndInvUUID().getWith(player);
        if(optional == null) return false;
        if (Objects.equals(optional, ModInfo.DEFAULT_UUID)) {
            LOGGER.warn("Player {} has default endless inventory UUID.", player.getName().getString());
            return false;
        }
        return true;
    }
    @Nullable
    private static EndlessInventory getPlayerDefaultEndInv(Player player){
        return levelEndInvData.fromUUID(ModRegistries.NbtAttachments.getEndInvUUID().getWith(player));
    }
}
