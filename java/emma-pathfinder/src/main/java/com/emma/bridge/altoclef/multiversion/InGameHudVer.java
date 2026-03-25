package adris.altoclef.multiversion;

import net.minecraft.client.MinecraftClient;

public class InGameHudVer {

    public static boolean shouldShowDebugHud() {
        return MinecraftClient.getInstance().inGameHud.getDebugHud().shouldShowDebugHud();
    }

}
