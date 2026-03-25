package com.emma.endinv;

import com.emma.endinv.network.IPacketDistributor;
import com.emma.endinv.util.SortType;

import java.util.UUID;

public final class ModInfo {

    public static final String MOD_ID = "endless_inventory";

    public static final UUID DEFAULT_UUID = new UUID(0L, 0L);

    static boolean clientLoaded = false;

    public static SortType.ISortHelper sortHelper = new SortType.ISortHelper() {};

    private static IPacketDistributor packetDistributor;

    public static IPlatform platformContext;

    public static IPacketDistributor getPacketDistributor(){
        return packetDistributor;
    }

    public static void setPacketDistributor(IPacketDistributor packetDistributor){
        ModInfo.packetDistributor = packetDistributor;
    }

    public static boolean isClientLoaded(){
        return clientLoaded;
    }
}
