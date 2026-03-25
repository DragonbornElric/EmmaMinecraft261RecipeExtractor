package adris.altoclef.multiversion;

import net.minecraft.block.Block;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.Registries;

public class BlockTagVer {


    public static boolean isWool(Block block) {
        return Registries.BLOCK.getEntry(block).isIn(BlockTags.WOOL);
    }

}
