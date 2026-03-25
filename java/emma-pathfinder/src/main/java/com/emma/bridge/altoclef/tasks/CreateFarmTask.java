package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import com.emma.bridge.EmmaBridgeMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Till dirt/grass, clear obstacles, place water, and plant seeds to create a farm.
 * <p>
 * State machine: GET_HOE → SCANNING → CLEARING → WORKING → WATERING → DONE
 * <p>
 * Fixes from v1: isolated hoe-crafting phase (no cycling), single-pass till+plant
 * (prevents farmland reversion), clears obstacles at y+1 (snow, tall grass),
 * breaks non-tillable blocks at y and replaces with dirt, optional water placement.
 */
public class CreateFarmTask extends Task {

    private static final Item[] ALL_HOES = {
            Items.NETHERITE_HOE, Items.DIAMOND_HOE, Items.IRON_HOE,
            Items.STONE_HOE, Items.GOLDEN_HOE, Items.WOODEN_HOE
    };

    private static final Item[] ALL_SEEDS = {
            Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS, Items.PUMPKIN_SEEDS,
            Items.POTATO, Items.CARROT
    };

    private final BlockPos center;
    private final int radius;

    private enum Phase { GET_HOE, SCANNING, CLEARING, WORKING, WATERING, DONE }

    /** Per-position action tag. */
    private enum Action { TILL, WATER }

    private record WorkEntry(BlockPos pos, Action action) {}

    private Phase phase;
    private List<BlockPos> toClear;
    private List<WorkEntry> workList;
    private List<BlockPos> waterPositions;
    private int currentIndex;
    private int clearCount;
    private int tillCount;
    private int plantCount;
    private int waterCount;
    private int breakCount;

    /** Ticks waiting at current block (for reach + interaction delay). */
    private int waitTicks;
    private static final int MAX_WAIT_TICKS = 10 * 20; // 10 seconds per block

    /** Sub-state for multi-tick block breaking in WORKING phase. */
    private boolean breakingBlock;
    private int breakTicks;

    /** Sub-state for multi-tick water dump (needs 2 ticks: face, then interact). */
    private int waterDumpTicks;

    /** Sub-state within WORKING: false = till step, true = plant step. */
    private boolean plantStep;

    public CreateFarmTask(BlockPos center, int radius) {
        this.center = center;
        this.radius = radius;
    }

    @Override
    protected void onStart() {
        phase = Phase.GET_HOE;
        toClear = new ArrayList<>();
        workList = new ArrayList<>();
        waterPositions = new ArrayList<>();
        currentIndex = 0;
        clearCount = 0;
        tillCount = 0;
        plantCount = 0;
        waterCount = 0;
        breakCount = 0;
        waitTicks = 0;
        breakingBlock = false;
        breakTicks = 0;
        waterDumpTicks = 0;
        plantStep = false;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        switch (phase) {
            // ── GET_HOE: acquire a hoe before doing any field work ──
            case GET_HOE -> {
                if (mod.getItemStorage().hasItem(ALL_HOES)) {
                    phase = Phase.SCANNING;
                    return null;
                }
                setDebugState("Getting a wooden hoe");
                return TaskCatalogue.getItemTask("wooden_hoe", 1);
            }

            // ── SCANNING: build work lists ──────────────────────────
            case SCANNING -> {
                scanArea();
                EmmaBridgeMod.LOGGER.info("[CreateFarm] Scan: {} to clear, {} work entries, {} water positions",
                        toClear.size(), workList.size(), waterPositions.size());
                if (toClear.isEmpty() && workList.isEmpty()) {
                    mod.log("No work to do at " + center + " r=" + radius);
                    phase = Phase.DONE;
                } else if (!toClear.isEmpty()) {
                    phase = Phase.CLEARING;
                    currentIndex = 0;
                    waitTicks = 0;
                } else {
                    phase = Phase.WORKING;
                    currentIndex = 0;
                    waitTicks = 0;
                    plantStep = false;
                }
                return null;
            }

            // ── CLEARING: break blocks at y+1 (snow, tall grass, flowers) ──
            case CLEARING -> {
                if (currentIndex >= toClear.size()) {
                    EmmaBridgeMod.LOGGER.info("[CreateFarm] Cleared {} obstacles", clearCount);
                    phase = Phase.WORKING;
                    currentIndex = 0;
                    waitTicks = 0;
                    plantStep = false;
                    return null;
                }

                BlockPos target = toClear.get(currentIndex);
                World world = MinecraftClient.getInstance().world;
                if (world != null && world.getBlockState(target).isAir()) {
                    currentIndex++;
                    waitTicks = 0;
                    return null;
                }

                if (waitTicks > MAX_WAIT_TICKS) {
                    currentIndex++;
                    waitTicks = 0;
                    return null;
                }

                if (!BlockInteraction.isInReach(mod, target)) {
                    setDebugState("Clearing " + (currentIndex + 1) + "/" + toClear.size());
                    waitTicks++;
                    return new GetToBlockTask(target);
                }

                LookHelper.lookAt(mod, target);
                BlockInteraction.startBreaking(mod, target, Direction.UP);
                clearCount++;
                currentIndex++;
                waitTicks = 0;
                return null;
            }

            // ── WORKING: single-pass till + plant per block ─────────
            case WORKING -> {
                if (currentIndex >= workList.size()) {
                    EmmaBridgeMod.LOGGER.info("[CreateFarm] Tilled {}, planted {}, broke {}",
                            tillCount, plantCount, breakCount);
                    if (!waterPositions.isEmpty()) {
                        phase = Phase.WATERING;
                        currentIndex = 0;
                        waitTicks = 0;
                        waterDumpTicks = 0;
                    } else {
                        mod.log("Farm created: tilled " + tillCount + ", planted " + plantCount);
                        phase = Phase.DONE;
                    }
                    return null;
                }

                WorkEntry entry = workList.get(currentIndex);
                // Skip water-tagged entries (handled in WATERING phase)
                if (entry.action == Action.WATER) {
                    currentIndex++;
                    waitTicks = 0;
                    plantStep = false;
                    return null;
                }

                BlockPos target = entry.pos;
                World world = MinecraftClient.getInstance().world;

                if (waitTicks > MAX_WAIT_TICKS) {
                    EmmaBridgeMod.LOGGER.info("[CreateFarm] Timeout at {}, skipping", target);
                    currentIndex++;
                    waitTicks = 0;
                    plantStep = false;
                    breakingBlock = false;
                    return null;
                }

                if (!BlockInteraction.isInReach(mod, target)) {
                    setDebugState("Working " + (currentIndex + 1) + "/" + workList.size());
                    waitTicks++;
                    return new GetToBlockTask(target);
                }

                if (world == null) return null;
                BlockState state = world.getBlockState(target);

                // ── Sub-state: breaking a non-tillable block ──
                if (breakingBlock) {
                    if (state.isAir() || isTillable(state) || state.getBlock() instanceof FarmlandBlock) {
                        // Break complete — now place dirt if the block is air
                        breakingBlock = false;
                        breakTicks = 0;
                        if (state.isAir() && mod.getSlotHandler().forceEquipItem(Items.DIRT)) {
                            LookHelper.lookAt(mod, target.down());
                            BlockInteraction.rightClickBlock(mod, target.down(), Direction.UP);
                        }
                        return null; // re-evaluate this position next tick
                    }
                    // Continue breaking
                    breakTicks++;
                    LookHelper.lookAt(mod, target);
                    if (breakTicks == 1) {
                        BlockInteraction.startBreaking(mod, target, null);
                    } else {
                        BlockInteraction.continueBreaking(mod, target, null);
                    }
                    waitTicks++;
                    return null;
                }

                // ── Plant step: just tilled, now plant seeds ──
                if (plantStep) {
                    BlockState current = world.getBlockState(target);
                    if (current.getBlock() instanceof FarmlandBlock && world.getBlockState(target.up()).isAir()) {
                        if (mod.getSlotHandler().forceEquipItem(ALL_SEEDS)) {
                            LookHelper.lookAt(mod, target);
                            BlockInteraction.rightClickBlock(mod, target, Direction.UP);
                            plantCount++;
                        }
                        // No seeds = skip planting, still advance
                    }
                    plantStep = false;
                    currentIndex++;
                    waitTicks = 0;
                    return null;
                }

                // ── Main step: evaluate block and act ──
                if (state.getBlock() instanceof FarmlandBlock) {
                    // Already farmland — just plant
                    if (world.getBlockState(target.up()).isAir() && mod.getSlotHandler().forceEquipItem(ALL_SEEDS)) {
                        LookHelper.lookAt(mod, target);
                        BlockInteraction.rightClickBlock(mod, target, Direction.UP);
                        plantCount++;
                    }
                    currentIndex++;
                    waitTicks = 0;
                    return null;
                }

                if (isTillable(state)) {
                    // Till it
                    if (!mod.getSlotHandler().forceEquipItem(ALL_HOES)) {
                        return null; // equip queued
                    }
                    LookHelper.lookAt(mod, target);
                    BlockInteraction.rightClickBlock(mod, target, Direction.UP);
                    tillCount++;
                    plantStep = true; // next tick: plant seeds on this block
                    return null;
                }

                // Not tillable, not farmland, not air — break it
                if (!state.isAir()) {
                    breakingBlock = true;
                    breakTicks = 0;
                    breakCount++;
                    return null; // will start breaking next tick
                }

                // Air — skip (could place dirt here, but needs dirt in inventory)
                currentIndex++;
                waitTicks = 0;
                return null;
            }

            // ── WATERING: place water sources at grid positions ─────
            case WATERING -> {
                if (currentIndex >= waterPositions.size()) {
                    mod.log("Farm created: tilled " + tillCount + ", planted " + plantCount
                            + ", water " + waterCount);
                    phase = Phase.DONE;
                    return null;
                }

                BlockPos target = waterPositions.get(currentIndex);

                if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    mod.log("No water buckets — place water manually at "
                            + (waterPositions.size() - currentIndex) + " positions");
                    phase = Phase.DONE;
                    return null;
                }

                if (waitTicks > MAX_WAIT_TICKS) {
                    currentIndex++;
                    waitTicks = 0;
                    waterDumpTicks = 0;
                    return null;
                }

                if (!BlockInteraction.isInReach(mod, target)) {
                    setDebugState("Watering " + (currentIndex + 1) + "/" + waterPositions.size());
                    waitTicks++;
                    return new GetToBlockTask(target);
                }

                World world = MinecraftClient.getInstance().world;
                if (world != null) {
                    BlockState state = world.getBlockState(target);
                    // Break solid block at water position first
                    if (!state.isAir() && !state.getFluidState().isStill()) {
                        LookHelper.lookAt(mod, target);
                        BlockInteraction.startBreaking(mod, target, null);
                        waitTicks++;
                        return null;
                    }
                }

                // tryDumpFluid handles look-gating (needs 2 ticks)
                if (BlockInteraction.tryDumpFluid(mod, target, Items.WATER_BUCKET)) {
                    waterCount++;
                    currentIndex++;
                    waitTicks = 0;
                    waterDumpTicks = 0;
                } else {
                    waterDumpTicks++;
                    waitTicks++;
                }
                return null;
            }

            case DONE -> {
                return null;
            }
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Nothing to clean up
    }

    @Override
    public boolean isFinished() {
        return phase == Phase.DONE;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CreateFarmTask t) {
            return t.center.equals(center) && t.radius == radius;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "CreateFarm(" + center.toShortString() + " r=" + radius
                + " phase=" + phase + " idx=" + currentIndex + ")";
    }

    // ── Scanning ─────────────────────────────────────────────────

    private void scanArea() {
        toClear.clear();
        workList.clear();
        waterPositions.clear();

        World world = MinecraftClient.getInstance().world;
        if (world == null) return;

        int y = center.getY();
        int cx = center.getX();
        int cz = center.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                BlockPos pos = new BlockPos(x, y, z);
                BlockPos above = pos.up();

                // Check y+1 for obstacles to clear (snow, tall grass, flowers, ferns)
                BlockState aboveState = world.getBlockState(above);
                if (!aboveState.isAir() && !aboveState.getFluidState().isStill()) {
                    // Don't clear crops that are already growing
                    if (!(world.getBlockState(pos).getBlock() instanceof FarmlandBlock)) {
                        toClear.add(above);
                    }
                }

                // Determine action for this position
                boolean isWaterPos = isWaterGridPosition(dx, dz);
                if (isWaterPos) {
                    waterPositions.add(pos);
                    workList.add(new WorkEntry(pos, Action.WATER));
                } else {
                    workList.add(new WorkEntry(pos, Action.TILL));
                }
            }
        }
    }

    /**
     * Water grid: one water source per 9x9 area.
     * Positions where both relative coords are multiples of 9
     * (offset by 4 so water is centered in each 9x9 plot).
     */
    private boolean isWaterGridPosition(int relX, int relZ) {
        // Shift so water falls at center of 9x9 plots
        int wx = ((relX % 9) + 9) % 9; // normalize to 0-8
        int wz = ((relZ % 9) + 9) % 9;
        return wx == 4 && wz == 4;
    }

    private static boolean isTillable(BlockState state) {
        return state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.DIRT_PATH);
    }
}
