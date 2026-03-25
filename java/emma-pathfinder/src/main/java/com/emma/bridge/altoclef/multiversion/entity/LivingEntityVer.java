package adris.altoclef.multiversion.entity;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class LivingEntityVer {


    // In 1.21.8, getEquippedItems() was removed.
    // Instead, iterate over all EquipmentSlot values and collect stacks.
    @Pattern
    private static Iterable<ItemStack> getItemsEquipped(LivingEntity entity) {
        List<ItemStack> items = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            items.add(entity.getEquippedStack(slot));
        }
        return items;
    }

    @Pattern
    private static boolean isSuitableFor(Item item, BlockState state) {
        return item.getDefaultStack().isSuitableFor(state);
    }

}
