/*
 * This file is part of Emmatone.
 *
 * Emmatone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Emmatone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Emmatone.  If not, see <https://www.gnu.org/licenses/>.
 */

package emmatone.utils;

import emmatone.api.utils.input.Input;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

/**
 * Custom ClientInput that replaces KeyboardInput during Emmatone path execution.
 * <p>
 * KeyboardInput reads keyboard state each tick. ControlledInput ignores keyboard
 * state entirely — its tick() reads movement intent from Emmatone's MovementState,
 * eliminating the tick-ordering race with KeyboardInput.tick() that caused stuck
 * virtual input state in the old InputOverrideHandler approach.
 * <p>
 * This is the same pattern Mojang uses for mob movement: MoveControl sets
 * forwardSpeed on the entity's own input layer, not by patching KeyBinding.
 * <p>
 * Proven in navtest/ POC. See navtest/src/main/java/com/emma/navtest/ControlledInput.java.
 */
public class ControlledInput extends ClientInput {

    /**
     * All-false input record — no movement, no jump, no sneak, no sprint.
     * Uses Mojang's built-in EMPTY constant.
     */
    public static final net.minecraft.world.entity.player.Input NONE =
            net.minecraft.world.entity.player.Input.EMPTY;

    private net.minecraft.world.entity.player.Input desired = NONE;

    /**
     * Set the desired input state. Called by Movement.update() each tick
     * with the movement intents from the current MovementState.
     */
    public void setDesired(net.minecraft.world.entity.player.Input desired) {
        this.desired = desired;
    }

    /**
     * Check whether a specific Emmatone input is currently desired.
     * Used by PathExecutor.shouldSprintNextTick() to read sprint state.
     */
    public boolean isDesired(Input input) {
        return switch (input) {
            case MOVE_FORWARD -> desired.forward();
            case MOVE_BACK -> desired.backward();
            case MOVE_LEFT -> desired.left();
            case MOVE_RIGHT -> desired.right();
            case JUMP -> desired.jump();
            case SNEAK -> desired.shift();
            case SPRINT -> desired.sprint();
            default -> false;
        };
    }

    /**
     * Override a single input field without reconstructing the entire desired state.
     * Used by PathExecutor.shouldSprintNextTick() to manage sprint/jump overrides.
     */
    public void overrideInput(Input input, boolean value) {
        boolean fwd = desired.forward(), back = desired.backward(),
                left = desired.left(), right = desired.right(),
                jump = desired.jump(), sneak = desired.shift(), sprint = desired.sprint();
        switch (input) {
            case MOVE_FORWARD -> fwd = value;
            case MOVE_BACK -> back = value;
            case MOVE_LEFT -> left = value;
            case MOVE_RIGHT -> right = value;
            case JUMP -> jump = value;
            case SNEAK -> sneak = value;
            case SPRINT -> sprint = value;
            default -> { return; }
        }
        this.desired = new net.minecraft.world.entity.player.Input(fwd, back, left, right, jump, sneak, sprint);
    }

    /**
     * Called by the game's tick loop during tickMovement(). Copies desired
     * intent to the fields that travel() reads. No keyboard state consulted.
     */
    @Override
    public void tick() {
        this.keyPresses = desired;
        float forward = getMovementMultiplier(desired.forward(), desired.backward());
        float sideways = getMovementMultiplier(desired.left(), desired.right());
        if (desired.shift()) {
            forward *= 0.3f;
            sideways *= 0.3f;
        }
        this.moveVector = new Vec2(sideways, forward);
    }

    private static float getMovementMultiplier(boolean positive, boolean negative) {
        if (positive == negative) return 0.0f;
        return positive ? 1.0f : -1.0f;
    }
}
