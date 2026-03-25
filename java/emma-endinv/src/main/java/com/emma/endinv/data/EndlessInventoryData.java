package com.emma.endinv.data;

import com.emma.endinv.EndlessInventory;
import com.emma.endinv.ModInfo;
import com.emma.endinv.ServerLevelEndInv;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import org.jetbrains.annotations.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

import static com.emma.endinv.data.EndInvCodecStrategy.END_INV_LIST_KEY;

public class EndlessInventoryData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final SavedDataType<EndlessInventoryData> DATA_TYPE = new SavedDataType<>(
            // The identifier of the saved data
            // Used as the path within the level's `data` folder
            Identifier.fromNamespaceAndPath("endless_inventory", END_INV_LIST_KEY),
            // The initial constructor
            EndlessInventoryData::new,
            // The codec used to serialize the data
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.list(EndlessInventory.CODEC).fieldOf(END_INV_LIST_KEY).forGetter(EID -> EID.levelEndInvs)
            ).apply(instance, lst -> {
                var EID = new EndlessInventoryData();
                for(var endinv : lst){
                    EID.addEndInvToLevel(endinv);
                }
                return EID;
            })),
            DataFixTypes.SAVED_DATA_MAP_DATA
    );

    public final NonNullList<EndlessInventory> levelEndInvs;

    public record BackupResult(boolean success, @Nullable String message) {}

    private EndlessInventoryData(){
        this.levelEndInvs = NonNullList.create();
    }

    public static void init(ServerLevel level){
        if (!level.dimension().equals(Level.OVERWORLD)) {
            //LOGGER.warn("Skipped EndlessInventoryData initialization in dimension: {}", level.dimension().location());
            return; // 仅在主世界执行
        }

        ServerLevelEndInv.levelEndInvData = level.getDataStorage().computeIfAbsent(DATA_TYPE);

        LOGGER.info("Initialized EndlessInventoryData in {} with {} inventories", String.valueOf(level.dimension()), ServerLevelEndInv.levelEndInvData.levelEndInvs.size());
    }

    public static BackupResult backup(ServerLevel level) {
        try {
            Path worldDir = level.getServer().getWorldPath(LevelResource.ROOT).normalize();
            Path dataFile = worldDir.resolve("data/endless_inventories.dat");

            if (!Files.exists(dataFile)) {
                throw new FileNotFoundException("Cannot find data file: " + dataFile);
            }

            // 创建备份文件夹
            Path backupDir = worldDir.resolve("endinv_backup");
            Files.createDirectories(backupDir);

            // 添加时间戳到备份文件名
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path backupFile = backupDir.resolve("endless_inventories_" + timestamp + ".dat");

            // 复制文件
            Files.copy(dataFile, backupFile, StandardCopyOption.REPLACE_EXISTING);

            return new BackupResult(true, backupFile.toString());
        } catch (IOException e) {
            return new BackupResult(false, e.getMessage());
        } catch (Exception e) {
            return new BackupResult(false, "Unexpected exception");
        }
    }

    public static EndlessInventoryData create(){
        return new EndlessInventoryData();
    }

    public void addEndInvToLevel(EndlessInventory endlessInventory){
        levelEndInvs.add(endlessInventory);
        setDirty();
    }

    public void byIndexRemove(int index){
        if(index<0 || index>= levelEndInvs.size()) return;
        levelEndInvs.remove(index);
    }
    @Nullable
    public EndlessInventory fromUUID(@Nullable UUID uuid){
        if(uuid == null || Objects.equals(uuid, ModInfo.DEFAULT_UUID)) return null;
        for(EndlessInventory endlessInventory : levelEndInvs){
            if (Objects.equals(endlessInventory.getUuid(),uuid)) return endlessInventory;
        }
        return null;
    }

    @Nullable
    public EndlessInventory fromIndex(int index){
        if(index<0 || index>= levelEndInvs.size()) return null;
        return levelEndInvs.get(index);
    }

    public int getIndex(EndlessInventory endlessInventory) {
        int index = 0;
        for(EndlessInventory endinv : levelEndInvs){
            if(Objects.equals(endlessInventory,endinv)) return index;
            index++;
        }
        return -1;
    }
}
