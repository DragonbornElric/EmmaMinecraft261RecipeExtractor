package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Direct Mojang API wrappers for block interaction.
 * <p>
 * Uses pure distance-based reach checks (no raycast gating, no crosshairTarget dependency).
 * Constructs BlockHitResult directly from known BlockPos + computed face,
 * matching the Phase 52-53 Baritone pattern (Movement.prepared() / ProcessBreakHelper).
 */
public final class BlockInteraction {

    private BlockInteraction() {}

    // ── Reach check ──────────────────────────────────────────

    /**
     * Check whether the player can reach a block position.
     * Pure distance check from eye position to block center.
     */
    public static boolean isInReach(AltoClef mod, BlockPos pos) {
        Vec3d eye = mod.getPlayer().getEyePos();
        double dist = eye.distanceTo(Vec3d.ofCenter(pos));
        double reachDist = mod.getClientBaritone().getPlayerContext()
                .playerController().getBlockReachDistance();
        return dist <= reachDist;
    }

    /**
     * Check whether the player has line-of-sight to a fluid source block.
     * Performs a client-side raycast from eye to block center.
     * Uses SOURCE_ONLY so the ray SEES fluid source blocks (stops at them).
     * Uses COLLIDER shape so solid blocks block the ray.
     *
     * @return true if the ray reaches the target fluid without hitting a solid block first
     */
    public static boolean hasFluidLOS(AltoClef mod, BlockPos pos) {
        Vec3d eye = mod.getPlayer().getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        BlockHitResult hit = mod.getWorld().raycast(
                new RaycastContext(eye, target,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.SOURCE_ONLY,
                        mod.getPlayer()));
        // MISS = ray reached endpoint without hitting anything (clear path)
        // BLOCK at target pos = ray hit the fluid block itself (can see it)
        // BLOCK at different pos = solid block in the way
        return hit.getType() == HitResult.Type.MISS
                || hit.getBlockPos().equals(pos);
    }

    // ── Face computation ─────────────────────────────────────

    /**
     * Compute the block face nearest to the player's eye position.
     * Uses geometry (nearest axis to eye), not raycast.
     * Matches ProcessBreakHelper.computeFace() / Movement.computeFace() in emma-pathfinder.
     */
    public static Direction computeFace(AltoClef mod, BlockPos pos) {
        Vec3d eye = mod.getPlayer().getEyePos();
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    // ── BlockHitResult construction ──────────────────────────

    /**
     * Construct a BlockHitResult from a known BlockPos and face.
     * Hit point is on the face center (standard for solid blocks).
     */
    public static BlockHitResult createHitResult(BlockPos pos, Direction face) {
        Vec3d hitPos = Vec3d.ofCenter(pos).add(
                face.getOffsetX() * 0.5,
                face.getOffsetY() * 0.5,
                face.getOffsetZ() * 0.5);
        return new BlockHitResult(hitPos, face, pos, false);
    }

    /**
     * Construct a BlockHitResult for fluid interaction.
     * Hit point is at block center (slightly inside the block, not on face edge).
     * Fluid interactions are more reliable this way.
     */
    public static BlockHitResult createFluidHitResult(AltoClef mod, BlockPos pos) {
        Vec3d hitPos = Vec3d.ofCenter(pos);
        Direction face = computeFace(mod, pos);
        return new BlockHitResult(hitPos, face, pos, false);
    }

    // ── Right-click (place/use) ──────────────────────────────

    /**
     * Right-click interact with a block. Uses direct API, no crosshairTarget.
     * Does NOT check reach — caller must verify isInReach() first if needed.
     *
     * @param face   the face to interact with (null = auto-compute nearest face)
     * @param facing cardinal direction to temporarily set player yaw for directional
     *               blocks (beds, stairs, pistons). null = use current yaw.
     * @return the ActionResult from interactBlock
     */
    public static ActionResult rightClickBlock(AltoClef mod, BlockPos pos, Direction face, Direction facing) {
        if (face == null) face = computeFace(mod, pos);

        float originalYaw = mod.getPlayer().getYaw();
        boolean yawOverridden = false;
        if (facing != null) {
            mod.getPlayer().setYaw(facingToYaw(facing));
            yawOverridden = true;
        }

        BlockHitResult bhr = createHitResult(pos, face);
        ActionResult result = MinecraftClient.getInstance().interactionManager
                .interactBlock(mod.getPlayer(), Hand.MAIN_HAND, bhr);
        mod.getPlayer().swingHand(Hand.MAIN_HAND);

        if (yawOverridden) {
            mod.getPlayer().setYaw(originalYaw);
        }
        return result;
    }

    /**
     * Right-click interact with a block (no facing override).
     */
    public static ActionResult rightClickBlock(AltoClef mod, BlockPos pos, Direction face) {
        return rightClickBlock(mod, pos, face, null);
    }

    /**
     * Right-click interact with auto-computed face.
     */
    public static ActionResult rightClickBlock(AltoClef mod, BlockPos pos) {
        return rightClickBlock(mod, pos, null, null);
    }

    /** Map cardinal direction to Minecraft yaw for directional block placement. */
    private static float facingToYaw(Direction dir) {
        return switch (dir) {
            case SOUTH -> 0f;
            case WEST  -> 90f;
            case NORTH -> 180f;
            case EAST  -> -90f;
            default    -> 0f;
        };
    }

    /**
     * Right-click a fluid source block with validation.
     * Validates: (1) player is not standing inside the target block,
     * (2) fluid is a still source block, not flowing.
     * Uses center hit point for reliable fluid pickup.
     *
     * @return true if interaction was attempted, false if validation failed
     */
    public static boolean rightClickFluid(AltoClef mod, BlockPos pos) {
        // Don't try to pick up fluid we're standing in — move first
        if (mod.getPlayer().getBlockPos().equals(pos)) return false;
        // Must be a still source block, not flowing
        if (!mod.getWorld().getFluidState(pos).isStill()) return false;

        BlockHitResult bhr = createFluidHitResult(mod, pos);
        MinecraftClient.getInstance().interactionManager
                .interactBlock(mod.getPlayer(), Hand.MAIN_HAND, bhr);
        mod.getPlayer().swingHand(Hand.MAIN_HAND);
        return true;
    }

    /**
     * Return the BlockPos of any solid block blocking LOS to a fluid at {@code pos},
     * or null if line of sight is clear.
     * Uses World.raycast (deterministic geometry) instead of crosshairTarget.
     * Can be called at any time — no timing or rotation constraints.
     */
    public static BlockPos fluidLOSObstruction(AltoClef mod, BlockPos pos) {
        Vec3d eye = mod.getPlayer().getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        BlockHitResult hit = mod.getWorld().raycast(
                new RaycastContext(eye, target,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.SOURCE_ONLY,
                        mod.getPlayer()));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(pos)) {
            return hit.getBlockPos();
        }
        return null;
    }

    /**
     * Look at the fluid block at {@code pos}, validate LOS via crosshairTarget,
     * equip a bucket, and use the bucket item to collect.
     * <p>
     * Returns true if the interaction was dispatched.
     * Returns false when: not in reach, player inside the block, bucket missing,
     * or cursor not yet on target (head still turning or solid obstruction).
     * <p>
     * Fluid collection requires {@code interactItem} (not {@code interactBlock}) because
     * {@code FluidBlock.onUse()} returns PASS — the bucket is filled by
     * {@code BucketItem.use()}, which does its own internal raycast from the player's
     * look direction. This mirrors vanilla's {@code handleBlockInteraction} fallthrough:
     * interactBlock PASS → interactItem.
     * <p>
     * Uses an angle check instead of crosshairTarget because vanilla's
     * GameRenderer.updateCrosshairTarget() uses FluidHandling.NONE — crosshairTarget
     * passes straight through fluids and never reports them.
     * <p>
     * Pattern: tick N → lookAt sets rotation, angle check fails (not yet facing) → false.
     * Tick N+1 → player already facing from last tick's lookAt, angle check passes →
     * fire interactItem. The movement packet carrying the rotation was sent between ticks,
     * so the server's BucketItem.use() raycast sees the correct direction.
     */
    public static boolean tryCollectFluid(AltoClef mod, BlockPos pos) {
        if (!isInReach(mod, pos)) return false;
        if (mod.getPlayer().getBlockPos().equals(pos)) return false;
        if (!hasFluidLOS(mod, pos)) return false;
        if (!mod.getSlotHandler().forceEquipItem(Items.BUCKET)) return false;

        // Check if player is ALREADY facing the fluid (from last tick's lookAt).
        // Must check BEFORE calling lookAt — we need to know if the rotation
        // was set on a previous tick (and thus already sent to server).
        boolean alreadyFacing = isLookingAt(mod, pos, 5.0);

        // Always maintain look direction for next tick.
        LookHelper.lookAt(mod, pos);

        if (!alreadyFacing) {
            return false; // rotation just set — wait for server to receive it
        }

        // Rotation was set last tick, movement packet already sent to server.
        // BucketItem.use() server-side raycast will see the correct direction.
        ActionResult result = MinecraftClient.getInstance().interactionManager
                .interactItem(mod.getPlayer(), Hand.MAIN_HAND);
        if (!result.isAccepted()) return false;
        mod.getPlayer().swingHand(Hand.MAIN_HAND);
        return true;
    }

    /**
     * Check if the player's look direction points approximately at a block position.
     * Uses dot product of look vector vs direction-to-target.
     */
    public static boolean isLookingAt(AltoClef mod, BlockPos pos, double toleranceDegrees) {
        Vec3d eye = mod.getPlayer().getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d toTarget = target.subtract(eye).normalize();
        Vec3d lookVec = mod.getPlayer().getRotationVec(1.0f);
        double dot = lookVec.dotProduct(toTarget);
        double angleDeg = Math.toDegrees(Math.acos(Math.min(1.0, dot)));
        return angleDeg < toleranceDegrees;
    }

    // ── Fluid dump (place fluid from bucket) ────────────────

    /**
     * Dump a fluid bucket (water/lava) onto a target block.
     * Same pattern as tryCollectFluid: angle check + interactItem.
     * BucketItem.use() handles both filling AND emptying via its own raycast.
     *
     * @param fluidBucket the filled bucket item (e.g. Items.WATER_BUCKET)
     * @return true if interaction was dispatched, false if not yet facing target
     */
    public static boolean tryDumpFluid(AltoClef mod, BlockPos pos, Item fluidBucket) {
        if (!isInReach(mod, pos)) return false;
        if (!hasFluidLOS(mod, pos)) return false;
        if (!mod.getSlotHandler().forceEquipItem(fluidBucket)) return false;

        boolean alreadyFacing = isLookingAt(mod, pos, 5.0);
        LookHelper.lookAt(mod, pos);

        if (!alreadyFacing) {
            return false;
        }

        ActionResult result = MinecraftClient.getInstance().interactionManager
                .interactItem(mod.getPlayer(), Hand.MAIN_HAND);
        if (!result.isAccepted()) return false;
        mod.getPlayer().swingHand(Hand.MAIN_HAND);
        return true;
    }

    // ── Left-click (attack/break) ────────────────────────────

    /**
     * Start breaking a block (first tick). Uses attackBlock.
     * Does NOT check reach — caller must verify isInReach() first if needed.
     *
     * @param face the face to attack (null = auto-compute nearest face)
     */
    public static void startBreaking(AltoClef mod, BlockPos pos, Direction face) {
        if (face == null) face = computeFace(mod, pos);
        MinecraftClient.getInstance().interactionManager.attackBlock(pos, face);
        mod.getPlayer().swingHand(Hand.MAIN_HAND);
    }

    /**
     * Continue breaking a block (subsequent ticks). Uses updateBlockBreakingProgress.
     *
     * @param face the face being broken (null = auto-compute nearest face)
     */
    public static void continueBreaking(AltoClef mod, BlockPos pos, Direction face) {
        if (face == null) face = computeFace(mod, pos);
        MinecraftClient.getInstance().interactionManager
                .updateBlockBreakingProgress(pos, face);
        mod.getPlayer().swingHand(Hand.MAIN_HAND);
    }
}
