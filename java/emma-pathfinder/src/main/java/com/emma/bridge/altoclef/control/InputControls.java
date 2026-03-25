package adris.altoclef.control;

import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;

/**
 * LOCKED DOWN — Phase 52, Step 4.
 *
 * All AltoClef call sites have been migrated:
 *   - Movement keys → {@link DirectInput} (game-mechanic use)
 *   - Click actions  → direct Mojang {@code interactionManager.*} API calls at each call site
 *
 * Movement methods are now silent no-ops (Baritone movement uses ControlledInput).
 * Click methods throw to catch any remaining callers at dev time.
 * {@link #forceLook(float, float)} is retained — it has no virtual-input equivalent.
 */
@SuppressWarnings("UnnecessaryDefault")
public class InputControls {

    // ── Movement inputs: silent no-ops ──────────────────────────────

    public void tryPress(Input input) {
        rejectClicks(input);
        // Movement: no-op
    }

    public void hold(Input input) {
        rejectClicks(input);
        // Movement: no-op
    }

    public void release(Input input) {
        rejectClicks(input);
        // Movement: no-op
    }

    public boolean isHeldDown(Input input) {
        rejectClicks(input);
        return false; // Movement: always false
    }

    // ── forceLook: retained ─────────────────────────────────────────

    public void forceLook(float yaw, float pitch) {
        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.setYaw(yaw);
            MinecraftClient.getInstance().player.setPitch(pitch);
        }
    }

    // ── Tick callbacks: no-ops (no queue to drain) ──────────────────

    public void onTickPre() { }

    public void onTickPost() { }

    // ── Internal ────────────────────────────────────────────────────

    private static void rejectClicks(Input input) {
        if (input == Input.CLICK_LEFT || input == Input.CLICK_RIGHT) {
            throw new UnsupportedOperationException(
                "InputControls no longer handles click inputs. " +
                "Use interactionManager.* APIs directly. Input: " + input
            );
        }
    }
}
