package adris.altoclef.multiversion;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;

public class BlockStateVer {


    @Pattern
    private static boolean isSolid(BlockState state) {
        return state.isSolid();
    }

    @Pattern
    private static boolean isReplaceable(BlockState state) {
        return state.isReplaceable();
    }

    @Pattern
    private static float getHardness(BlockState state) {
        return state.getBlock().getHardness();
    }

    @Pattern
    private static float getHardness(Block block) {
        return block.getHardness();
    }

}
