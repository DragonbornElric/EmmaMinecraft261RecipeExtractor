package adris.altoclef.multiversion;

import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.spawner.MobSpawnerLogic;
import net.minecraft.world.World;

import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.Random;

public class MethodWrapper {



    public static Entity getRenderedEntity(MobSpawnerLogic logic, World world, BlockPos pos) {
        return logic.getRenderedEntity(world, pos);
    }

    public static float getDamageLeft(LivingEntity armorWearer, double damage, DamageSource source, double armor, double armorToughness) {
        return getDamageLeft(armorWearer, (float)damage,source,(float)armor,(float)armorToughness);
    }

    public static float getDamageLeft(LivingEntity armorWearer, float damage, DamageSource source, float armor, float armorToughness) {
        return DamageUtil.getDamageLeft(armorWearer, damage, source, armor, armorToughness);
    }



}
