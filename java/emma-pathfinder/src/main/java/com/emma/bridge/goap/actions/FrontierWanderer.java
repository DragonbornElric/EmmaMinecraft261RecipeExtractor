package com.emma.bridge.goap.actions;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;

/**
 * Drives long-leg exploration that keeps pushing the loaded chunk frontier in a
 * stable bearing until resources appear or the bearing repeatedly stalls out.
 */
public final class FrontierWanderer {

    private static final int LEG_TIMEOUT_TICKS = 900;
    private static final int SETTLE_TICKS = 40;
    private static final int MIN_PROGRESS_CHUNKS = 2;
    private static final int MAX_STALLED_LEGS_PER_BEARING = 2;
    private static final int MIN_LOOKAHEAD_CHUNKS = 4;
    private static final double ARRIVAL_DISTANCE = 10.0;

    public enum StepResult {
        IDLE,
        PATHING,
        SETTLING,
        NEW_LEG,
        ROTATED_BEARING,
        ARRIVED
    }

    public enum Bearing {
        NORTH(0, -1),
        NORTH_EAST(1, -1),
        EAST(1, 0),
        SOUTH_EAST(1, 1),
        SOUTH(0, 1),
        SOUTH_WEST(-1, 1),
        WEST(-1, 0),
        NORTH_WEST(-1, -1);

        final int dx;
        final int dz;

        Bearing(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        Bearing rotateClockwise() {
            Bearing[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        static Bearing random(RandomSource random) {
            Bearing[] values = values();
            return values[random.nextInt(values.length)];
        }
    }

    private final RandomSource random = RandomSource.create();

    private boolean active;
    private Bearing bearing;
    private BlockPos currentTarget;
    private int legTicks;
    private int settleTicksRemaining;
    private int stalledLegsOnBearing;
    private int legStartChunkX;
    private int legStartChunkZ;

    public void start(LocalPlayer player) {
        if (player == null) {
            return;
        }
        active = true;
        settleTicksRemaining = 0;
        stalledLegsOnBearing = 0;
        if (bearing == null) {
            bearing = Bearing.random(random);
        }
        issueNextLeg(player);
    }

    public void reset() {
        active = false;
        currentTarget = null;
        legTicks = 0;
        settleTicksRemaining = 0;
        stalledLegsOnBearing = 0;
    }

    public boolean isActive() {
        return active;
    }

    public Bearing getBearing() {
        return bearing;
    }

    public BlockPos getCurrentTarget() {
        return currentTarget;
    }

    public StepResult tick(LocalPlayer player) {
        if (!active || player == null) {
            return StepResult.IDLE;
        }

        if (currentTarget == null) {
            issueNextLeg(player);
            return StepResult.NEW_LEG;
        }

        if (settleTicksRemaining > 0) {
            settleTicksRemaining--;
            if (settleTicksRemaining == 0) {
                issueNextLeg(player);
                return StepResult.NEW_LEG;
            }
            return StepResult.SETTLING;
        }

        legTicks++;
        return switch (GoapNavHelper.tickNavigateToXZ(
                player,
                currentTarget.getX(),
                currentTarget.getZ(),
                legTicks,
                LEG_TIMEOUT_TICKS,
                ARRIVAL_DISTANCE)) {
            case ARRIVED -> {
                settleTicksRemaining = SETTLE_TICKS;
                stalledLegsOnBearing = 0;
                yield StepResult.ARRIVED;
            }
            case TIMEOUT -> {
                if (madeMeaningfulProgress(player)) {
                    stalledLegsOnBearing = 0;
                    issueNextLeg(player);
                    yield StepResult.NEW_LEG;
                }

                stalledLegsOnBearing++;
                if (stalledLegsOnBearing >= MAX_STALLED_LEGS_PER_BEARING) {
                    bearing = bearing.rotateClockwise();
                    stalledLegsOnBearing = 0;
                    issueNextLeg(player);
                    yield StepResult.ROTATED_BEARING;
                }

                issueNextLeg(player);
                yield StepResult.NEW_LEG;
            }
            case NO_TARGET -> {
                issueNextLeg(player);
                yield StepResult.NEW_LEG;
            }
            case PATHING -> StepResult.PATHING;
        };
    }

    private boolean madeMeaningfulProgress(LocalPlayer player) {
        ChunkPos currentChunk = new ChunkPos(player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
        int deltaChunkX = currentChunk.x() - legStartChunkX;
        int deltaChunkZ = currentChunk.z() - legStartChunkZ;
        int signedProgress = deltaChunkX * bearing.dx + deltaChunkZ * bearing.dz;
        return signedProgress >= Math.max(MIN_PROGRESS_CHUNKS, GoapNavHelper.getLoadedChunkRadius() / 3);
    }

    private void issueNextLeg(LocalPlayer player) {
        ChunkPos chunkCenter = GoapNavHelper.getLoadedChunkCenter(player);
        int radius = GoapNavHelper.getLoadedChunkRadius();
        int lookahead = Math.max(MIN_LOOKAHEAD_CHUNKS, radius / 2);
        int legDistanceChunks = radius + lookahead;
        int targetChunkX = chunkCenter.x() + bearing.dx * legDistanceChunks;
        int targetChunkZ = chunkCenter.z() + bearing.dz * legDistanceChunks;

        currentTarget = new BlockPos((targetChunkX << 4) + 8, player.blockPosition().getY(), (targetChunkZ << 4) + 8);
        legStartChunkX = chunkCenter.x();
        legStartChunkZ = chunkCenter.z();
        legTicks = 0;

        GoapNavHelper.pathToXZ(currentTarget.getX(), currentTarget.getZ());
    }
}