package com.emma.bridge.control;

import emmatone.api.EmmatoneAPI;
import emmatone.api.utils.IPlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import emmatone.api.utils.Rotation;
import emmatone.api.utils.RotationUtils;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;

/**
 * Direct Mojang API wrappers for block interaction.
 * <p>
 * Uses pure distance-based reach checks (no raycast gating, no crosshairTarget dependency).
 * Constructs BlockHitResult directly from known BlockPos + computed face,
 * matching the emma-pathfinder pattern (Movement.prepared() / ProcessBreakHelper).
 * <p>
 * Zero EmmaClef dependencies — uses Minecraft + Emmatone APIs directly.
 */
public final class BlockInteraction {

    private BlockInteraction() {}

    /** Last block position right-clicked. Used by ContainerTracker to associate open screens with block positions. */
    public static BlockPos lastInteractedBlockPos = null;

    // ── Reach check ──────────────────────────────────────────

    /**
     * Check whether the player can reach a block position.
     * Pure distance check from eye position to block center.
     */
    public static boolean isInReach(BlockPos pos) {
        LocalPlayer player = player();
        if (player == null) return false;
        Vec3 eye = player.getEyePosition();
        double dist = eye.distanceTo(Vec3.atCenterOf(pos));
        double reachDist = EmmatoneAPI.getProvider().getPrimaryEmmatone()
                .getPlayerContext().playerController().getBlockReachDistance();
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
    public static boolean hasFluidLOS(BlockPos pos) {
        LocalPlayer player = player();
        Level world = world();
        if (player == null || world == null) return false;
        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        BlockHitResult hit = world.clip(
                new ClipContext(eye, target,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.SOURCE_ONLY,
                        player));
        return hit.getType() == HitResult.Type.MISS
                || hit.getBlockPos().equals(pos);
    }

    // ── Face computation ─────────────────────────────────────

    /**
     * Compute the block face nearest to the player's eye position.
     * Uses geometry (nearest axis to eye), not raycast.
     */
    public static Direction computeFace(BlockPos pos) {
        LocalPlayer player = player();
        if (player == null) return Direction.UP;
        Vec3 eye = player.getEyePosition();
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
        Vec3 hitPos = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5,
                face.getStepY() * 0.5,
                face.getStepZ() * 0.5);
        return new BlockHitResult(hitPos, face, pos, false);
    }

    /**
     * Construct a BlockHitResult for fluid interaction.
     * Hit point is at block center (slightly inside the block, not on face edge).
     */
    public static BlockHitResult createFluidHitResult(BlockPos pos) {
        Vec3 hitPos = Vec3.atCenterOf(pos);
        Direction face = computeFace(pos);
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
     * @return the InteractionResult from interactBlock
     */
    public static InteractionResult rightClickBlock(BlockPos pos, Direction face, Direction facing) {
        lastInteractedBlockPos = pos;
        LocalPlayer player = player();
        if (player == null) return InteractionResult.FAIL;
        if (face == null) face = computeFace(pos);

        float originalYaw = player.getYRot();
        boolean yawOverridden = false;
        if (facing != null) {
            player.setYRot(facingToYaw(facing));
            yawOverridden = true;
        }

        BlockHitResult bhr = createHitResult(pos, face);
        InteractionResult result = Minecraft.getInstance().gameMode
                .useItemOn(player, InteractionHand.MAIN_HAND, bhr);
        player.swing(InteractionHand.MAIN_HAND);

        if (yawOverridden) {
            player.setYRot(originalYaw);
        }
        return result;
    }

    /**
     * Right-click interact with a block (no facing override).
     */
    public static InteractionResult rightClickBlock(BlockPos pos, Direction face) {
        return rightClickBlock(pos, face, null);
    }

    /**
     * Right-click interact with auto-computed face.
     */
    public static InteractionResult rightClickBlock(BlockPos pos) {
        return rightClickBlock(pos, null, null);
    }

    /**
     * Right-click a block via the emmatone IPlayerContext abstraction.
     * Constructs a BlockHitResult, calls processRightClickBlock, and swings on success.
     * Single source of truth for the place-block-and-swing pattern used by emmatone processes.
     *
     * @param ctx     the emmatone player context
     * @param against the block to click on (face center is the hit point)
     * @param face    the face of the block to click
     * @return the InteractionResult
     */
    public static InteractionResult placeBlock(IPlayerContext ctx, BlockPos against, Direction face) {
        BlockHitResult hr = createHitResult(against, face);
        InteractionResult result = ctx.playerController().processRightClickBlock(
                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hr);
        if (result == InteractionResult.SUCCESS) {
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }
        return result;
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
     *
     * @return true if interaction was attempted, false if validation failed
     */
    public static boolean rightClickFluid(BlockPos pos) {
        LocalPlayer player = player();
        Level world = world();
        if (player == null || world == null) return false;
        if (player.blockPosition().equals(pos)) return false;
        if (!world.getFluidState(pos).isSource()) return false;

        BlockHitResult bhr = createFluidHitResult(pos);
        Minecraft.getInstance().gameMode
                .useItemOn(player, InteractionHand.MAIN_HAND, bhr);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Return the BlockPos of any solid block blocking LOS to a fluid at {@code pos},
     * or null if line of sight is clear.
     */
    public static BlockPos fluidLOSObstruction(BlockPos pos) {
        LocalPlayer player = player();
        Level world = world();
        if (player == null || world == null) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        BlockHitResult hit = world.clip(
                new ClipContext(eye, target,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.SOURCE_ONLY,
                        player));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(pos)) {
            return hit.getBlockPos();
        }
        return null;
    }

    /**
     * Look at a block position, validate LOS, equip a bucket, and collect fluid.
     * Uses angle check + interactItem (BucketItem.use() does its own server-side raycast).
     *
     * Pattern: tick N → lookAt sets rotation, angle check fails → false.
     * Tick N+1 → already facing from last tick → angle check passes → fire interactItem.
     */
    public static boolean tryCollectFluid(BlockPos pos) {
        LocalPlayer player = player();
        if (player == null) return false;
        if (!isInReach(pos)) return false;
        if (player.blockPosition().equals(pos)) return false;
        if (!hasFluidLOS(pos)) return false;
        if (!forceEquipItem(Items.BUCKET)) return false;

        boolean alreadyFacing = isLookingAt(pos, 5.0);
        lookAt(pos);

        if (!alreadyFacing) {
            return false; // rotation just set — wait for server to receive it
        }

        InteractionResult result = Minecraft.getInstance().gameMode
                .useItem(player, InteractionHand.MAIN_HAND);
        if (!result.consumesAction()) return false;
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Check if the player's look direction points approximately at a block position.
     * Uses dot product of look vector vs direction-to-target.
     */
    public static boolean isLookingAt(BlockPos pos, double toleranceDegrees) {
        LocalPlayer player = player();
        if (player == null) return false;
        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        Vec3 toTarget = target.subtract(eye).normalize();
        Vec3 lookVec = player.getViewVector(1.0f);
        double dot = lookVec.dot(toTarget);
        double angleDeg = Math.toDegrees(Math.acos(Math.min(1.0, dot)));
        return angleDeg < toleranceDegrees;
    }

    // ── Fluid dump (place fluid from bucket) ────────────────

    /**
     * Dump a fluid bucket (water/lava) onto a target block.
     * Same pattern as tryCollectFluid: angle check + interactItem.
     *
     * @param fluidBucket the filled bucket item (e.g. Items.WATER_BUCKET)
     * @return true if interaction was dispatched, false if not yet facing target
     */
    public static boolean tryDumpFluid(BlockPos pos, Item fluidBucket) {
        LocalPlayer player = player();
        if (player == null) return false;
        if (!isInReach(pos)) return false;
        if (!hasFluidLOS(pos)) return false;
        if (!forceEquipItem(fluidBucket)) return false;

        boolean alreadyFacing = isLookingAt(pos, 5.0);
        lookAt(pos);

        if (!alreadyFacing) {
            return false;
        }

        InteractionResult result = Minecraft.getInstance().gameMode
                .useItem(player, InteractionHand.MAIN_HAND);
        if (!result.consumesAction()) return false;
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // ── Left-click (attack/break) ────────────────────────────

    /**
     * Start breaking a block (first tick). Uses attackBlock.
     *
     * @param face the face to attack (null = auto-compute nearest face)
     */
    public static void startBreaking(BlockPos pos, Direction face) {
        LocalPlayer player = player();
        if (player == null) return;
        if (face == null) face = computeFace(pos);
        Minecraft.getInstance().gameMode.startDestroyBlock(pos, face);
        player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Continue breaking a block (subsequent ticks). Uses updateBlockBreakingProgress.
     *
     * @param face the face being broken (null = auto-compute nearest face)
     */
    public static void continueBreaking(BlockPos pos, Direction face) {
        LocalPlayer player = player();
        if (player == null) return;
        if (face == null) face = computeFace(pos);
        Minecraft.getInstance().gameMode
                .continueDestroyBlock(pos, face);
        player.swing(InteractionHand.MAIN_HAND);
    }

    // ── Look helper ──────────────────────────────────────────

    /**
     * Compute yaw and pitch angles from an eye position to a target position.
     * Delegates to Emmatone's RotationUtils for the actual math.
     *
     * @return float[2] where [0]=yaw, [1]=pitch (in degrees)
     */
    public static float[] calcYawPitch(Vec3 eye, Vec3 target) {
        Rotation rot = RotationUtils.calcRotationFromVec3d(eye, target);
        return new float[]{rot.getYaw(), rot.getPitch()};
    }

    /**
     * Set the player's yaw and pitch to look at a block position.
     * Sets rotation directly on the player entity — the movement packet
     * carrying the new rotation is sent between ticks automatically.
     */
    public static void lookAt(BlockPos pos) {
        lookAt(Vec3.atCenterOf(pos));
    }

    /**
     * Set the player's yaw and pitch to look at a Vec3 position.
     */
    public static void lookAt(Vec3 target) {
        LocalPlayer player = player();
        if (player == null) return;
        float[] rot = calcYawPitch(player.getEyePosition(), target);
        player.setYRot(rot[0]);
        player.setXRot(rot[1]);
    }

    // ── Inventory helpers ────────────────────────────────────

    /**
     * Find an item in the player's inventory and move it to the main hand (slot 0 of hotbar).
     * Uses direct inventory click API — no EmmaClef SlotHandler.
     *
     * @return true if the item is now in the main hand, false if not found
     */
    public static boolean forceEquipItem(Item item) {
        LocalPlayer player = player();
        if (player == null) return false;

        // Already holding it?
        if (player.getMainHandItem().is(item)) return true;

        // Search hotbar first (slots 0-8)
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(item)) {
                player.getInventory().setSelectedSlot(i);
                return true;
            }
        }

        // Search main inventory (slots 9-35) and swap to hotbar slot 0
        // InventoryMenu slot mapping: 9-35 = main inventory, 36-44 = hotbar
        // Hotbar slot 0 in Inventory = screen handler slot 36
        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getItem(i).is(item)) {
                // Click the source slot, then click hotbar slot 0 (screen handler slot 36) to swap
                int syncId = player.inventoryMenu.containerId;
                Minecraft.getInstance().gameMode
                        .handleContainerInput(syncId, i, 0, ContainerInput.PICKUP, player);
                Minecraft.getInstance().gameMode
                        .handleContainerInput(syncId, 36, 0, ContainerInput.PICKUP, player);
                // If there was an item in slot 0, put it back in the source slot
                if (!player.inventoryMenu.getCarried().isEmpty()) {
                    Minecraft.getInstance().gameMode
                            .handleContainerInput(syncId, i, 0, ContainerInput.PICKUP, player);
                }
                player.getInventory().setSelectedSlot(0);
                return true;
            }
        }

        return false; // item not in inventory
    }

    // ── Internal ─────────────────────────────────────────────

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    private static Level world() {
        return Minecraft.getInstance().level;
    }
}
