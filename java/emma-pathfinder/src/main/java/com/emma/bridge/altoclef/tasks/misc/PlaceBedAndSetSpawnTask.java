package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import com.emma.bridge.EmmaBridgeMod;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SleepingChatScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.Optional;

/**
 * Place a bed and sleep in it — direct API approach.
 * <p>
 * No look gates, no raycast validation, no event listeners.
 * Uses interactionManager.interactBlock() directly (proven to work
 * without look direction on private server).
 * <p>
 * Flow: find flat spot → equip bed → set yaw and pitch → place → interact to sleep.
 */
public class PlaceBedAndSetSpawnTask extends Task {

    private static final int SCAN_RADIUS = 2;
    private static final int[] WIDE_RADII = {4, 8, 14, 22};

    private boolean stayInBed;
    private boolean spawnSet;
    private boolean wasSleeping;
    private BlockPos bedForSpawnPoint;

    /** Ticks since bed was placed — used to delay sleep interaction. */
    private int ticksSincePlacement = -1;
    private BlockPos placedBedPos;

    // /** Pending placement: equip happened, waiting for look + equip sync. */
    // private BedPlacement pendingPlacement;
    // private int equipTicks = -1;

    /** Cooldown after failed placement — don't spam interactBlock. */
    private int retryCooldown = 0;

    /** Consecutive local placement failures — after 2, skip local scan and use heightmap. */
    private int placementFailures = 0;

    /** Heightmap-identified flat spot to navigate to when local scan fails. */
    private BlockPos navigateTarget;

    public PlaceBedAndSetSpawnTask() {
    }

    /** Flag: remain in bed until daytime (used by SleepThroughNightTask). */
    public PlaceBedAndSetSpawnTask stayInBed() {
        this.stayInBed = true;
        return this;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    @Override
    protected void onStart() {
        spawnSet = false;
        wasSleeping = false;
        ticksSincePlacement = -1;
        placedBedPos = null;
        // pendingPlacement = null;
        // equipTicks = -1;
        retryCooldown = 0;
        placementFailures = 0;
        navigateTarget = null;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        MinecraftClient client = MinecraftClient.getInstance();

        // ── Dimension gate ──────────────────────────────────────
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to the overworld first.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // ── Already sleeping → just wait ────────────────────────
        if (client.currentScreen instanceof SleepingChatScreen
                || (mod.getPlayer() != null && mod.getPlayer().isSleeping())) {
            wasSleeping = true;
            spawnSet = true;
            setDebugState("Sleeping...");
            return null;
        }

        // ── Just woke up → done ─────────────────────────────────
        if (wasSleeping) {
            spawnSet = true;
            return null;
        }

        // ── Retry cooldown after failed placement ──────────────
        if (retryCooldown > 0) {
            retryCooldown--;
            setDebugState("Placement cooldown (" + retryCooldown + ")");
            return null;
        }

        // ── [COMMENTED OUT] Equip + look + place bed (fluid pattern) ──
        // Look-gating removed: Python test proved interactBlock works without
        // any look direction. isLookingAt() gate + Baritone lookAt caused stalls.
        // if (pendingPlacement != null && equipTicks >= 0) {
        //     ... (see git history for original)
        // }

        // ── Waiting after placement → interact to sleep ─────────
        if (placedBedPos != null && ticksSincePlacement >= 0) {
            ticksSincePlacement++;
            if (ticksSincePlacement >= 5) {
                setDebugState("Getting into bed");
                interactBed(mod, placedBedPos);
                // Reset — if it didn't work, we'll try again
                placedBedPos = null;
                ticksSincePlacement = -1;
            } else {
                setDebugState("Waiting to interact with bed (" + ticksSincePlacement + "/5)");
            }
            return null;
        }

        // ── No bed in inventory → try existing bed or collect one ──
        if (!mod.getItemStorage().hasItem(ItemHelper.BED)) {
            // Try existing beds nearby
            BlockPos existingBed = findNearbyBed(mod, 40);
            if (existingBed != null) {
                return goToAndUseBed(mod, existingBed);
            }
            setDebugState("Getting a bed first");
            return TaskCatalogue.getItemTask("bed", 1);
        }

        // ── Existing bed nearby → just use it ───────────────────
        BlockPos existingBed = findNearbyBed(mod, 20);
        if (existingBed != null) {
            return goToAndUseBed(mod, existingBed);
        }

        // ── In water → find land first ──────────────────────────
        if (mod.getPlayer().isTouchingWater()) {
            setDebugState("In water — can't place bed");
            return null;
        }

        // ── Find flat spot and place bed (direct — no look gate) ──
        // After 2 local placement failures, skip local scan — it finds spots
        // that look valid (e.g. tall grass is replaceable) but placement fails.
        BedPlacement spot = placementFailures < 2 ? findBedSpot(mod) : null;
        if (spot == null) {
            // Local scan failed or skipped — use heightmap to find a spot further out
            if (navigateTarget == null) {
                navigateTarget = findBedSpotWide(mod);
            }
            if (navigateTarget != null) {
                if (mod.getPlayer().getBlockPos().isWithinDistance(navigateTarget, 3)) {
                    // Arrived at new area — reset failures and retry local scan
                    navigateTarget = null;
                    placementFailures = 0;
                    setDebugState("Arrived at target — retrying placement");
                    return null;
                }
                // Navigate to adjacent block so player doesn't stand on the placement spot
                BlockPos standPos = pickAdjacentStandPos(mod, navigateTarget);
                setDebugState("Walking to flat spot at " + navigateTarget);
                return new GetToBlockTask(standPos);
            }
            // Heightmap found nothing either — truly need to wander
            setDebugState("No flat spot found — wandering");
            return new TimeoutWanderTask();
        }
        // Local scan succeeded — clear any stale navigate target
        navigateTarget = null;

        if (!equipBedDirect(mod)) {
            setDebugState("Bed not in hotbar — waiting");
            return null;
        }

        // Place directly — no look gate, no Baritone cancel, no multi-tick wait.
        BlockPos base = spot.base;
        BlockState groundState = client.world.getBlockState(base);
        ItemStack held = mod.getPlayer().getStackInHand(Hand.MAIN_HAND);
        String heldName = held.isEmpty() ? "empty" : Registries.ITEM.getId(held.getItem()).toString();
        EmmaBridgeMod.LOGGER.info("[BedTask] PLACE: base={} ground={} held={} slot={} facing={} playerPos={} playerXYZ=[{},{},{}]",
                base, groundState.getBlock(), heldName,
                mod.getPlayer().getInventory().getSelectedSlot(), spot.facing,
                mod.getPlayer().getBlockPos(),
                String.format("%.1f", mod.getPlayer().getX()),
                String.format("%.1f", mod.getPlayer().getY()),
                String.format("%.1f", mod.getPlayer().getZ()));

        // Place bed with facing — rightClickBlock handles yaw save/set/restore
        // Target foot position from above (Direction.DOWN) so foot is the placement target.
        ActionResult actionResult = BlockInteraction.rightClickBlock(mod, base.up(), Direction.DOWN, spot.facing);
        EmmaBridgeMod.LOGGER.info("[BedTask] PLACE result: {}", actionResult);

        if (actionResult.isAccepted()) {
            placedBedPos = base.up();
            bedForSpawnPoint = placedBedPos;
            ticksSincePlacement = 0;
            placementFailures = 0;
            setDebugState("Bed placed at " + placedBedPos);
        } else {
            placementFailures++;
            EmmaBridgeMod.LOGGER.info("[BedTask] Placement failure #{}", placementFailures);
            setDebugState("Placement FAILED (" + placementFailures + "): " + actionResult);
            retryCooldown = 40;
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Nothing to clean up — no behaviour stack, no event listeners
    }

    @Override
    public boolean isFinished() {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) return true;
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() == null) return false;
        boolean sleeping = mod.getPlayer().isSleeping();
        // Finished when spawn is set and no longer sleeping
        return spawnSet && !sleeping && wasSleeping;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlaceBedAndSetSpawnTask;
    }

    @Override
    protected String toDebugString() {
        return "Placing bed + sleeping";
    }

    // ── Public API (used by BeatMinecraftTask) ──────────────────

    public BlockPos getBedSleptPos() {
        return bedForSpawnPoint;
    }

    public boolean isSpawnSet() {
        return spawnSet;
    }

    public void resetSleep() {
        spawnSet = false;
        wasSleeping = false;
    }

    // ── Types ─────────────────────────────────────────────────────

    private record BedPlacement(BlockPos base, Direction facing) {}

    // ── Private helpers ─────────────────────────────────────────

    /** Find nearest placed bed block within range. */
    private BlockPos findNearbyBed(AltoClef mod, double maxRange) {
        Optional<BlockPos> closest = mod.getBlockScanner().getNearestBlock(
                mod.getPlayer().getPos(),
                pos -> pos.isWithinDistance(mod.getPlayer().getPos(), maxRange),
                ItemHelper.itemsToBlocks(ItemHelper.BED)
        );
        if (closest.isEmpty()) return null;

        // Prefer the head part for interaction
        BlockPos pos = closest.get();
        BlockPos head = WorldHelper.getBedHead(pos);
        return head != null ? head : pos;
    }

    /** Navigate to a bed and interact with it. */
    private Task goToAndUseBed(AltoClef mod, BlockPos bedPos) {
        double distSq = mod.getPlayer().getBlockPos().getSquaredDistance(bedPos);
        if (distSq > 16) { // > 4 blocks away
            setDebugState("Walking to existing bed");
            return new GetToBlockTask(bedPos);
        }
        // Close enough — interact directly
        setDebugState("Getting into existing bed");
        interactBed(mod, bedPos);
        bedForSpawnPoint = bedPos;
        return null;
    }

    /** Right-click a bed to sleep — direct API, no look gate. */
    private void interactBed(AltoClef mod, BlockPos bedPos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null) return;

        BlockState bedState = client.world.getBlockState(bedPos);
        EmmaBridgeMod.LOGGER.info("[BedTask] SLEEP: pos={} block={}",
                bedPos, bedState.getBlock());

        BlockHitResult hitResult = buildHitResult(bedPos, Direction.UP);
        var actionResult = client.interactionManager.interactBlock(
                mod.getPlayer(), Hand.MAIN_HAND, hitResult);
        EmmaBridgeMod.LOGGER.info("[BedTask] SLEEP result: {}", actionResult);
        spawnSet = true;
    }

    /**
     * Equip a bed to the hotbar. Checks hotbar first (fast path),
     * then uses SlotHandler to SWAP from main inventory if needed.
     */
    private boolean equipBedDirect(AltoClef mod) {
        // Fast path: bed already in hotbar
        PlayerInventory inv = mod.getPlayer().getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                for (Item bed : ItemHelper.BED) {
                    if (stack.getItem() == bed) {
                        inv.setSelectedSlot(i);
                        return true;
                    }
                }
            }
        }
        // Bed in main inventory but not hotbar — swap it in
        for (Item bed : ItemHelper.BED) {
            if (mod.getItemStorage().hasItem(bed)) {
                mod.getSlotHandler().forceEquipItem(bed);
                return true; // swap queued, ready next tick
            }
        }
        return false;
    }

    /** Build a BlockHitResult targeting the face of a block (face center). */
    private static BlockHitResult buildHitResult(BlockPos blockPos, Direction face) {
        Vec3d hitPos = Vec3d.ofCenter(blockPos).add(
                face.getOffsetX() * 0.5,
                face.getOffsetY() * 0.5,
                face.getOffsetZ() * 0.5
        );
        return new BlockHitResult(hitPos, face, blockPos, false);
    }

    /**
     * Find a valid spot to place a bed near the player.
     * Scans small radius, skipping spots where bed overlaps player.
     */
    private BedPlacement findBedSpot(AltoClef mod) {
        BlockPos playerPos = mod.getPlayer().getBlockPos();
        BlockPos below = playerPos.down();

        // Scan small radius — overlap check is inside findValidPlacement
        BedPlacement best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = below.add(dx, dy, dz);
                    BedPlacement p = findValidPlacement(candidate, playerPos);
                    if (p == null) continue;
                    double d = candidate.getSquaredDistance(playerPos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        if (best == null) {
            EmmaBridgeMod.LOGGER.info("[BedTask] findBedSpot: no valid spot in radius={} playerPos={}", SCAN_RADIUS, playerPos);
        }
        return best;
    }

    /**
     * Expanding-ring heightmap search for a bed spot beyond SCAN_RADIUS.
     * Searches rings at radii 6, 10, 16, 24 — returns the nearest valid
     * surface block in the first ring that has one.
     *
     * Only needs one block — interactBlock bypasses vanilla placement
     * constraints, so no flat-pair or solid-base requirement.
     */
    private BlockPos findBedSpotWide(AltoClef mod) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        BlockPos playerPos = mod.getPlayer().getBlockPos();
        int px = playerPos.getX(), pz = playerPos.getZ();

        int prevRadius = SCAN_RADIUS;
        for (int radius : WIDE_RADII) {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    // Skip inner area already covered by the previous ring
                    if (Math.abs(dx) <= prevRadius && Math.abs(dz) <= prevRadius) continue;

                    int x = px + dx;
                    int z = pz + dz;
                    int y = client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;

                    // Reject unloaded chunks or unreachable depths
                    if (y < client.world.getBottomY() || y < playerPos.getY() - 20) continue;

                    double dist = dx * dx + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
            if (best != null) {
                EmmaBridgeMod.LOGGER.info("[BedTask] Heightmap found spot at {} (ring radius={})", best, radius);
                return best;
            }
            prevRadius = radius;
        }
        return null;
    }

    /** Pick the cardinal-adjacent block closest to the player to stand on,
     *  so we don't overlap the bed placement spot. */
    private BlockPos pickAdjacentStandPos(AltoClef mod, BlockPos target) {
        BlockPos playerPos = mod.getPlayer().getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos adj = target.offset(dir);
            double dist = adj.getSquaredDistance(playerPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = adj;
            }
        }
        return best;
    }

    /**
     * Check if a bed can be placed above pos. No solid base required —
     * our interactBlock approach bypasses vanilla placement constraints.
     * Only needs: replaceable foot + replaceable head + headroom above foot
     * (so player doesn't suffocate when standing up from bed).
     * Skips directions where the bed head would overlap the player.
     */
    private BedPlacement findValidPlacement(BlockPos pos, BlockPos playerPos) {
        MinecraftClient client = MinecraftClient.getInstance();
        BlockPos foot = pos.up();
        // Foot position must be replaceable (air, tall grass, etc.)
        if (!client.world.getBlockState(foot).isReplaceable()) return null;
        // Foot can't be where the player is standing (entity collision blocks placement)
        // Player occupies two blocks: playerPos (feet) and playerPos.up() (head)
        if (foot.equals(playerPos) || foot.equals(playerPos.up())) return null;
        // Headroom above foot — player stands up here
        if (WorldHelper.isSolidBlock(foot.up())) return null;
        // Find a cardinal direction where head can go — skip directions that overlap player
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos head = foot.offset(dir);
            if (head.equals(playerPos) || head.equals(playerPos.up())) continue;
            if (client.world.getBlockState(head).isReplaceable()) {
                return new BedPlacement(pos, dir);
            }
        }
        return null; // all 4 directions blocked or overlap player
    }
}
