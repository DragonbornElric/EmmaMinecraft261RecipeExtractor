package adris.altoclef.multiversion;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ToolMaterial;

public class ToolMaterialVer {

    /**
     * Get the mining level for a plain Item (MC 1.21.8+: ToolItem removed).
     * Uses a hardcoded lookup based on known tool items.
     * Returns -1 if the item is not a recognized tool.
     */
    public static int getMiningLevel(Item item) {
        if (item == Items.WOODEN_PICKAXE || item == Items.WOODEN_AXE || item == Items.WOODEN_SHOVEL || item == Items.WOODEN_HOE || item == Items.WOODEN_SWORD
                || item == Items.GOLDEN_PICKAXE || item == Items.GOLDEN_AXE || item == Items.GOLDEN_SHOVEL || item == Items.GOLDEN_HOE || item == Items.GOLDEN_SWORD) {
            return 0;
        } else if (item == Items.STONE_PICKAXE || item == Items.STONE_AXE || item == Items.STONE_SHOVEL || item == Items.STONE_HOE || item == Items.STONE_SWORD) {
            return 1;
        } else if (item == Items.IRON_PICKAXE || item == Items.IRON_AXE || item == Items.IRON_SHOVEL || item == Items.IRON_HOE || item == Items.IRON_SWORD) {
            return 2;
        } else if (item == Items.DIAMOND_PICKAXE || item == Items.DIAMOND_AXE || item == Items.DIAMOND_SHOVEL || item == Items.DIAMOND_HOE || item == Items.DIAMOND_SWORD) {
            return 3;
        } else if (item == Items.NETHERITE_PICKAXE || item == Items.NETHERITE_AXE || item == Items.NETHERITE_SHOVEL || item == Items.NETHERITE_HOE || item == Items.NETHERITE_SWORD) {
            return 4;
        }
        // Unknown tool — check if it at least has a TOOL component
        if (new ItemStack(item).contains(DataComponentTypes.TOOL)) {
            return 0; // default to wood-level for unknown tools
        }
        return -1;
    }

    public static int getMiningLevel(ToolMaterial material) {
        if (material == ToolMaterial.WOOD || material == ToolMaterial.GOLD) {
            return 0;
        } else if (material == ToolMaterial.STONE) {
            return 1;
        } else if (material == ToolMaterial.IRON) {
            return 2;
        } else if (material == ToolMaterial.DIAMOND) {
            return 3;
        } else if (material == ToolMaterial.NETHERITE) {
            return 4;
        }
        return 0; // unknown material — default to wood-level
    }

}
