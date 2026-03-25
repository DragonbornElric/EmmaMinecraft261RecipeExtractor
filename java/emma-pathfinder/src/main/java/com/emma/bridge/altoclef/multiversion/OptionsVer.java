package adris.altoclef.multiversion;

import net.minecraft.client.MinecraftClient;

public class OptionsVer {


    public static void setGamma(double value) {
        MinecraftClient.getInstance().options.getGamma().setValue(value);
    }

    public static void setAutoJump(boolean value) {
        MinecraftClient.getInstance().options.getAutoJump().setValue(value);
    }

}
