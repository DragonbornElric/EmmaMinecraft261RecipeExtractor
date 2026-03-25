package adris.altoclef.multiversion.entity;

import adris.altoclef.multiversion.Pattern;
import net.fabricmc.loader.impl.lib.sat4j.core.Vec;
import net.minecraft.entity.Entity;
import adris.altoclef.mixins.EntityAccessor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

public class EntityVer {


    @Pattern
    public boolean isInNetherPortal(Entity entity) {
        return adris.altoclef.multiversion.entity.EntityHelper.isInNetherPortal(entity);
    }

    @Pattern
    public int getPortalCooldown(Entity entity) {
        return entity.getPortalCooldown();
    }


    @Pattern
    public BlockPos getLandingPos(Entity entity) {
        return entity.getSteppingPos();
    }

    @Pattern
    private static float getPitch(Entity player) {
        return player.getPitch();
    }

    @Pattern
    private static float getYaw(Entity player) {
        return player.getYaw();
    }

    @Pattern
    private static void setPitch(Entity player, float value) {
        player.setPitch(value);
    }

    @Pattern
    private static void setYaw(Entity player, float value) {
        player.setYaw(value);
    }

    @Pattern
    private static Vec3d getEyePos(Entity entity) {
        return entity.getEyePos();
    }

    @Pattern
    private static ChunkPos getChunkPos(Entity entity) {
        return entity.getChunkPos();
    }

    @Pattern
    private static int getBlockX(Entity entity) {
        return entity.getBlockX();
    }

    @Pattern
    private static int getBlockY(Entity entity) {
        return entity.getBlockY();
    }

    @Pattern
    private static int getBlockZ(Entity entity) {
        return entity.getBlockZ();
    }

}
