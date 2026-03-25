package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.chains.MobDefenseChain;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;

import java.util.List;

/**
 * Attacks an entity, but the target entity must be specified.
 */
public abstract class AbstractKillEntityTask extends AbstractDoToEntityTask {
    private static final double OTHER_FORCE_FIELD_RANGE = 2;

    // Not the "striking" distance, but the "ok we're close enough, lower our guard for other mobs and focus on this one" range.
    private static final double CONSIDER_COMBAT_RANGE = 10;

    protected AbstractKillEntityTask() {
        this(CONSIDER_COMBAT_RANGE, OTHER_FORCE_FIELD_RANGE);
    }

    protected AbstractKillEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    protected AbstractKillEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(maintainDistance, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    public static Item bestWeapon(AltoClef mod) {
        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);

        Item bestItem = MobDefenseChain.getBestWeapon(mod);
        if (bestItem != null) {
            return bestItem;
        }

        // just get highest damage — in MC 1.21.8+ tools are plain Items, check TOOL component
        bestItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        float bestDamage = Float.NEGATIVE_INFINITY;

        ItemStack equippedStack = new ItemStack(bestItem);
        if (equippedStack.contains(DataComponentTypes.TOOL)) {
            bestDamage = getAttackDamage(bestItem);
        }

        for (ItemStack invStack : invStacks) {
            Item invItem = invStack.getItem();
            if (!invStack.contains(DataComponentTypes.TOOL)) continue;

            float itemDamage = getAttackDamage(invItem);

            if (itemDamage > bestDamage) {
                bestItem = invItem;
                bestDamage = itemDamage;
            }
        }

        return bestItem;
    }

    /**
     * Hardcoded attack damage lookup for known tools/weapons (MC 1.21.8+: ToolItem removed).
     */
    private static float getAttackDamage(Item item) {
        if (item == Items.NETHERITE_SWORD) return 8;
        if (item == Items.DIAMOND_SWORD)   return 7;
        if (item == Items.IRON_SWORD)      return 6;
        if (item == Items.GOLDEN_SWORD)    return 4;
        if (item == Items.STONE_SWORD)     return 5;
        if (item == Items.WOODEN_SWORD)    return 4;
        if (item == Items.TRIDENT)         return 9;
        if (item == Items.MACE)            return 5;
        if (item == Items.NETHERITE_AXE)   return 10;
        if (item == Items.DIAMOND_AXE)     return 9;
        if (item == Items.IRON_AXE)        return 9;
        if (item == Items.GOLDEN_AXE)      return 7;
        if (item == Items.STONE_AXE)       return 9;
        if (item == Items.WOODEN_AXE)      return 7;
        if (item == Items.NETHERITE_PICKAXE) return 6;
        if (item == Items.DIAMOND_PICKAXE)   return 5;
        if (item == Items.IRON_PICKAXE)      return 4;
        if (item == Items.GOLDEN_PICKAXE)    return 2;
        if (item == Items.STONE_PICKAXE)     return 3;
        if (item == Items.WOODEN_PICKAXE)    return 2;
        if (item == Items.NETHERITE_SHOVEL) return 6.5f;
        if (item == Items.DIAMOND_SHOVEL)   return 5.5f;
        if (item == Items.IRON_SHOVEL)      return 4.5f;
        if (item == Items.GOLDEN_SHOVEL)    return 2.5f;
        if (item == Items.STONE_SHOVEL)     return 3.5f;
        if (item == Items.WOODEN_SHOVEL)    return 2.5f;
        return 1; // unknown tool
    }

    public static boolean equipWeapon(AltoClef mod) {
        Item bestWeapon = bestWeapon(mod);
        Item equipedWeapon = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        if (bestWeapon != null && bestWeapon != equipedWeapon) {
            mod.getSlotHandler().forceEquipItem(bestWeapon);
            return true;
        }
        return false;
    }

    @Override
    protected Task onEntityInteract(AltoClef mod, Entity entity) {
        // Equip weapon
        if (!equipWeapon(mod)) {
            float hitProg = mod.getPlayer().getAttackCooldownProgress(0);
            if (hitProg >= 1 && (mod.getPlayer().isOnGround() || mod.getPlayer().getVelocity().getY() < 0 || mod.getPlayer().isTouchingWater())) {
                Vec3d aimPos = ProjectileHelper.getLeadPredictedAimPos(
                    mod.getPlayer().getEyePos(), entity, 1.5f);
                LookHelper.lookAt(mod, aimPos);
                mod.getControllerExtras().attack(entity);
            }
        }
        return null;
    }
}