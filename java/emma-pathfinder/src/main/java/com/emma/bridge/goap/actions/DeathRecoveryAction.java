package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalNear;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.goap.DeathContext;
import com.emma.bridge.goap.DeathContext.DeathSnapshot;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.InventoryScanner;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * GOAP Action: Recover items dropped on death.
 *
 * State machine: IDLE -> ASSESS -> TRAVEL -> SCAN_COLLECT -> IDLE
 *
 * No special EQUIP phase -- EquipBestArmorAction competes naturally in the
 * GOAP auction and wins first if spare gear is available.
 *
 * Items scatter ~7 blocks around the death location. SCAN_COLLECT phase
 * scans for all dropped ItemEntity instances within range and walks to
 * each one for auto-pickup on collision.
 *
 * Personality category: "safety" -- personality safety weight scales the score.
 * A cautious Emma strongly pursues recovery; an aggressive Emma may skip it.
 *
 * IMPORTANT -- Despawn timer context:
 * Minecraft's 5-minute item despawn timer ONLY ticks while the chunk is loaded.
 * On Emma's private server, chunks unload when she dies. The 15-minute timeout
 * in DeathContext is a real-time sanity cap, NOT a despawn race. See DeathContext
 * for full explanation.
 */
public class DeathRecoveryAction extends GoapAction {

    /** Navigate to within this distance of death coords (blocks). */
    private static final float ARRIVAL_DISTANCE = 5.0f;

    /** Transition from TRAVEL to SCAN_COLLECT at this distance (blocks). */
    private static final float SCAN_TRIGGER_DISTANCE = 10.0f;

    /** Max radius to look for scattered items from death position (blocks). */
    private static final double ITEM_SCAN_RADIUS = 24.0;

    /** Ticks with no drops found before marking recovery complete. */
    private static final int NO_DROPS_TIMEOUT_TICKS = 200;  // 10 seconds

    private enum Phase { IDLE, ASSESS, TRAVEL, SCAN_COLLECT }

    private Phase phase = Phase.IDLE;
    private int noDropsTimer = 0;
    private ItemEntity currentPickupTarget = null;

    @Override
    public String getName() {
        return "DeathRecovery";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        // Fast path: no death data = nothing to do
        DeathContext ctx = DeathContext.getInstance();
        return ctx.getLatestDeath() != null
                && !ctx.isRecoveryComplete()
                && !ctx.isTimedOut();
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        DeathContext ctx = DeathContext.getInstance();
        DeathSnapshot death = ctx.getLatestDeath();
        if (death == null) return 0;
        if (ctx.isRecoveryComplete()) return 0;
        if (ctx.isTimedOut()) return 0;

        // Hard zeros -- unrecoverable situations
        if (death.voidDeath) return 0;
        if (ctx.isDeathLoop()) return 0;

        float survivalPriority = goals.getGoal("survive")
                .map(g -> g.priority).orElse(8.0f);

        // === Distance factor ===
        double dist = distance3d(state.posX, state.posY, state.posZ,
                death.x, death.y, death.z);
        float distFactor = (float) (1.0 / (1.0 + dist / 200.0));

        // === Lava death penalty (most items destroyed) ===
        float lavaPenalty = death.lavaRelated ? 0.15f : 1.0f;

        // === Danger at death site ===
        float dangerFactor = 1.0f / (1.0f + death.hostileCount * 0.3f);

        // === Cross-dimension penalty ===
        // Not zero -- personality safety weight tips the balance.
        float dimensionFactor;
        if (state.dimension.equals(death.dimension)) {
            dimensionFactor = 1.0f;
        } else if (death.dimension.contains("nether")) {
            dimensionFactor = 0.25f;
        } else if (death.dimension.contains("the_end")) {
            dimensionFactor = 0.15f;
        } else {
            dimensionFactor = 0.2f;
        }

        // === Equipment factor ===
        // Slightly lower when naked -- gives EquipBestArmorAction room to win first
        float gearFactor = (state.armorValue > 0) ? 1.0f : 0.7f;

        // === Phase commitment bonus (anti-thrash) ===
        float commitBonus = switch (phase) {
            case SCAN_COLLECT -> 1.3f;
            case TRAVEL -> 1.1f;
            default -> 1.0f;
        };

        return survivalPriority
                * distFactor
                * lavaPenalty
                * dangerFactor
                * dimensionFactor
                * gearFactor
                * commitBonus;
    }

    @Override
    public void execute(Minecraft client) {
        DeathSnapshot death = DeathContext.getInstance().getLatestDeath();
        if (death == null) {
            phase = Phase.IDLE;
            return;
        }

        // ASSESS: one-time feasibility check
        phase = Phase.ASSESS;

        if (death.voidDeath) {
            EmmaBridgeMod.LOGGER.info("[DeathRecovery] Void death -- items unrecoverable, aborting");
            abort("void_death");
            return;
        }

        if (DeathContext.getInstance().isDeathLoop()) {
            EmmaBridgeMod.LOGGER.info("[DeathRecovery] Death loop detected -- aborting to prevent repeat death");
            abort("death_loop");
            return;
        }

        // Start navigation to death site
        phase = Phase.TRAVEL;
        noDropsTimer = 0;
        currentPickupTarget = null;

        EmmaBridgeMod.LOGGER.info("[DeathRecovery] Starting recovery -- navigating to death at {}, {}, {} ({})",
                (int) death.x, (int) death.y, (int) death.z, death.dimension);

        startNavigationToDeath(death);
    }

    @Override
    public void tick(Minecraft client) {
        DeathContext ctx = DeathContext.getInstance();

        // Safety: abort if timed out or death data cleared
        if (ctx.isTimedOut() || ctx.getLatestDeath() == null) {
            abort("timed_out");
            return;
        }

        switch (phase) {
            case TRAVEL -> tickTravel(client);
            case SCAN_COLLECT -> tickScanCollect(client);
            default -> {} // IDLE or ASSESS -- nothing to tick
        }
    }

    private void tickTravel(Minecraft client) {
        DeathSnapshot death = DeathContext.getInstance().getLatestDeath();
        if (death == null) { abort("death_cleared"); return; }

        // Check distance to death site
        LocalPlayer player = client.player;
        if (player != null) {
            double dist = player.position().distanceTo(
                    new Vec3(death.x, death.y, death.z));

            if (dist < SCAN_TRIGGER_DISTANCE) {
                // Close enough -- switch to scanning
                phase = Phase.SCAN_COLLECT;
                noDropsTimer = 0;
                EmmaBridgeMod.LOGGER.info("[DeathRecovery] Arrived near death site -- scanning for items");
                // Cancel Emmatone navigation, we'll handle movement now
                cancelEmmatone();
                return;
            }
        }

        // Check if Emmatone stopped pathing (stuck or finished)
        boolean pathing = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone()
                .getPathingBehavior().isPathing();

        if (!pathing) {
            // Emmatone stopped -- re-issue navigation
            startNavigationToDeath(death);
        }
    }

    private void tickScanCollect(Minecraft client) {
        DeathSnapshot death = DeathContext.getInstance().getLatestDeath();
        if (death == null) { abort("death_cleared"); return; }

        LocalPlayer player = client.player;
        if (player == null) return;

        Vec3 deathPos = new Vec3(death.x, death.y, death.z);

        // Find all dropped items near the death position
        AABB scanBox = new AABB(
                deathPos.x - ITEM_SCAN_RADIUS, deathPos.y - ITEM_SCAN_RADIUS, deathPos.z - ITEM_SCAN_RADIUS,
                deathPos.x + ITEM_SCAN_RADIUS, deathPos.y + ITEM_SCAN_RADIUS, deathPos.z + ITEM_SCAN_RADIUS);

        List<ItemEntity> nearbyDrops = player.level().getEntitiesOfClass(
                ItemEntity.class, scanBox, item -> item.isAlive());

        // Find best drop by value-weighted distance: score = itemValue / (1 + distance)
        // Diamond gear > weapons > iron gear > food > junk
        Optional<ItemEntity> nearest = nearbyDrops.stream()
                .max((a, b) -> {
                    double scoreA = getItemValue(a) / (1.0 + a.position().distanceTo(player.position()));
                    double scoreB = getItemValue(b) / (1.0 + b.position().distanceTo(player.position()));
                    return Double.compare(scoreA, scoreB);
                });

        if (nearest.isPresent()) {
            noDropsTimer = 0;
            ItemEntity target = nearest.get();

            // Check if we're close enough for auto-pickup (within ~1.5 blocks)
            double targetDist = player.position().distanceTo(target.position());
            if (targetDist > 1.5) {
                // Navigate to the item
                if (target != currentPickupTarget) {
                    currentPickupTarget = target;
                    EmmatoneAPI.getProvider()
                            .getPrimaryEmmatone()
                            .getCustomGoalProcess()
                            .setGoalAndPath(new GoalNear(
                                    target.blockPosition(), 1));
                }
            }
            // Auto-pickup happens on collision -- no explicit action needed
        } else {
            // No drops visible -- increment timeout
            noDropsTimer++;
            currentPickupTarget = null;

            if (noDropsTimer >= NO_DROPS_TIMEOUT_TICKS) {
                EmmaBridgeMod.LOGGER.info("[DeathRecovery] No more drops found -- recovery complete");
                DeathContext.getInstance().markRecoveryComplete();
                phase = Phase.IDLE;
                cancelEmmatone();
            }
        }

        // Also complete if inventory is full
        if (player.getInventory() != null) {
            if (InventoryScanner.emptySlots(player.getInventory()) == 0) {
                EmmaBridgeMod.LOGGER.info("[DeathRecovery] Inventory full -- recovery complete");
                DeathContext.getInstance().markRecoveryComplete();
                phase = Phase.IDLE;
                cancelEmmatone();
            }
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        // If we're mid-recovery and another action preempts (flee, attack),
        // DON'T clear the death context -- we'll resume when we win the auction again.
        // Only cancel active navigation.
        if (phase == Phase.TRAVEL || phase == Phase.SCAN_COLLECT) {
            cancelEmmatone();
        }
        currentPickupTarget = null;
        // Note: phase is preserved so we can resume scoring with commit bonus
    }

    @Override
    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    // -- Personality + goal integration -----------------------------------

    @Override
    public String getPrimaryGoalId() {
        return "survive";
    }

    @Override
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        // Recovery is a sub-goal of survival
        if ("survive".equals(goal.id)) return 0.4f;
        return 0.0f;
    }

    @Override
    public String personalityCategory() {
        return "safety";
    }

    // -- Debug ------------------------------------------------------------

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("phase", phase.name());
        bd.addProperty("no_drops_timer", noDropsTimer);

        DeathContext ctx = DeathContext.getInstance();
        DeathSnapshot death = ctx.getLatestDeath();
        if (death != null) {
            bd.addProperty("death_pos",
                    (int) death.x + ", " + (int) death.y + ", " + (int) death.z);
            bd.addProperty("death_dimension", death.dimension);
            bd.addProperty("death_cause", death.cause);
            bd.addProperty("elapsed_sec",
                    (System.currentTimeMillis() - death.timestampMs) / 1000);
            bd.addProperty("lava_related", death.lavaRelated);
            bd.addProperty("void_death", death.voidDeath);
            bd.addProperty("hostile_count", death.hostileCount);

            // Show individual scoring factors
            double dist = distance3d(state.posX, state.posY, state.posZ,
                    death.x, death.y, death.z);
            bd.addProperty("dist_blocks", (int) dist);
            bd.addProperty("dist_factor", (float) (1.0 / (1.0 + dist / 200.0)));
            bd.addProperty("lava_penalty", death.lavaRelated ? 0.15f : 1.0f);
            bd.addProperty("danger_factor", 1.0f / (1.0f + death.hostileCount * 0.3f));
            bd.addProperty("gear_factor", (state.armorValue > 0) ? 1.0f : 0.7f);

            String dimFactor;
            if (state.dimension.equals(death.dimension)) dimFactor = "1.0 (same)";
            else if (death.dimension.contains("nether")) dimFactor = "0.25 (nether)";
            else if (death.dimension.contains("the_end")) dimFactor = "0.15 (end)";
            else dimFactor = "0.2 (cross-dim)";
            bd.addProperty("dimension_factor", dimFactor);
        }

        bd.addProperty("death_loop", ctx.isDeathLoop());
        bd.addProperty("timed_out", ctx.isTimedOut());
        bd.addProperty("recovery_complete", ctx.isRecoveryComplete());

        return bd;
    }

    // -- Helpers ----------------------------------------------------------

    /**
     * Assign a value multiplier to dropped items for priority pickup.
     * Diamond gear > weapons > iron gear > food > everything else.
     */
    private static double getItemValue(ItemEntity itemEntity) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(itemEntity.getItem().getItem()).toString();

        if (id.contains("diamond")) return 10.0;
        if (id.contains("netherite")) return 12.0;
        if (id.contains("sword") || id.contains("bow") || id.contains("trident")
                || id.contains("crossbow")) return 8.0;
        if (id.contains("iron")) return 5.0;
        if (id.contains("_pickaxe") || id.contains("_axe") || id.contains("_shovel")) return 6.0;
        if (id.contains("shield")) return 5.0;

        // Check if food via DataComponentTypes
        if (itemEntity.getItem().has(
                net.minecraft.core.component.DataComponents.FOOD)) return 3.0;

        return 1.0;
    }

    private void startNavigationToDeath(DeathSnapshot death) {
        BlockPos deathPos = new BlockPos((int) death.x, (int) death.y, (int) death.z);
        EmmatoneAPI.getProvider()
                .getPrimaryEmmatone()
                .getCustomGoalProcess()
                .setGoalAndPath(new GoalNear(deathPos, (int) ARRIVAL_DISTANCE));
    }

    private void cancelEmmatone() {
        try {
            EmmatoneAPI.getProvider()
                    .getPrimaryEmmatone()
                    .getPathingBehavior().cancelEverything();
        } catch (Exception ignored) {}
    }

    private void abort(String reason) {
        EmmaBridgeMod.LOGGER.info("[DeathRecovery] Aborting: {}", reason);
        DeathContext.getInstance().markRecoveryComplete();
        phase = Phase.IDLE;
        currentPickupTarget = null;
    }

    private static double distance3d(double x1, double y1, double z1,
                                     double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
