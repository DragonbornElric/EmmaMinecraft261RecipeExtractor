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

package emmatone.process;

import emmatone.api.utils.BetterBlockPos;
import emmatone.pathing.movement.MovementHelper;
import emmatone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Structurally-ordered placement candidate selector for BuilderProcess.
 * <p>
 * Replaces the old proximity-based scan (searchForPlacables 5-block radius)
 * with bottom-up, support-aware ordering:
 * <ul>
 *   <li>Y ascending (foundation first)</li>
 *   <li>Distance to player as tiebreaker</li>
 *   <li>Deferred blocks wait until their support is placed</li>
 *   <li>Frontier expansion: placing a block re-checks its 6 neighbors</li>
 * </ul>
 */
public class PlacementOrderer {

    private static final Direction[] ALL_DIRECTIONS = Direction.values();

    private final Level world;

    /** Blocks that have support and are ready to place, ordered by priority. */
    private TreeSet<BetterBlockPos> readyCandidates;

    /** Blocks waiting for support (torches, doors, rails, etc.). */
    private final Set<BetterBlockPos> deferredBlocks = new HashSet<>();

    /** Current player position used for distance tiebreaker in comparator. */
    private BetterBlockPos playerPos;

    /** Whether a rebuild is needed before the next getCandidates() call. */
    private boolean dirty = true;

    public PlacementOrderer(Level world) {
        this.world = world;
        this.playerPos = BetterBlockPos.ORIGIN;
        this.readyCandidates = createTreeSet(playerPos);
    }

    private static TreeSet<BetterBlockPos> createTreeSet(BetterBlockPos playerPos) {
        return new TreeSet<>((a, b) -> {
            // Y ascending — foundation first
            if (a.y != b.y) return Integer.compare(a.y, b.y);
            // Distance to player as tiebreaker
            double distA = a.distSqr(playerPos);
            double distB = b.distSqr(playerPos);
            int distCmp = Double.compare(distA, distB);
            if (distCmp != 0) return distCmp;
            // Stable tiebreaker: X then Z
            if (a.x != b.x) return Integer.compare(a.x, b.x);
            return Integer.compare(a.z, b.z);
        });
    }

    /**
     * Rebuild the ready/deferred sets from the current incorrect positions.
     *
     * @param incorrectPositions all positions that need correction
     * @param bsi                block state interface for world queries
     * @param approxPlaceable    player's current placeable inventory
     * @param getSchematic       function to get desired state for a position
     */
    public void rebuild(
            Set<BetterBlockPos> incorrectPositions,
            BlockStateInterface bsi,
            List<BlockState> approxPlaceable,
            SchematicQuery getSchematic
    ) {
        readyCandidates = createTreeSet(playerPos);
        deferredBlocks.clear();

        for (BetterBlockPos pos : incorrectPositions) {
            BlockState current = bsi.get0(pos);
            // Only consider air positions that need a block placed
            if (!(current.getBlock() instanceof AirBlock)) {
                continue;
            }
            BlockState desired = getSchematic.getDesired(pos.x, pos.y, pos.z, current);
            if (desired == null || desired.getBlock() instanceof AirBlock) {
                continue;
            }
            // Check if player has the block
            if (!containsBlockState(approxPlaceable, desired)) {
                continue;
            }

            if (hasPlacementSupport(pos, desired, bsi)) {
                readyCandidates.add(pos);
            } else {
                deferredBlocks.add(pos);
            }
        }
        dirty = false;
    }

    /**
     * Check whether a block at pos has adequate support to be placed.
     * Two conditions:
     * 1. BlockState.canSurvive() — Minecraft's own support validation
     * 2. At least one adjacent non-replaceable block (face to click against)
     */
    public boolean hasPlacementSupport(BetterBlockPos pos, BlockState desired, BlockStateInterface bsi) {
        // Condition 1: Minecraft's block survival check
        if (!desired.canSurvive(world, pos)) {
            return false;
        }
        // Condition 2: at least one solid adjacent face to click against
        for (Direction dir : ALL_DIRECTIONS) {
            BetterBlockPos neighbor = new BetterBlockPos(pos.relative(dir));
            BlockState neighborState = bsi.get0(neighbor);
            if (!MovementHelper.isReplaceable(neighbor.x, neighbor.y, neighbor.z, neighborState, bsi)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called after a block is successfully placed. Re-checks the 6 neighbors
     * to see if any deferred blocks are now supported.
     */
    public void onBlockPlaced(BetterBlockPos pos, BlockStateInterface bsi, SchematicQuery getSchematic) {
        readyCandidates.remove(pos);
        deferredBlocks.remove(pos);

        for (Direction dir : ALL_DIRECTIONS) {
            BetterBlockPos neighbor = new BetterBlockPos(pos.relative(dir));
            if (deferredBlocks.contains(neighbor)) {
                BlockState current = bsi.get0(neighbor);
                BlockState desired = getSchematic.getDesired(neighbor.x, neighbor.y, neighbor.z, current);
                if (desired != null && hasPlacementSupport(neighbor, desired, bsi)) {
                    deferredBlocks.remove(neighbor);
                    readyCandidates.add(neighbor);
                }
            }
        }
    }

    /**
     * Return priority-ordered placement candidates (up to maxCount).
     */
    public List<BetterBlockPos> getCandidates(BetterBlockPos playerPos, int maxCount) {
        this.playerPos = playerPos;
        List<BetterBlockPos> result = new ArrayList<>(Math.min(maxCount, readyCandidates.size()));
        int count = 0;
        for (BetterBlockPos pos : readyCandidates) {
            if (count >= maxCount) break;
            result.add(pos);
            count++;
        }
        return result;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public int readyCount() {
        return readyCandidates.size();
    }

    public int deferredCount() {
        return deferredBlocks.size();
    }

    /**
     * Update player position for distance-based comparator.
     * Rebuilds the TreeSet with the new comparator.
     */
    public void updatePlayerPos(BetterBlockPos newPlayerPos) {
        if (!newPlayerPos.equals(this.playerPos)) {
            this.playerPos = newPlayerPos;
            // Re-sort: transfer all elements to a new TreeSet with updated comparator
            TreeSet<BetterBlockPos> newSet = createTreeSet(newPlayerPos);
            newSet.addAll(readyCandidates);
            readyCandidates = newSet;
        }
    }

    private static boolean containsBlockState(List<BlockState> states, BlockState desired) {
        for (BlockState s : states) {
            if (s.equals(desired)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Functional interface for querying the schematic's desired state at a position.
     */
    @FunctionalInterface
    public interface SchematicQuery {
        BlockState getDesired(int x, int y, int z, BlockState current);
    }
}
