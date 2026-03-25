package adris.altoclef.multiversion;

import net.minecraft.client.MinecraftClient;

public class MinecraftClientVer {


    @Pattern
    private static float getTickDelta(MinecraftClient client) {
        return client.getRenderTickCounter().getTickProgress(true);
    }

}
