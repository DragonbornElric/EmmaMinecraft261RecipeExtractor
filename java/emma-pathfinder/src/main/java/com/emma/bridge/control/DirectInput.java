package com.emma.bridge.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Direct Mojang API wrappers for game-mechanic inputs.
 * <p>
 * Movement {@code KeyBinding.setDown()} calls eliminated.
 * Item-use hold ({@code useKey}) consolidated into {@link #setUseHeld(boolean)}.
 * <ul>
 *   <li>State setters (sneak, sprint) → {@code player.setShiftKeyDown()} / {@code player.setSprinting()}</li>
 *   <li>Sustained movement (forward/back/left/right/jump) → {@link ManualSteering}</li>
 * </ul>
 * <p>
 * Movement during Emmatone pathfinding is handled by ControlledInput (in emma-pathfinder).
 * Click interactions use {@code interactionManager.*} APIs directly at each call site.
 */
public class DirectInput {

    // --- State setters (direct entity state, no KeyBinding) ---

    /**
     * Set sneaking state directly on the player entity.
     * For set-call-clear pattern: call before interactBlock(), restore after.
     * For sustained sneak: call each tick (or use ManualSteering).
     */
    public static void setSneaking(boolean sneaking) {
        LocalPlayer player = getPlayer();
        if (player != null) player.setShiftKeyDown(sneaking);
    }

    /**
     * Set sprinting state directly on the player entity.
     */
    public static void setSprinting(boolean sprinting) {
        LocalPlayer player = getPlayer();
        if (player != null) player.setSprinting(sprinting);
    }

    // --- State queries (read entity state, not key state) ---

    public static boolean isSneaking() {
        LocalPlayer player = getPlayer();
        return player != null && player.isShiftKeyDown();
    }

    public static boolean isJumping() {
        // ManualSteering tracks jump state; fall back to checking entity jumping field
        if (ManualSteering.isActive()) {
            // Query desired state from ManualSteering
            return player().input.keyPresses.jump();
        }
        LocalPlayer player = getPlayer();
        return player != null && player.input.keyPresses.jump();
    }

    // --- Sustained movement (delegate to ManualSteering) ---

    /**
     * Set forward movement. Auto-starts ManualSteering if not active.
     * Call with false to stop forward movement.
     */
    public static void setForward(boolean pressed) {
        ensureSteering(pressed);
        ManualSteering.setForward(pressed);
    }

    public static void setBack(boolean pressed) {
        ensureSteering(pressed);
        ManualSteering.setBackward(pressed);
    }

    public static void setLeft(boolean pressed) {
        ensureSteering(pressed);
        ManualSteering.setLeft(pressed);
    }

    public static void setRight(boolean pressed) {
        ensureSteering(pressed);
        ManualSteering.setRight(pressed);
    }

    /**
     * Set jump state via ManualSteering. Required for swimming (tickMovement reads
     * input.input.jump()) and elytra deployment.
     * For one-shot ground jump, prefer player.jump() directly.
     */
    public static void setJumping(boolean pressed) {
        ensureSteering(pressed);
        ManualSteering.setJump(pressed);
    }

    /**
     * Press forward for one tick. Starts ManualSteering, sets forward=true.
     * Caller is responsible for stopping (setForward(false) or ManualSteering.stop()).
     */
    public static void pressForwardOnce() {
        ManualSteering.start();
        ManualSteering.setForward(true);
    }

    // --- Item use (hold right-click) ---

    /**
     * Hold or release the "use item" action (eating, bow draw, shield block, bucket).
     * This is a sustained hold — Minecraft's tick loop checks the flag each tick
     * to continue the item use charge-up. Not a keypress simulation.
     */
    public static void setUseHeld(boolean held) {
        Minecraft.getInstance().options.keyUse.setDown(held);
    }

    // --- Internal ---

    /**
     * Auto-start ManualSteering when setting a sustained input to true.
     * Does NOT auto-stop when setting to false — caller must explicitly
     * call ManualSteering.stop() when all movement is done.
     */
    private static void ensureSteering(boolean pressed) {
        if (pressed && !ManualSteering.isActive()) {
            ManualSteering.start();
        }
    }

    private static LocalPlayer getPlayer() {
        return Minecraft.getInstance().player;
    }

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }
}
