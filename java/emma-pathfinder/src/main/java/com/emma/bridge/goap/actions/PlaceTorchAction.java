package com.emma.bridge.goap.actions;

import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.InventoryScanner;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * GOAP Action: Place torches in dark areas.
 *
 * Preconditions: has torches + light level is low
 * Score: be_lit goal priority x darkness urgency
 *
 * Uses direct block interaction to place torches:
 *   1. Count torches in inventory
 *   2. Equip torch via BlockInteraction.forceEquipItem
 *   3. Right-click on a solid surface below/beside player
 */
public class PlaceTorchAction extends GoapAction {

    /** Default light level at which placement activates (mob spawn threshold). */
    private static final int DEFAULT_darkThreshold = 3;

    /** Light level at which score spikes (pitch dark). */
    private static final int DANGER_THRESHOLD = 1;

    /** Cooldown between torch placements (ticks). */
    private static final int PLACE_COOLDOWN = 40;

    private boolean enabled = true;
    private int darkThreshold = DEFAULT_darkThreshold;
    private boolean active = false;
    private int cooldownTimer = 0;
    /** Ticks since equip — place after 2 ticks for server sync. */
    private int equipWaitTicks = 0;

    @Override
    public String getName() {
        return "PlaceTorch";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return enabled && countTorches() > 0 && state.lightLevel <= darkThreshold;
    }

    // ── Public accessors for TorchHandler ──────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getDarkThreshold() { return darkThreshold; }
    public void setDarkThreshold(int threshold) { this.darkThreshold = threshold; }
    public int getTorchCount() { return countTorches(); }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (state.lightLevel > darkThreshold) return 0;
        if (countTorches() <= 0) return 0;

        float goalPriority = goals.getGoal("be_lit")
                .map(g -> g.priority)
                .orElse(5.0f);

        // Urgency: 0.0 at threshold, 1.0 at complete darkness
        float urgency = 1.0f - ((float) state.lightLevel / darkThreshold);

        // Spike when at mob-spawn danger level
        if (state.lightLevel <= DANGER_THRESHOLD) {
            urgency = Math.max(urgency, 0.8f);
        }

        return goalPriority * urgency;
    }

    @Override
    public void execute(Minecraft client) {
        active = true;
        cooldownTimer = 0;
        equipWaitTicks = 0;
        equipTorch();  // equip on first tick, place after 2-tick delay
    }

    @Override
    public void tick(Minecraft client) {
        if (!active) return;

        // Waiting for server to sync held item after equip
        if (equipWaitTicks > 0) {
            if (++equipWaitTicks >= 3) {  // 2 ticks after equip
                equipWaitTicks = 0;
                placeTorch(client);
                cooldownTimer = 0;
            }
            return;
        }

        cooldownTimer++;
        if (cooldownTimer >= PLACE_COOLDOWN) {
            cooldownTimer = 0;
            equipTorch();  // equip this tick, place after delay
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        active = false;
        cooldownTimer = 0;
        equipWaitTicks = 0;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String getPrimaryGoalId() {
        return "be_lit";
    }

    @Override
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        if ("survive".equals(goal.id)) return 0.2f; // light prevents mob spawns
        return 0.0f;
    }

    @Override
    public String personalityCategory() {
        return "neutral";  // torch placement is survival, not personality-gated
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("active", active);
        bd.addProperty("light_level", state.lightLevel);
        bd.addProperty("torch_count", countTorches());
        return bd;
    }

    // -- Torch placement --------------------------------------------------

    /** Equip torch this tick — starts 2-tick wait for server sync. */
    private void equipTorch() {
        if (BlockInteraction.forceEquipItem(Items.TORCH)
                || BlockInteraction.forceEquipItem(Items.SOUL_TORCH)) {
            equipWaitTicks = 1;  // tick() increments to 3 before placing
        }
    }

    /** Place the torch — called one tick after equip so the server knows what we're holding. */
    private void placeTorch(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;

        // Verify we're still holding a torch (another action may have swapped)
        ItemStack held = player.getMainHandItem();
        if (!held.is(Items.TORCH) && !held.is(Items.SOUL_TORCH)) {
            equipTorch();  // re-equip, try again next tick
            return;
        }

        BlockPos feet = player.blockPosition();
        BlockPos below = feet.below();

        // Prefer placing on the block below (floor torch)
        if (client.level.getBlockState(below).isSolidRender()
                && client.level.getBlockState(feet).isAir()) {
            BlockInteraction.rightClickBlock(below, Direction.UP);
            return;
        }

        // Try placing on adjacent walls
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos wallBlock = feet.relative(dir);
            if (client.level.getBlockState(wallBlock).isSolidRender()
                    && client.level.getBlockState(feet).isAir()) {
                BlockInteraction.rightClickBlock(wallBlock, dir.getOpposite());
                return;
            }
        }
    }

    private int countTorches() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;
        return InventoryScanner.countItems(client.player.getInventory(),
                stack -> stack.is(Items.TORCH) || stack.is(Items.SOUL_TORCH));
    }
}
