package adris.altoclef.multiversion.blockpos;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.util.math.*;
import adris.altoclef.multiversion.blockpos.BlockPosHelper;

public class BlockPosVer {


    public static BlockPos ofFloored(Position pos) {
        return new BlockPos(MathHelper.floor(pos.getX()), MathHelper.floor(pos.getY()), MathHelper.floor(pos.getZ()));
    }


    public static double getSquaredDistance(BlockPos pos, Position obj) {
        return pos.getSquaredDistance(obj);
    }

    @Pattern
    private static Vec3i north(Vec3i blockPos) {
        return blockPos.north();
    }

    @Pattern
    private static Vec3i north(Vec3i blockPos, int amount) {
        return blockPos.north(amount);
    }

    @Pattern
    private static Vec3i east(Vec3i blockPos) {
        return blockPos.east();
    }

    @Pattern
    private static Vec3i east(Vec3i blockPos, int amount) {
        return blockPos.east(amount);
    }

    @Pattern
    private static Vec3i west(Vec3i blockPos) {
        return blockPos.west();
    }

    @Pattern
    private static Vec3i west(Vec3i blockPos, int amount) {
        return blockPos.west(amount);
    }

    @Pattern
    private static Vec3i south(Vec3i blockPos) {
        return blockPos.south();
    }

    @Pattern
    private static Vec3i south(Vec3i blockPos, int amount) {
        return blockPos.south(amount);
    }

    @Pattern
    private static Vec3i add(Vec3i blockPos, int x, int y, int z) {
        return blockPos.add(x,y,z);
    }


}
