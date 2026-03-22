package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Random;

/**
 * Unstuck action: detects when Emma hasn't moved and takes random action to escape.
 *
 * Detection: position ring buffer — no significant movement for 100 ticks (~5 seconds).
 * Action: random movement, jump, break adjacent block.
 * Category: neutral
 */
public class UnstuckAction extends GoapAction {

    private static final int BUFFER_SIZE = 100;
    private static final double STUCK_THRESHOLD = 2.0; // blocks

    private final double[] posXBuffer = new double[BUFFER_SIZE];
    private final double[] posYBuffer = new double[BUFFER_SIZE];
    private final double[] posZBuffer = new double[BUFFER_SIZE];
    private int bufferIndex = 0;
    private boolean bufferFull = false;
    private boolean active = false;
    private int unstuckTicks = 0;
    private final Random random = new Random();

    @Override
    public String getName() {
        return "Unstuck";
    }

    @Override
    public String personalityCategory() {
        return "neutral";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        // Record position
        posXBuffer[bufferIndex] = state.posX;
        posYBuffer[bufferIndex] = state.posY;
        posZBuffer[bufferIndex] = state.posZ;
        bufferIndex = (bufferIndex + 1) % BUFFER_SIZE;
        if (bufferIndex == 0) bufferFull = true;

        if (!bufferFull) return false; // not enough data yet

        // Only consider "stuck" if Emmatone is actively pathing —
        // standing still with no navigation goal is idle, not stuck
        boolean emmatonePathing = EmmatoneAPI.getProvider().getPrimaryEmmatone()
                .getPathingBehavior().isPathing();
        if (!emmatonePathing) return false;

        return isStuck();
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        // Double-check Emmatone is actually pathing (not just idle)
        boolean emmatonePathing = EmmatoneAPI.getProvider().getPrimaryEmmatone()
                .getPathingBehavior().isPathing();
        if (!emmatonePathing || !isStuck()) return 0f;

        // Low but non-zero score — only wins when nothing else is working
        return 2.0f;
    }

    @Override
    public void execute(Minecraft client) {
        active = true;
        unstuckTicks = 0;
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        unstuckTicks++;

        // Phase 1: Random movement (ticks 0-20)
        if (unstuckTicks <= 20) {
            randomMove(player);
            return;
        }

        // Phase 2: Jump + forward (ticks 21-40)
        if (unstuckTicks <= 40) {
            DirectInput.setForward(true);
            DirectInput.setJumping(true);
            return;
        }

        // Phase 3: Break adjacent block (ticks 41-80)
        if (unstuckTicks <= 80) {
            breakAdjacentBlock(player, client);
            return;
        }

        // Phase 4: Done, reset buffer
        active = false;
        DirectInput.setForward(false);
        DirectInput.setJumping(false);
        DirectInput.setLeft(false);
        DirectInput.setRight(false);
        resetBuffer();
    }

    @Override
    public void onDeactivated(Minecraft client) {
        active = false;
        unstuckTicks = 0;
        DirectInput.setForward(false);
        DirectInput.setJumping(false);
        DirectInput.setLeft(false);
        DirectInput.setRight(false);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    private boolean isStuck() {
        if (!bufferFull) return false;

        // Compare oldest position with newest
        int oldestIndex = bufferIndex; // oldest is at current write position (circular)
        int newestIndex = (bufferIndex - 1 + BUFFER_SIZE) % BUFFER_SIZE;

        double dx = posXBuffer[newestIndex] - posXBuffer[oldestIndex];
        double dy = posYBuffer[newestIndex] - posYBuffer[oldestIndex];
        double dz = posZBuffer[newestIndex] - posZBuffer[oldestIndex];

        double totalDisplacement = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return totalDisplacement < STUCK_THRESHOLD;
    }

    private void randomMove(LocalPlayer player) {
        int dir = random.nextInt(4);
        DirectInput.setForward(dir == 0);
        DirectInput.setBack(dir == 1);
        DirectInput.setLeft(dir == 2);
        DirectInput.setRight(dir == 3);

        // Occasional jumps
        DirectInput.setJumping(random.nextFloat() < 0.3f);
    }

    private void breakAdjacentBlock(LocalPlayer player, Minecraft client) {
        BlockPos feet = player.blockPosition();
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        for (Direction dir : dirs) {
            BlockPos adjacent = feet.relative(dir);
            BlockState state = player.level().getBlockState(adjacent);
            if (!state.isAir() && state.getDestroySpeed(player.level(), adjacent) >= 0) {
                // Break this block
                BlockInteraction.startBreaking(adjacent, dir.getOpposite());
                return;
            }
            // Also check one above (head level)
            BlockPos aboveAdjacent = adjacent.above();
            BlockState aboveState = player.level().getBlockState(aboveAdjacent);
            if (!aboveState.isAir() && aboveState.getDestroySpeed(player.level(), aboveAdjacent) >= 0) {
                BlockInteraction.startBreaking(aboveAdjacent, dir.getOpposite());
                return;
            }
        }
    }

    private void resetBuffer() {
        bufferIndex = 0;
        bufferFull = false;
    }
}
