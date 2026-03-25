package adris.altoclef.multiversion;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;


public class EnchantmentHelperVer {

    @Pattern
    public boolean hasBindingCurse(ItemStack stack) {
        return EnchantmentHelper.hasAnyEnchantmentsWith(stack, net.minecraft.component.EnchantmentEffectComponentTypes.PREVENT_ARMOR_CHANGE);
    }

}
