package adris.altoclef.multiversion;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.world.World;

public class DamageSourceVer {


    public static DamageSource getFallDamageSource(World world) {
        return world.getDamageSources().fall();
    }

}
