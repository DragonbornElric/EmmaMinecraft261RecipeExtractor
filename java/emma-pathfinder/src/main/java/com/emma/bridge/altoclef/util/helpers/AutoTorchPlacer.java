package adris.altoclef.util.helpers;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.Set;

/**
 * Background utility that automatically places torches when the player
 * is in a dark area. Part of Phase 58a (@player agent utilities).
 *
 * Two-layer control:
 *   - "enabled" = master switch, toggled by @torch command or WebSocket
 *   - "active"  = set by tasks or WebSocket (for standalone testing)
 *
 * Both must be true for torches to be placed.
 *
 * Targets: Minecraft 1.21.8 / Fabric + Yarn mappings
 */
public class AutoTorchPlacer {

    private static final int DEFAULT_LIGHT_THRESHOLD = 3;
    private static final int PLACEMENT_COOLDOWN_TICKS = 3; // ~150ms on wired LAN
    private static final Set<Item> TORCH_ITEMS = Set.of(
            Items.TORCH, Items.SOUL_TORCH
    );
    private static final Set<net.minecraft.block.Block> TORCH_BLOCKS = Set.of(
            net.minecraft.block.Blocks.TORCH, net.minecraft.block.Blocks.SOUL_TORCH,
            net.minecraft.block.Blocks.WALL_TORCH, net.minecraft.block.Blocks.SOUL_WALL_TORCH
    );

    // --- State (volatile for cross-thread visibility: WebSocket handler writes, client tick reads) ---
    private volatile boolean enabled = true;
    private volatile boolean active = false;
    private volatile int lightThreshold = DEFAULT_LIGHT_THRESHOLD;
    private int cooldown = 0;
    private boolean notifiedOutOfTorches = false;

    // Optional callback for "out of torches" event (wired by bridge to WebSocket broadcast)
    private Runnable onTorchOut = null;

    // =========================================================================
    // Public API
    // =========================================================================

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggleEnabled() {
        this.enabled = !this.enabled;
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            notifiedOutOfTorches = false;
        }
    }

    public boolean isActive() {
        return active;
    }

    public void setLightThreshold(int threshold) {
        this.lightThreshold = threshold;
    }

    public int getLightThreshold() {
        return lightThreshold;
    }

    /**
     * Set callback for when torches run out. Called at most once per activation.
     */
    public void setOnTorchOut(Runnable callback) {
        this.onTorchOut = callback;
    }

    /**
     * Count total torches across all inventory slots (hotbar, main, offhand).
     */
    public int getTorchCount() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        PlayerInventory inv = client.player.getInventory();
        int count = 0;

        // Main inventory + hotbar (slots 0-35)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && TORCH_ITEMS.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }

        // Offhand
        ItemStack offhand = inv.getStack(PlayerInventory.OFF_HAND_SLOT);
        if (!offhand.isEmpty() && TORCH_ITEMS.contains(offhand.getItem())) {
            count += offhand.getCount();
        }

        return count;
    }

    /**
     * Call once per client tick from AltoClef's main loop.
     */
    public void tick() {
        if (!enabled || !active) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.interactionManager == null) return;

        // Don't place while a screen/container is open
        if (client.currentScreen != null) return;

        // Cooldown between placements
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        ClientPlayerEntity player = client.player;
        BlockPos feetPos = player.getBlockPos();

        // Already a torch here? Skip (prevents double-placement during light propagation delay)
        if (TORCH_BLOCKS.contains(client.world.getBlockState(feetPos).getBlock())) return;

        // Check block light level (ignores sky light — works in all dimensions)
        int blockLight = client.world.getLightLevel(LightType.BLOCK, feetPos);
        if (blockLight > lightThreshold) return;

        // Check if we can place a torch here
        if (!canPlaceTorchAt(feetPos)) return;

        // Find a torch and place it
        if (placeTorchFromInventory(player)) {
            cooldown = PLACEMENT_COOLDOWN_TICKS;
            notifiedOutOfTorches = false;
        }
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private boolean canPlaceTorchAt(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;

        // Must not be in fluid
        if (!client.world.getBlockState(pos).getFluidState().isEmpty()) return false;

        // Block below must have a solid top face to support a torch
        return Block.sideCoversSmallSquare(client.world, pos.down(), Direction.UP);
    }

    private boolean placeTorchFromInventory(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();

        // --- Option 1: Offhand already has a torch ---
        if (isTorch(inv.getStack(PlayerInventory.OFF_HAND_SLOT))) {
            return doPlaceTorch(player, Hand.OFF_HAND);
        }

        // --- Option 2: Torch in hotbar (slots 0-8) ---
        int hotbarSlot = findTorchInRange(inv, 0, 9);
        if (hotbarSlot != -1) {
            int previousSlot = inv.getSelectedSlot();
            inv.setSelectedSlot(hotbarSlot);
            syncSelectedSlot(player);

            boolean placed = doPlaceTorch(player, Hand.MAIN_HAND);

            inv.setSelectedSlot(previousSlot);
            syncSelectedSlot(player);
            return placed;
        }

        // --- Option 3: Torch in main inventory (slots 9-35) ---
        int mainSlot = findTorchInRange(inv, 9, 36);
        if (mainSlot != -1) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.interactionManager == null) return false;

            int targetHotbarSlot = inv.getSelectedSlot();

            // Swap main inv slot into current hotbar slot
            client.interactionManager.clickSlot(
                    player.playerScreenHandler.syncId,
                    mainSlot,
                    targetHotbarSlot,
                    net.minecraft.screen.slot.SlotActionType.SWAP,
                    player
            );

            boolean placed = doPlaceTorch(player, Hand.MAIN_HAND);

            // Swap back to restore
            client.interactionManager.clickSlot(
                    player.playerScreenHandler.syncId,
                    mainSlot,
                    targetHotbarSlot,
                    net.minecraft.screen.slot.SlotActionType.SWAP,
                    player
            );

            return placed;
        }

        // --- No torches found ---
        if (!notifiedOutOfTorches) {
            notifiedOutOfTorches = true;
            player.sendMessage(
                    net.minecraft.text.Text.literal("[AutoTorch] Out of torches!"),
                    true
            );
            if (onTorchOut != null) {
                onTorchOut.run();
            }
        }
        return false;
    }

    private boolean doPlaceTorch(ClientPlayerEntity player, Hand hand) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null) return false;

        BlockPos below = player.getBlockPos().down();
        Vec3d hitVec = Vec3d.ofCenter(below).add(0, 0.5, 0); // top face of block below

        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, below, false);
        ActionResult result = client.interactionManager.interactBlock(player, hand, hitResult);

        return result.isAccepted();
    }

    private void syncSelectedSlot(ClientPlayerEntity player) {
        player.networkHandler.sendPacket(
                new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(
                        player.getInventory().getSelectedSlot()
                )
        );
    }

    private int findTorchInRange(PlayerInventory inv, int from, int to) {
        for (int i = from; i < to; i++) {
            if (isTorch(inv.getStack(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isTorch(ItemStack stack) {
        return !stack.isEmpty() && TORCH_ITEMS.contains(stack.getItem());
    }
}
