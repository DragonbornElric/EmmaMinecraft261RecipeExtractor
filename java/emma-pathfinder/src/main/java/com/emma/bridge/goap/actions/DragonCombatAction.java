package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.ItemStack;
import com.emma.bridge.util.CombatHelper;
import com.emma.bridge.util.InventoryScanner;
import com.emma.bridge.util.ItemClassifier;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * GOAP Action: Kill the ender dragon using bed explosions or melee.
 *
 * Strategy:
 *   - Primary: Bed bomb — place bed on exit portal obsidian, detonate when dragon
 *     perches. Beds explode in the End dealing massive damage (~100 per explosion).
 *   - Fallback: Melee sword — sprint-attack dragon's head when it perches on the
 *     exit portal.
 *
 * AltoClef reference: KillEnderDragonWithBedsTask + OneCycleTask (bed bomb timing),
 * PunkEnderDragonTask (melee perch attack).
 *
 * Scores on "kill_ender_dragon" goal type. Returns 0 if dragon is not alive.
 */
public class DragonCombatAction extends GoapAction {

    private enum Phase {
        SETUP,          // Navigate to exit portal area
        WAIT_PERCH,     // Wait for dragon to begin perching
        PLACE_BED,      // Place bed on portal surface
        DETONATE,       // Right-click bed to explode it
        MELEE_APPROACH, // Sword fallback: get close to dragon head
        MELEE_ATTACK,   // Sword fallback: swing at dragon
        RETREAT,        // Back off after explosion/attack
        DONE
    }

    // Exit portal center (bedrock fountain) at Y=64
    private static final BlockPos PORTAL_CENTER = new BlockPos(0, 64, 0);
    // Bed placement surface: north face of portal pillar
    private static final BlockPos BED_PLACE_POS = new BlockPos(0, 64, -1);
    // Where to stand during bed bomb
    private static final BlockPos STAND_POS = new BlockPos(0, 64, -3);

    private static final double PERCH_DETECTION_RANGE = 10.0;
    private static final double BED_DETONATION_RANGE = 4.0;
    private static final double MELEE_RANGE = 4.5;

    private Phase phase = Phase.DONE;
    private String targetGoalId;
    private int waitTicks;
    private int bedsUsed;
    private boolean useBedStrategy;

    // Track the bed position after placing
    private BlockPos placedBedPos;

    @Override
    public String getName() {
        return "DragonCombat";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return state.dimension.contains("the_end") && state.dragonAlive;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (!state.dragonAlive) return 0;
        // Don't fight dragon while crystals are healing it
        if (state.endCrystalCount > 0) return 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if ("kill_ender_dragon".equals(goal.type)) {
                targetGoalId = goal.id;
                return goal.priority * 0.95f;
            }
        }
        return 0;
    }

    @Override
    public void execute(Minecraft client) {
        phase = Phase.SETUP;
        waitTicks = 0;
        bedsUsed = 0;
        placedBedPos = null;

        // Decide strategy based on inventory
        useBedStrategy = hasBed(client.player);

        EmmaBridgeMod.LOGGER.info("DragonCombat: starting, strategy={}", useBedStrategy ? "bed_bomb" : "melee");
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (player == null || world == null) return;
        waitTicks++;

        // Check if dragon is dead
        if (!isDragonAlive(player, world)) {
            EmmaBridgeMod.LOGGER.info("DragonCombat: dragon is dead! ({} beds used)", bedsUsed);
            phase = Phase.DONE;
            return;
        }

        switch (phase) {
            case SETUP -> tickSetup(player, world);
            case WAIT_PERCH -> tickWaitPerch(player, world);
            case PLACE_BED -> tickPlaceBed(player, world);
            case DETONATE -> tickDetonate(player, world);
            case MELEE_APPROACH -> tickMeleeApproach(player, world);
            case MELEE_ATTACK -> tickMeleeAttack(player, world);
            case RETREAT -> tickRetreat(player);
            case DONE -> {}
        }
    }

    // ── Setup: Navigate to exit portal area ──────────────────────

    private void tickSetup(LocalPlayer player, ClientLevel world) {
        double dist = player.position().distanceTo(Vec3.atCenterOf(PORTAL_CENTER));

        if (dist < 8.0) {
            GoapNavHelper.cancelPathing();
            if (useBedStrategy) {
                phase = Phase.WAIT_PERCH;
            } else {
                phase = Phase.WAIT_PERCH;
            }
            waitTicks = 0;
            EmmaBridgeMod.LOGGER.info("DragonCombat: at portal, waiting for perch");
            return;
        }

        GoapNavHelper.NavResult result = GoapNavHelper.tickNavigateToBlock(
                player, STAND_POS, waitTicks, 400, 5.0);

        switch (result) {
            case ARRIVED -> {
                phase = Phase.WAIT_PERCH;
                waitTicks = 0;
            }
            case TIMEOUT -> {
                // Try from here
                phase = Phase.WAIT_PERCH;
                waitTicks = 0;
            }
            case NO_TARGET -> {
                // Retry
                waitTicks = 0;
            }
            case PATHING -> {}
        }
    }

    // ── Wait for dragon to perch ─────────────────────────────────

    private void tickWaitPerch(LocalPlayer player, ClientLevel world) {
        EnderDragon dragon = findDragon(player, world);
        if (dragon == null) {
            phase = Phase.DONE;
            return;
        }

        // Check if dragon is perching or sitting on the portal
        var phaseInstance = dragon.getPhaseManager().getCurrentPhase();
        var phaseType = phaseInstance.getPhase();

        boolean isPerching = phaseType == EnderDragonPhase.LANDING
                || phaseType == EnderDragonPhase.SITTING_FLAMING
                || phaseType == EnderDragonPhase.SITTING_SCANNING
                || phaseType == EnderDragonPhase.SITTING_ATTACKING;

        if (isPerching) {
            // Dragon is landing or sitting — time to act
            double dragonDist = player.distanceTo(dragon);

            if (useBedStrategy && hasBed(player)) {
                phase = Phase.PLACE_BED;
                waitTicks = 0;
                EmmaBridgeMod.LOGGER.info("DragonCombat: dragon perching, placing bed");
            } else {
                // Melee fallback
                phase = Phase.MELEE_APPROACH;
                waitTicks = 0;
                EmmaBridgeMod.LOGGER.info("DragonCombat: dragon perching, melee approach");
            }
            return;
        }

        // While waiting, stay near portal center and avoid dragon breath
        double dist = player.position().distanceTo(Vec3.atCenterOf(STAND_POS));
        if (dist > 6.0 && !GoapNavHelper.isPathing()) {
            GoapNavHelper.pathTo(STAND_POS);
        }

        // Re-evaluate strategy: if we ran out of beds, switch to melee
        if (useBedStrategy && !hasBed(player)) {
            useBedStrategy = false;
            EmmaBridgeMod.LOGGER.info("DragonCombat: out of beds, switching to melee");
        }
    }

    // ── Bed bomb: Place bed ──────────────────────────────────────

    private void tickPlaceBed(LocalPlayer player, ClientLevel world) {
        if (!hasBed(player)) {
            useBedStrategy = false;
            phase = Phase.WAIT_PERCH;
            waitTicks = 0;
            return;
        }

        // Navigate to placement position
        double dist = player.position().distanceTo(Vec3.atCenterOf(STAND_POS));
        if (dist > 4.0) {
            if (!GoapNavHelper.isPathing()) {
                GoapNavHelper.pathTo(STAND_POS);
            }
            if (waitTicks > 60) {
                // Timeout, try placing from here
                GoapNavHelper.cancelPathing();
            } else {
                return;
            }
        } else {
            if (GoapNavHelper.isPathing()) {
                GoapNavHelper.cancelPathing();
            }
        }

        // Equip a bed
        if (!equipBed(player)) return;

        // Wait a couple ticks for server to sync held item
        if (waitTicks < 3) return;

        // Place bed directly at target position, facing SOUTH toward portal center.
        // Facing controls bed orientation only — no lookAt or solid ground check needed.
        InteractionResult result = BlockInteraction.rightClickBlock(
                BED_PLACE_POS, Direction.UP, Direction.SOUTH);

        if (result != null && result.consumesAction()) {
            placedBedPos = BED_PLACE_POS;
            phase = Phase.DETONATE;
            waitTicks = 0;
            EmmaBridgeMod.LOGGER.info("DragonCombat: bed placed at {} facing SOUTH", placedBedPos);
        } else if (waitTicks > 20) {
            // Placement failed — retry next perch cycle
            phase = Phase.WAIT_PERCH;
            waitTicks = 0;
        }
    }

    // ── Bed bomb: Detonate ───────────────────────────────────────

    private void tickDetonate(LocalPlayer player, ClientLevel world) {
        EnderDragon dragon = findDragon(player, world);
        if (dragon == null) {
            phase = Phase.DONE;
            return;
        }

        // In the End, beds explode when you right-click them (can't sleep).
        // The explosion happens immediately on right-click interaction.
        // So we actually want: right-click to USE a bed item → it explodes.
        // Or if we placed a bed block, right-click the bed block → explosion.

        if (placedBedPos != null && world.getBlockState(placedBedPos).getBlock() instanceof BedBlock) {
            // Wait for dragon to be close to the bed
            Vec3 bedCenter = Vec3.atCenterOf(placedBedPos);
            double dragonDist = dragon.position().distanceTo(bedCenter);

            // Dragon head is what we care about — it's ahead of body position
            // During perch, head is at roughly (0, 64, 0) on the portal
            if (dragonDist < BED_DETONATION_RANGE || waitTicks > 40) {
                // Detonate! Right-click the bed — no lookAt needed, useItemOn works by position
                BlockInteraction.rightClickBlock(placedBedPos, Direction.UP);
                bedsUsed++;
                placedBedPos = null;
                phase = Phase.RETREAT;
                waitTicks = 0;
                EmmaBridgeMod.LOGGER.info("DragonCombat: bed detonated! ({} total)", bedsUsed);
            }
        } else {
            // Bed wasn't placed or was destroyed — go back to waiting
            placedBedPos = null;
            phase = Phase.WAIT_PERCH;
            waitTicks = 0;
        }
    }

    // ── Melee: Approach dragon ───────────────────────────────────

    private void tickMeleeApproach(LocalPlayer player, ClientLevel world) {
        EnderDragon dragon = findDragon(player, world);
        if (dragon == null) {
            phase = Phase.DONE;
            return;
        }

        // Check dragon is still perching
        var phaseType = dragon.getPhaseManager().getCurrentPhase().getPhase();
        boolean isSitting = phaseType == EnderDragonPhase.SITTING_FLAMING
                || phaseType == EnderDragonPhase.SITTING_SCANNING
                || phaseType == EnderDragonPhase.SITTING_ATTACKING
                || phaseType == EnderDragonPhase.LANDING;

        if (!isSitting) {
            // Dragon took off — go back to waiting
            phase = Phase.WAIT_PERCH;
            waitTicks = 0;
            GoapNavHelper.cancelPathing();
            return;
        }

        double dist = player.distanceTo(dragon);

        if (dist < MELEE_RANGE) {
            GoapNavHelper.cancelPathing();
            phase = Phase.MELEE_ATTACK;
            waitTicks = 0;
            return;
        }

        // Navigate to dragon position (it sits on the portal)
        BlockPos dragonBlockPos = dragon.blockPosition();
        if (!GoapNavHelper.isPathing()) {
            GoapNavHelper.pathTo(dragonBlockPos);
        }

        if (waitTicks > 100) {
            // Timeout approaching
            phase = Phase.WAIT_PERCH;
            waitTicks = 0;
        }
    }

    // ── Melee: Attack dragon ─────────────────────────────────────

    private void tickMeleeAttack(LocalPlayer player, ClientLevel world) {
        EnderDragon dragon = findDragon(player, world);
        if (dragon == null) {
            phase = Phase.DONE;
            return;
        }

        // Check still perching
        var phaseType = dragon.getPhaseManager().getCurrentPhase().getPhase();
        boolean isSitting = phaseType == EnderDragonPhase.SITTING_FLAMING
                || phaseType == EnderDragonPhase.SITTING_SCANNING
                || phaseType == EnderDragonPhase.SITTING_ATTACKING;

        if (!isSitting) {
            phase = Phase.WAIT_PERCH;
            waitTicks = 0;
            return;
        }

        double dist = player.distanceTo(dragon);
        if (dist > MELEE_RANGE + 2) {
            phase = Phase.MELEE_APPROACH;
            waitTicks = 0;
            return;
        }

        // Equip best sword
        equipSword(player);

        // Look at dragon and attack
        BlockInteraction.lookAt(dragon.position().add(0, 2, 0)); // Aim at body center
        DirectInput.setSprinting(true);

        // Attack with cooldown respect
        if (player.getAttackStrengthScale(0.0f) >= 1.0f) {
            Minecraft.getInstance().gameMode.attack(player, dragon);
            player.swing(InteractionHand.MAIN_HAND);
        }

        // Timeout — dragon should take off eventually
        if (waitTicks > 200) {
            phase = Phase.WAIT_PERCH;
            waitTicks = 0;
            DirectInput.setSprinting(false);
        }
    }

    // ── Retreat from explosion ────────────────────────────────────

    private void tickRetreat(LocalPlayer player) {
        DirectInput.setSprinting(false);

        if (waitTicks > 15) {
            // Re-evaluate: more beds? Continue bed strategy. Otherwise melee.
            useBedStrategy = hasBed(player);
            phase = Phase.WAIT_PERCH;
            waitTicks = 0;
            return;
        }

        // Run away from portal center briefly
        if (waitTicks < 8) {
            double dx = player.getX() - PORTAL_CENTER.getX();
            double dz = player.getZ() - PORTAL_CENTER.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist < 6.0) {
                Vec3 awayDir = new Vec3(dx, 0, dz).normalize();
                BlockPos retreatPos = new BlockPos(
                        (int) (player.getX() + awayDir.x * 8),
                        (int) player.getY(),
                        (int) (player.getZ() + awayDir.z * 8));
                if (!GoapNavHelper.isPathing()) {
                    GoapNavHelper.pathTo(retreatPos);
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private EnderDragon findDragon(LocalPlayer player, ClientLevel world) {
        AABB scanBox = new AABB(-200, 0, -200, 200, 256, 200);
        for (Entity entity : world.getEntities(player, scanBox)) {
            if (entity instanceof EnderDragon dragon && dragon.isAlive()) {
                return dragon;
            }
        }
        return null;
    }

    private boolean isDragonAlive(LocalPlayer player, ClientLevel world) {
        return findDragon(player, world) != null;
    }

    private boolean hasBed(LocalPlayer player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BedItem) {
                return true;
            }
        }
        return false;
    }

    private boolean equipBed(LocalPlayer player) {
        int slot = InventoryScanner.findSlot(player.getInventory(),
                stack -> stack.getItem() instanceof BedItem);
        if (slot < 0) return false;

        if (slot < 9) {
            player.getInventory().setSelectedSlot(slot);
        } else {
            // Shift-click from main inventory to hotbar, then select
            ItemClassifier.shiftClick(Minecraft.getInstance(), player, slot);
            // Find where it landed in hotbar
            int hotbarSlot = InventoryScanner.findSlot(player.getInventory(),
                    stack -> stack.getItem() instanceof BedItem);
            if (hotbarSlot >= 0 && hotbarSlot < 9) {
                player.getInventory().setSelectedSlot(hotbarSlot);
            }
        }
        return true;
    }

    private void equipSword(LocalPlayer player) {
        CombatHelper.equipBestWeapon(player);
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        DirectInput.setForward(false);
        DirectInput.setSprinting(false);
        if (phase != Phase.DONE) {
            GoapNavHelper.cancelPathing();
        }
        phase = Phase.DONE;
        targetGoalId = null;
    }

    @Override
    public boolean isActive() {
        return phase != Phase.DONE;
    }

    @Override
    public int getMinimumActiveTicks() {
        return 40; // Don't interrupt during combat
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public String personalityCategory() {
        return "aggression";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("phase", phase.name());
        bd.addProperty("strategy", useBedStrategy ? "bed_bomb" : "melee");
        bd.addProperty("beds_used", bedsUsed);
        return bd;
    }
}
