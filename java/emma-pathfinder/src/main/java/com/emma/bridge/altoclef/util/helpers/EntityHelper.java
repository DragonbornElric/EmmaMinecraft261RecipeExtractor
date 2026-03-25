package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.DamageSourceWrapper;
import adris.altoclef.multiversion.MethodWrapper;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Helper functions to interpret entity state
 */
public class EntityHelper {
    public static final double ENTITY_GRAVITY = 0.08; // per second

    public static boolean isAngryAtPlayer(AltoClef mod, Entity mob) {
        boolean hostile = isProbablyHostileToPlayer(mod, mob);
        if (mob instanceof LivingEntity entity) {
            return hostile && entity.canSee(mod.getPlayer());
        }
        return hostile;
    }

    public static boolean isProbablyHostileToPlayer(AltoClef mod, Entity entity) {
        if (entity instanceof MobEntity mob) {
            if (mob instanceof SlimeEntity slime) {
                return slime.getAttributeValue(EntityAttributes.ATTACK_DAMAGE) > 0;
            }
            if (mob instanceof PiglinEntity piglin) {
                return piglin.isAttacking() && !isTradingPiglin(mob) && piglin.isAdult();
            }
            if (mob instanceof EndermanEntity enderman) {
                return enderman.isAngry();
            }
            if (mob instanceof ZombifiedPiglinEntity zombifiedPiglin) {
                return zombifiedPiglin.isAttacking();
            }

            return mob.isAttacking() || mob instanceof HostileEntity;
        }

        return false;
    }

    public static boolean isTradingPiglin(Entity entity) {
        if (entity instanceof PiglinEntity pig) {
            // In 1.21.8, getHandItems() was removed. Check main and off hand directly.
            if (pig.getMainHandStack().getItem().equals(Items.GOLD_INGOT)
                    || pig.getOffHandStack().getItem().equals(Items.GOLD_INGOT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate the resulting damage dealt to a player as a result of some damage.
     * If this player were to receive this damage, the player's health will be subtracted by the resulting value.
     */
    public static double calculateResultingPlayerDamage(PlayerEntity player, DamageSource src, double damageAmount) {
        // Copied logic from `PlayerEntity.applyDamage`
        DamageSourceWrapper source = DamageSourceWrapper.of(src);

        // In 1.21.8, isInvulnerableTo requires ServerWorld (not available on client).
        // Use isInvulnerable() as a client-side approximation.
        if (player.isInvulnerable())
            return 0;

        // Armor Base
        if (!source.bypassesArmor()) {
            damageAmount = MethodWrapper.getDamageLeft(player, damageAmount,src,player.getArmor(),player.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS));
        }

        // Enchantments & Potions
        if (!source.bypassesShield()) {
            float k;
            if (player.hasStatusEffect(StatusEffects.RESISTANCE) && source.isOutOfWorld()) {
                //noinspection ConstantConditions
                k = (player.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1) * 5;
                float j = 25 - k;
                double f = damageAmount * (double) j;
                double g = damageAmount;
                damageAmount = Math.max(f / 25.0F, 0.0F);
            }

            if (damageAmount <= 0.0) {
                damageAmount = 0.0;
            } else {
                k = EnchantmentHelper.getProtectionAmount(null, player, src);
                if (k > 0) {
                    damageAmount = DamageUtil.getInflictedDamage((float) damageAmount, (float) k);
                }
            }
        }

        // Absorption
        damageAmount = Math.max(damageAmount - player.getAbsorptionAmount(), 0.0F);
        return damageAmount;
    }
}
