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

package emmatone.pathing.movement;

import emmatone.api.pathing.movement.MovementStatus;
import emmatone.api.utils.Rotation;
import emmatone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MovementState {

    /**
     * Typed interaction intents — each maps to exactly ONE Mojang API path.
     * No fallbacks. If an intent fails, fail loudly and fix the intent generation.
     */
    public sealed interface InteractionIntent {
        /** Break a block. API: clickBlock → onPlayerDamageBlock (multi-tick). */
        record BreakBlock(BlockPos pos) implements InteractionIntent {}
        /** Place a block against an adjacent surface. API: processRightClickBlock. */
        record PlaceBlock(BlockPos placeAt, BlockPos against, Direction face) implements InteractionIntent {}
        /** Interact with an existing block (open door/gate/water bucket). API: processRightClickBlock. */
        record InteractBlock(BlockPos target, Direction face) implements InteractionIntent {}
        /** Use held item without block target (eating, bows, pearls). API: processRightClick. */
        record UseItem() implements InteractionIntent {}
    }

    /** Pre-computed placement geometry from attemptToPlaceABlock(). */
    public record PlacementTarget(BlockPos placeAt, BlockPos against, Direction face) {}

    private MovementStatus status;
    private MovementTarget target = new MovementTarget();
    private final Map<Input, Boolean> inputState = new HashMap<>();
    private InteractionIntent interactionIntent;
    private PlacementTarget placementTarget;

    public MovementState setStatus(MovementStatus status) {
        this.status = status;
        return this;
    }

    public MovementStatus getStatus() {
        return status;
    }

    public MovementTarget getTarget() {
        return this.target;
    }

    public MovementState setTarget(MovementTarget target) {
        this.target = target;
        return this;
    }

    public MovementState setInput(Input input, boolean forced) {
        this.inputState.put(input, forced);
        return this;
    }

    public Map<Input, Boolean> getInputStates() {
        return this.inputState;
    }

    public MovementState setInteraction(InteractionIntent intent) {
        this.interactionIntent = intent;
        return this;
    }

    public InteractionIntent getInteraction() {
        return this.interactionIntent;
    }

    public void clearInteraction() {
        this.interactionIntent = null;
    }

    public MovementState setPlacementTarget(PlacementTarget target) {
        this.placementTarget = target;
        return this;
    }

    public PlacementTarget getPlacementTarget() {
        return this.placementTarget;
    }

    public static class MovementTarget {

        /**
         * Yaw and pitch angles that must be matched
         */
        public Rotation rotation;

        /**
         * Whether or not this target must force rotations.
         * <p>
         * {@code true} if we're trying to place or break blocks, {@code false} if we're trying to look at the movement location
         */
        private boolean forceRotations;

        public MovementTarget() {
            this(null, false);
        }

        public MovementTarget(Rotation rotation, boolean forceRotations) {
            this.rotation = rotation;
            this.forceRotations = forceRotations;
        }

        public final Optional<Rotation> getRotation() {
            return Optional.ofNullable(this.rotation);
        }

        public boolean hasToForceRotations() {
            return this.forceRotations;
        }
    }
}
