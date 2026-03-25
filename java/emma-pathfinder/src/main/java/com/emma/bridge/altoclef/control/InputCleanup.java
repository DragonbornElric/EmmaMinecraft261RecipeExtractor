package adris.altoclef.control;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;

/**
 * Centralized input cleanup for AltoClef task cancellation / completion.
 * <p>
 * Replaces the old {@code getInputOverrideHandler().clearAllKeys()} calls
 * with direct state resets: entity state setters + ManualSteering restore.
 */
public class InputCleanup {

    /**
     * Reset all AltoClef-controlled input state.
     * Call on task stop, cancel, or chain completion.
     * <ul>
     *   <li>Clears sneak/sprint entity state</li>
     *   <li>Stops ManualSteering (restores KeyboardInput)</li>
     * </ul>
     */
    public static void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player != null) {
            player.setSneaking(false);
            player.setSprinting(false);
        }
        // Release held interaction keys (shields, eating, bows) and jump
        DirectInput.setUseHeld(false);
        client.options.jumpKey.setPressed(false);
        ManualSteering.stop();
    }
}
