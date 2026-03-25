package adris.altoclef.control;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/**
 * Lightweight Input subclass for sustained manual movement outside Baritone pathfinding.
 * <p>
 * Same architecture as {@code ControlledInput} in emma-pathfinder (Mojmap side):
 * replaces {@code KeyboardInput} temporarily so that {@code tickMovement()} reads
 * movement intent from AltoClef tasks, not from keyboard state. Eliminates the
 * {@code KeyboardInput.tick()} overwrite race that made {@code KeyBinding.setPressed()}
 * unreliable.
 * <p>
 * Usage:
 * <pre>
 * ManualSteering.start();           // install — saves KeyboardInput for restore
 * ManualSteering.setForward(true);  // set desired movement
 * ManualSteering.setSneak(true);
 * // ... task runs for N ticks, tick() copies desired → playerInput each tick ...
 * ManualSteering.stop();            // restore KeyboardInput
 * </pre>
 */
public class ManualSteering extends Input {

    private static ManualSteering instance;
    private static Input savedInput;

    // Desired movement state — written by AltoClef tasks, read by tick()
    private boolean desiredForward;
    private boolean desiredBackward;
    private boolean desiredLeft;
    private boolean desiredRight;
    private boolean desiredJump;
    private boolean desiredSneak;
    private boolean desiredSprint;

    // --- Lifecycle ---

    /**
     * Install ManualSteering as the player's input handler.
     * Saves the current input (KeyboardInput) for restore.
     * <p>
     * If Baritone's ControlledInput is already installed (path execution active),
     * this is a no-op — Baritone owns the input layer during pathfinding.
     */
    public static void start() {
        ClientPlayerEntity player = getPlayer();
        if (player == null) return;

        // Don't override Baritone's ControlledInput during path execution
        if (isBaritoneControlled(player)) return;

        if (instance != null && player.input == instance) return; // already active

        savedInput = player.input;
        instance = new ManualSteering();
        player.input = instance;
    }

    /**
     * Restore the original input handler (KeyboardInput).
     * Zeros all desired movement state.
     */
    public static void stop() {
        ClientPlayerEntity player = getPlayer();
        if (player == null) return;

        if (instance != null && player.input == instance) {
            player.input = savedInput != null ? savedInput : new net.minecraft.client.input.KeyboardInput(MinecraftClient.getInstance().options);
            instance = null;
            savedInput = null;
        }
    }

    /** Is ManualSteering currently installed as the player's input? */
    public static boolean isActive() {
        ClientPlayerEntity player = getPlayer();
        return player != null && instance != null && player.input == instance;
    }

    // --- Desired state setters (called by AltoClef tasks each tick) ---

    public static void setForward(boolean pressed) {
        if (instance != null) instance.desiredForward = pressed;
    }

    public static void setBackward(boolean pressed) {
        if (instance != null) instance.desiredBackward = pressed;
    }

    public static void setLeft(boolean pressed) {
        if (instance != null) instance.desiredLeft = pressed;
    }

    public static void setRight(boolean pressed) {
        if (instance != null) instance.desiredRight = pressed;
    }

    public static void setJump(boolean pressed) {
        if (instance != null) instance.desiredJump = pressed;
    }

    public static void setSneak(boolean pressed) {
        if (instance != null) instance.desiredSneak = pressed;
    }

    public static void setSprint(boolean pressed) {
        if (instance != null) instance.desiredSprint = pressed;
    }

    /** Zero all desired movement state without uninstalling. */
    public static void clearAll() {
        if (instance != null) {
            instance.desiredForward = false;
            instance.desiredBackward = false;
            instance.desiredLeft = false;
            instance.desiredRight = false;
            instance.desiredJump = false;
            instance.desiredSneak = false;
            instance.desiredSprint = false;
        }
    }

    // --- Tick: copies desired state to fields that tickMovement() reads ---

    @Override
    public void tick() {
        this.playerInput = new PlayerInput(
                desiredForward, desiredBackward,
                desiredLeft, desiredRight,
                desiredJump, desiredSneak, desiredSprint
        );

        float forward = getMovementMultiplier(desiredForward, desiredBackward);
        float sideways = getMovementMultiplier(desiredLeft, desiredRight);
        if (desiredSneak) {
            forward *= 0.3f;
            sideways *= 0.3f;
        }
        this.movementVector = new Vec2f(sideways, forward);
    }

    // --- Internal ---

    private static float getMovementMultiplier(boolean positive, boolean negative) {
        if (positive == negative) return 0.0f;
        return positive ? 1.0f : -1.0f;
    }

    private static ClientPlayerEntity getPlayer() {
        return MinecraftClient.getInstance().player;
    }

    /**
     * Check if Baritone's ControlledInput is installed.
     * Uses class name check to avoid cross-module dependency (ControlledInput
     * is in emma-pathfinder with Mojmap naming, ManualSteering is in bridge mod
     * with Yarn naming).
     */
    private static boolean isBaritoneControlled(ClientPlayerEntity player) {
        return player.input != null
                && player.input.getClass().getName().contains("ControlledInput");
    }
}
