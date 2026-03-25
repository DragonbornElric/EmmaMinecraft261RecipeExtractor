package adris.altoclef.multiversion.world;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import net.minecraft.registry.entry.RegistryEntry;

public class WorldVer {



    public static boolean isBiomeAtPos(World world, RegistryKey<Biome> biome, BlockPos pos) {
        RegistryEntry<Biome> b = world.getBiome(pos);
        return b.matchesKey(biome);
    }


    public static boolean isBiome(RegistryEntry<Biome> biome1, RegistryKey<Biome> biome2) {
        return biome1.matchesKey(biome2);
    }


    @Pattern
    public static int getBottomY(World world) {
        return world.getBottomY();
    }

    // In 1.21.8, getTopY() no-arg was removed from HeightLimitView.
    // Use getBottomY() + getHeight() which is equivalent.
    @Pattern
    public static int getTopY(World world) {
        return world.getBottomY() + world.getHeight();
    }

    @Pattern
    private static boolean isOutOfHeightLimit(World world,BlockPos pos) {
        return world.isOutOfHeightLimit(pos);
    }

}
