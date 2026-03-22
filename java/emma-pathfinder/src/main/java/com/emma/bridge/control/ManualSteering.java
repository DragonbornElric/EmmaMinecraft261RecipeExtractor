package com.emma.bridge.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Lightweight Input subclass for sustained manual movement outside Emmatone pathfinding.
 * <p>
 * Same architecture as {@code ControlledInput} in emma-pathfinder (Mojmap side):
 * replaces {@code KeyboardInput} temporarily so that {@code tickMovement()} reads
 * movement intent from GOAP actions, not from keyboard state. Eliminates the
 * {@code KeyboardInput.tick()} overwrite race that made {@code KeyBinding.setDown()}
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
public class ManualSteering extends ClientInput {

    private static ManualSteering instance;
    private static ClientInput savedInput;

    // Desired movement state — written by GOAP actions, read by tick()
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
     * If Emmatone's ControlledInput is already installed (path execution active),
     * this is a no-op — Emmatone owns the input layer during pathfinding.
     */
    public static void start() {
        LocalPlayer player = getPlayer();
        if (player == null) return;

        // Don't override Emmatone's ControlledInput during path execution
        if (isEmmatoneControlled(player)) return;

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
        LocalPlayer player = getPlayer();
        if (player == null) return;

        if (instance != null && player.input == instance) {
            player.input = savedInput != null ? savedInput : new net.minecraft.client.player.KeyboardInput(Minecraft.getInstance().options);
            instance = null;
            savedInput = null;
        }
    }

    /** Is ManualSteering currently installed as the player's input? */
    public static boolean isActive() {
        LocalPlayer player = getPlayer();
        return player != null && instance != null && player.input == instance;
    }

    // --- Desired state setters (called by GOAP actions each tick) ---

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
        this.keyPresses = new Input(
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
        this.moveVector = new Vec2(sideways, forward);
    }

    // --- Internal ---

    private static float getMovementMultiplier(boolean positive, boolean negative) {
        if (positive == negative) return 0.0f;
        return positive ? 1.0f : -1.0f;
    }

    private static LocalPlayer getPlayer() {
        return Minecraft.getInstance().player;
    }

    /**
     * Check if Emmatone's ControlledInput is installed.
     * Uses class name check to avoid cross-module dependency (ControlledInput
     * is in emma-pathfinder with Mojmap naming, ManualSteering is in bridge mod
     * with Yarn naming).
     */
    private static boolean isEmmatoneControlled(LocalPlayer player) {
        return player.input != null
                && player.input.getClass().getName().contains("ControlledInput");
    }
}
