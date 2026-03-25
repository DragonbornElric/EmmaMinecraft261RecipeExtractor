package adris.altoclef.multiversion.entity;

import adris.altoclef.mixins.PortalManagerAccessor;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.dimension.NetherPortal;

public class EntityHelper {

    public static boolean isInNetherPortal(Entity entity) {
       return (entity.portalManager != null && ((PortalManagerAccessor)entity.portalManager).accessPortal() instanceof NetherPortalBlock && entity.portalManager.isInPortal())
               || entity.getPortalCooldown() > 0;
    }


}
