package adris.altoclef.multiversion.item;

import adris.altoclef.multiversion.FoodComponentWrapper;
import adris.altoclef.multiversion.Pattern;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class ItemVer {

    public static FoodComponentWrapper getFoodComponent(Item item) {
        return FoodComponentWrapper.of(item.getComponents().get(net.minecraft.component.DataComponentTypes.FOOD));
    }

    public static boolean isFood(ItemStack stack) {
        return isFood(stack.getItem());
    }

    public static boolean hasCustomName(ItemStack stack) {
        return stack.contains(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
    }

    public static boolean isFood(Item item) {
        return item.getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD);
    }

    @Pattern
    private static boolean isSuitableFor(Item item, BlockState state) {
        return item.getDefaultStack().isSuitableFor(state);
    }

    // the fact that this works is insane...
    @Pattern
    private static Item RAW_GOLD() {
        return Items.RAW_GOLD;
    }

    @Pattern
    private static Item RAW_IRON() {
        return Items.RAW_IRON;
    }


}
