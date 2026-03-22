package com.emma.bridge.schematic;

import emmatone.api.schematic.AbstractSchematic;
import com.emma.bridge.EmmaBridgeMod;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A concrete Emmatone ISchematic backed by a 3D BlockState array.
 *
 * <p>Used by {@link GuideSchematicAdapter} to pass build guide data from the
 * Python BuildDB directly to Emmatone's BuilderProcess without needing
 * .schematic files on disk.</p>
 *
 * <p>Array layout: stateArray[x][z][y] — matches Emmatone's convention.</p>
 */
public class GuideSchematic extends AbstractSchematic {

    private final BlockState[][][] states;
    private int desiredStateCallCount = 0;

    /**
     * @param states  3D array of BlockState, indexed [x][z][y]
     * @param sizeX   Width (X dimension)
     * @param sizeY   Height (Y dimension)
     * @param sizeZ   Length (Z dimension)
     */
    public GuideSchematic(BlockState[][][] states, int sizeX, int sizeY, int sizeZ) {
        super(sizeX, sizeY, sizeZ);
        this.states = states;
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState currentState, List<BlockState> approxPlaceable) {
        if (x < 0 || x >= widthX() || z < 0 || z >= lengthZ() || y < 0 || y >= heightY()) {
            return currentState; // Out of bounds — don't touch
        }

        desiredStateCallCount++;
        BlockState desired = states[x][z][y];

        // BLOCK-TYPE MATCHING: Even though schematics provide full state data (facing,
        // half, etc.), some properties are auto-computed by Minecraft at placement time
        // (stair shape from neighbors, fence connections, waterlogged, etc.) and will
        // never match the schematic's default values. Block-type matching prevents
        // Emmatone from breaking and re-placing blocks in an infinite loop.
        // Correct facing is ensured at placement time via yaw override in BuilderProcess.
        if (!desired.isAir() && currentState.getBlock() == desired.getBlock()) {
            return currentState; // Right block type — tell Emmatone it's correct
        }

        // Log first few calls to verify Emmatone is actually querying us
        if (desiredStateCallCount <= 5) {
            EmmaBridgeMod.LOGGER.info("[Schematic] desiredState({},{},{}) = {} (current={}, approxPlaceable={})",
                    x, y, z, desired, currentState, approxPlaceable.size());
        } else if (desiredStateCallCount == 6) {
            EmmaBridgeMod.LOGGER.info("[Schematic] (suppressing further desiredState logs...)");
        }

        return desired;
    }

    /**
     * Validate and log the schematic contents.
     * Call after construction to verify block data is correct.
     */
    public void validateAndLog() {
        int air = 0, nonAir = 0;
        Map<String, Integer> blockCounts = new HashMap<>();

        for (int x = 0; x < widthX(); x++) {
            for (int z = 0; z < lengthZ(); z++) {
                for (int y = 0; y < heightY(); y++) {
                    BlockState state = states[x][z][y];
                    if (state.isAir()) {
                        air++;
                    } else {
                        nonAir++;
                        String name = state.getBlock().toString();
                        blockCounts.merge(name, 1, Integer::sum);
                    }
                }
            }
        }

        EmmaBridgeMod.LOGGER.info("[Schematic] Validation: {} non-air blocks, {} air, dims={}x{}x{}",
                nonAir, air, widthX(), heightY(), lengthZ());

        // Log block type breakdown
        blockCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8)
                .forEach(e -> EmmaBridgeMod.LOGGER.info("[Schematic]   {} x{}", e.getKey(), e.getValue()));

        if (nonAir == 0) {
            EmmaBridgeMod.LOGGER.error("[Schematic] WARNING: Schematic contains ZERO non-air blocks!");
        }
    }
}
