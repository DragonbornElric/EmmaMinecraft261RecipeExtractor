package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.StoreInAnyContainerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import baritone.api.BaritoneAPI;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.ISchematic;
import net.minecraft.block.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AltoClef Task that wraps Baritone's BuilderProcess for multi-block schematic builds.
 *
 * Three-phase construction pipeline:
 *   Phase 1 (CLEARING): Uses Baritone's clearArea() to remove all terrain, trees, and
 *     obstacles in the schematic footprint. During clearing, Baritone's pathfinder has
 *     full access to pillar-up and scaffolding movements (no placement penalties).
 *   Phase 2 (BUILDING): Runs Baritone's builder with the actual schematic on clean ground.
 *     Bottom-up layer construction (layerHeight=2) ensures foundation before walls.
 *     Deferred blocks (doors, torches, ladders, etc.) are skipped via okIfAir.
 *   Phase 3 (FINISHING): Clears okIfAir and disables layer order. Re-runs builder to
 *     place all deferred/attachment blocks. Walls and floors now exist so they can attach.
 *     No layers = Baritone picks by reachability (door first, then interior).
 *
 * By running through AltoClef's UserTaskChain, the build naturally integrates with
 * the priority chain system:
 * - MobDefenseChain can interrupt to fight threats, then building resumes
 * - FoodChain can interrupt to eat, then building resumes
 * - MLGBucketFallChain can save from fall damage
 *
 * Uses BotBehaviour push/pop to isolate state and protect placed schematic blocks
 * from being broken by Baritone's own pathfinding during defense interruptions.
 */
public class BuildSchematicTask extends Task {

    private static final Logger LOGGER = LoggerFactory.getLogger("BuildSchematicTask");

    /** Build phases: site prep → structural → finishing. */
    private enum Phase { CLEARING, BUILDING, FINISHING }

    private final String buildName;
    private final ISchematic schematic;
    private final Vec3i origin;
    private final int totalBlocks;

    private Phase phase = Phase.CLEARING;
    private boolean phaseStarted = false;
    private boolean buildComplete = false;
    private boolean behaviourPushed = false;
    private boolean interruptedByChain = false;
    private int ticksSinceLastProgress = 0;
    private int tickCount = 0;
    private boolean hasJumpedThisCycle = false;

    /** Track actual block-count progress (not just isPathing). */
    private int lastKnownBlockCount = -1;
    private int ticksSinceRealProgress = 0;

    /** Cooldown: ticks remaining before next checkAndGatherMissing scan. */
    private int gatherCheckCooldown = 0;
    /** Remember the last gather item name to avoid re-logging identical gather requests. */
    private String lastGatherItem = "";
    /** Track items that failed to gather — skip them to avoid infinite loops. */
    private final Map<String, Integer> gatherFailCount = new HashMap<>();
    /** Max consecutive failures before blacklisting an item for this build session. */
    private static final int MAX_GATHER_FAILURES = 3;
    /** Whether this is a resume build (skipped clearing due to existing progress). */
    private boolean isResumeBuild = false;
    /** Active gather subtask — persisted across ticks so AltoClef doesn't kill it.
     *  Without this, returning null during cooldown ticks cancels the running gather
     *  task after just 1 tick of execution. Must keep returning the same Task instance
     *  until it completes (isFinished() or AltoClef stops it). */
    private Task activeGatherTask = null;
    /** Item count when gather started — used to detect successful gather. */
    private int gatherStartCount = 0;
    /** Consecutive restart cycles with zero block progress during BUILD phase.
     *  If this exceeds the threshold, we skip to FINISHING — the remaining structural
     *  blocks are unreachable or need ungatherable materials. */
    private int consecutiveNoProgressRestarts = 0;
    /** Placed count at last restart — used to detect whether a restart cycle made progress. */
    private int placedAtLastRestart = -1;
    /** Max consecutive no-progress restarts before skipping to FINISHING phase. */
    private static final int MAX_NO_PROGRESS_RESTARTS = 3;

    /** Ticks with no pathing before we force-uncrouch (5 seconds). */
    private static final int UNCROUCH_TICKS = 100;
    /** Ticks with no progress before we try a jump to unstick (10 seconds). */
    private static final int JUMP_UNSTICK_TICKS = 200;
    /** Maximum ticks with no pathing activity before we consider the build stuck (30 seconds). */
    private static final int STUCK_TIMEOUT_TICKS = 600;
    /** Maximum ticks with no REAL block progress before we restart (45 seconds). */
    private static final int REAL_PROGRESS_TIMEOUT_TICKS = 900;

    /**
     * @param buildName   Display name for the build (e.g. "Wooden House 2")
     * @param schematic   The ISchematic to build (e.g. GuideSchematic)
     * @param origin      World coordinates for the build origin
     * @param totalBlocks Total number of blocks in the schematic (for logging)
     */
    public BuildSchematicTask(String buildName, ISchematic schematic, Vec3i origin, int totalBlocks) {
        this.buildName = buildName;
        this.schematic = schematic;
        this.origin = origin;
        this.totalBlocks = totalBlocks;
    }

    @Override
    protected void onStart() {
        tickCount = 0;

        AltoClef mod = AltoClef.getInstance();

        // If resuming after a chain interruption, just resume the builder —
        // don't push behaviour again (it's already pushed from the first onStart).
        if (interruptedByChain) {
            interruptedByChain = false;
            ticksSinceLastProgress = 0;
            IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
            if (builder.isPaused()) {
                builder.resume();
                LOGGER.info("[BuildTask] Resumed builder after chain interruption");
            } else if (!builder.isActive()) {
                // Builder was completely cancelled by the higher-priority chain's
                // own Baritone commands (e.g., MobDefenseChain pathing to a mob).
                // Restart the builder from where we left off in the current phase.
                String label = buildName + (phase == Phase.FINISHING ? " (finishing)" : "");
                builder.build(label, schematic, origin);
                LOGGER.info("[BuildTask] Restarted builder after chain interrupt (builder was cancelled, phase={})", phase);
            }
            LOGGER.info("[BuildTask] Re-entered after chain interrupt. phase={} phaseStarted={} builderActive={}",
                    phase, phaseStarted, builder.isActive());
            return;
        }

        // Fresh start
        phase = Phase.CLEARING;
        phaseStarted = false;
        buildComplete = false;
        ticksSinceLastProgress = 0;

        // Push behaviour state — protects our settings from contaminating other tasks
        // and lets us add block-break protection that auto-reverts on pop().
        mod.getBehaviour().push();
        behaviourPushed = true;

        // Protect schematic blocks from Baritone pathfinding.
        // When MobDefenseChain interrupts and Baritone paths to a mob, it might
        // break blocks in its way — we don't want it breaking our structure.
        mod.getBehaviour().avoidBlockBreaking(pos -> isSchematicBlock(pos));

        // Configure Baritone builder settings for our schematic.
        // Our BuildDB stores blocks without specific state properties (e.g. stairs have
        // no facing, fences have no connections). Baritone's approxPlaceable list uses
        // context-dependent states (player rotation → facing), so without this flag
        // directional blocks like stairs/fences/glass_panes would be treated as "missing"
        // despite being in inventory. buildIgnoreDirection makes sameBlockstate() skip
        // ORIENTATION_PROPS (facing, axis, half, shape, etc.) during matching.
        BaritoneAPI.getSettings().buildIgnoreDirection.value = true;

        // buildIgnoreProperties: additional property names to ignore during block state
        // comparison (sameBlockstate). buildIgnoreDirection covers ORIENTATION_PROPS
        // (facing, axis, half, shape, etc.) but NOT connection or runtime state properties.
        // Without these, blocks like glass panes get placed → auto-connect to neighbors →
        // state changes → Baritone sees mismatch → breaks them → infinite loop.
        List<String> ignoreProps = BaritoneAPI.getSettings().buildIgnoreProperties.value;
        ignoreProps.addAll(java.util.Arrays.asList(
                "north", "south", "east", "west",  // pane/fence/bar/wall auto-connections
                "up",                               // wall cap (auto-computed)
                "waterlogged",                       // irrelevant for matching
                "open",                              // doors, trapdoors, fence gates
                "powered",                           // redstone state
                "occupied",                          // beds
                "hinge"                              // door hinge (auto-determined by placement)
        ));

        // Increase incorrectSize from default 100 to handle our 257+ block schematics.
        // If too low, Baritone only sees a subset of incorrect positions per recalc pass.
        BaritoneAPI.getSettings().incorrectSize.value = 500;

        // Allow Baritone to pull items from the full inventory, not just hotbar.
        // With 20+ item types (gear + materials), most won't fit on the 9-slot hotbar.
        BaritoneAPI.getSettings().allowInventory.value = true;

        // ── Reset settings that may be stale from a previous build ──────────
        // buildInLayers, okIfAir, and buildIgnoreBlocks are global Baritone settings
        // that persist across task instances. A previous build's BUILD/FINISHING phase
        // may have left buildInLayers=true + layerHeight=2, which would cripple
        // clearArea() by only clearing 2 Y-levels at a time.
        BaritoneAPI.getSettings().buildInLayers.value = false;
        BaritoneAPI.getSettings().layerOrder.value = false;
        BaritoneAPI.getSettings().skipFailedLayers.value = false;
        BaritoneAPI.getSettings().okIfAir.value.clear();
        BaritoneAPI.getSettings().buildIgnoreBlocks.value.clear();
        LOGGER.info("[BuildTask] Reset stale settings: buildInLayers=false, okIfAir cleared, buildIgnoreBlocks cleared");

        // NOTE: buildIgnoreBlocks (snow/grass) is NOT set here — it would cause clearArea()
        // to skip snow/grass blocks (desired=air + snow in ignore list = "valid").
        // These are added when transitioning to BUILD phase instead.

        LOGGER.info("[BuildTask] Starting build '{}' ({} blocks) at {} schematic dims={}x{}x{}",
                buildName, totalBlocks, origin, schematic.widthX(), schematic.heightY(), schematic.lengthZ());
        LOGGER.info("[BuildTask] Phase 1: CLEARING site. buildIgnoreDirection=true, incorrectSize=500");

        // Log Baritone settings that affect building
        boolean interactionPaused = mod.getExtraBaritoneSettings().isInteractionPaused();
        LOGGER.info("[BuildTask] interactionPaused={}", interactionPaused);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
        tickCount++;

        // If builder is paused (we paused it during an interruption), resume it.
        // Only log once to avoid spam — defense chain can cause rapid pause/resume cycles.
        if (builder.isPaused()) {
            builder.resume();
            if (tickCount % 100 == 1) {  // Log at most once per 5s
                LOGGER.info("[BuildTask] Resumed builder after interruption (phase={})", phase);
            }
        }

        if (phase == Phase.CLEARING) {
            return tickClearing(mod, builder);
        } else if (phase == Phase.BUILDING) {
            return tickBuilding(mod, builder);
        } else {
            return tickFinishing(mod, builder);
        }
    }

    /**
     * Phase 1: Site clearing — remove non-schematic blocks in the footprint.
     *
     * Smart skip: if any schematic blocks are already correctly placed (i.e. this is
     * a resume of a previous build), skip clearing entirely. Baritone's builder.build()
     * already does a proper diff — it breaks wrong blocks and places correct ones.
     * clearArea() is only useful for fresh builds on raw terrain where the builder
     * would waste time breaking trees/terrain block-by-block.
     *
     * Uses countCorrectBlocks() to detect pre-existing build progress. Even 1 correct
     * schematic block means someone (or a previous build attempt) placed it, so we
     * should NOT nuke the site.
     */
    private Task tickClearing(AltoClef mod, IBuilderProcess builder) {
        if (!builder.isActive()) {
            if (phaseStarted) {
                // clearArea was running and finished — check if the site is actually clear
                int remaining = countNonAirInFootprint(mod);
                if (remaining == 0) {
                    LOGGER.info("[BuildTask] Site clearing complete! Transitioning to BUILD phase.");
                    phase = Phase.BUILDING;
                    phaseStarted = false;
                    ticksSinceLastProgress = 0;
                    hasJumpedThisCycle = false;

                    // Enable layer-based building for the construction phase
                    // Bottom-up, 2-Y-level layers: foundation first, then walls, then roof.
                    enableBuildPhaseSettings();
                    LOGGER.info("[BuildTask] Phase 2: BUILDING. buildInLayers=true (bottom-up, height=2, skipFailed=true)");
                    return null; // Next tick starts build
                }
                // Not fully clear — restart clearArea
                LOGGER.info("[BuildTask] clearArea stopped but {} blocks remain, restarting...", remaining);
            } else {
                // ── Smart skip: detect if build is being resumed ──
                // Count blocks already correctly placed from a previous build attempt.
                // If ANY schematic blocks are already correct, this is a resume — skip
                // clearing to avoid demolishing existing progress. Baritone's builder
                // will handle stray wrong-blocks via its normal diff logic.
                int alreadyCorrect = countCorrectBlocks(mod);
                int remaining = countNonAirInFootprint(mod);

                if (alreadyCorrect > 0) {
                    LOGGER.info("[BuildTask] Detected existing build progress: {}/{} blocks correct, {} stray blocks. Skipping CLEARING — builder will handle diff.",
                            alreadyCorrect, totalBlocks, remaining);
                    phase = Phase.BUILDING;
                    phaseStarted = false;
                    ticksSinceLastProgress = 0;
                    isResumeBuild = true;

                    enableBuildPhaseSettings();
                    // Resume builds: disable layer ordering so Baritone can place
                    // blocks by reachability instead of strict bottom-up. With most
                    // of the structure already built, lower-layer blocks are often
                    // unreachable from inside, causing permanent stuck.
                    BaritoneAPI.getSettings().buildInLayers.value = false;
                    LOGGER.info("[BuildTask] Phase 2: BUILDING (resume). buildInLayers=FALSE (reachability order, structure already partially built)");
                    return null;
                }

                if (remaining == 0) {
                    LOGGER.info("[BuildTask] Site already clear, skipping to BUILD phase.");
                    phase = Phase.BUILDING;
                    phaseStarted = false;
                    ticksSinceLastProgress = 0;

                    enableBuildPhaseSettings();
                    LOGGER.info("[BuildTask] Phase 2: BUILDING. buildInLayers=true (bottom-up, height=2, skipFailed=true)");
                    return null;
                }
                LOGGER.info("[BuildTask] Fresh site — {} non-air blocks to clear, 0 schematic blocks placed", remaining);
            }

            // Start/restart clearArea on the schematic footprint
            BlockPos corner1 = new BlockPos(origin.getX(), origin.getY(), origin.getZ());
            BlockPos corner2 = new BlockPos(
                    origin.getX() + schematic.widthX() - 1,
                    origin.getY() + schematic.heightY() - 1,
                    origin.getZ() + schematic.lengthZ() - 1);
            builder.clearArea(corner1, corner2);
            phaseStarted = true;
            LOGGER.info("[BuildTask] clearArea({} → {}) started", corner1, corner2);
            setDebugState("Clearing site: " + buildName);
            return null;
        }

        // clearArea is active — monitor progress
        setDebugState("Clearing site: " + buildName);
        monitorProgress(mod, builder);
        return null;
    }

    /**
     * Phase 2: Building — place structural schematic blocks on the cleared site.
     * Deferred blocks (doors, torches, ladders, etc.) are skipped via okIfAir.
     */
    private Task tickBuilding(AltoClef mod, IBuilderProcess builder) {
        // If a gather subtask is active, keep returning it so AltoClef runs it.
        // Returning null here would kill the subtask after 1 tick.
        if (activeGatherTask != null) {
            if (activeGatherTask.isFinished()) {
                // Gather completed — check if we actually got the item
                LOGGER.info("[BuildTask] Gather task completed: {}", lastGatherItem);
                // Reset failure count if we got the item
                ClientPlayerEntity player = mod.getPlayer();
                if (player != null && lastGatherItem != null && !lastGatherItem.isEmpty()) {
                    Item targetItem = Registries.ITEM.get(Identifier.of("minecraft:" + lastGatherItem));
                    int currentCount = 0;
                    for (int i = 0; i < player.getInventory().size(); i++) {
                        ItemStack s = player.getInventory().getStack(i);
                        if (!s.isEmpty() && s.getItem() == targetItem) currentCount += s.getCount();
                    }
                    if (currentCount > gatherStartCount) {
                        gatherFailCount.remove(lastGatherItem);
                        LOGGER.info("[BuildTask] Gather succeeded for '{}': {} -> {}", lastGatherItem, gatherStartCount, currentCount);
                    }
                }
                activeGatherTask = null;
                // Restart builder to use newly gathered materials
                builder.build(buildName, schematic, origin);
                phaseStarted = true;
                gatherCheckCooldown = 200; // 10s before next scan
                LOGGER.info("[BuildTask] Restarted builder after gather. builderActive={}", builder.isActive());
                return null;
            }
            // Still running — keep AltoClef executing it
            return activeGatherTask;
        }

        if (!builder.isActive()) {
            if (phaseStarted) {
                // Throttle: only run the expensive schematic scan every 100 ticks (5s)
                // when the builder is idle.  Completion check is cheap enough to run
                // every time because it exits early once it's clear we're not done.
                if (gatherCheckCooldown > 0) {
                    gatherCheckCooldown--;
                    return null;
                }

                // Builder was running and stopped — check if structural blocks are done.
                // During BUILD phase, completion check skips deferred blocks.
                if (checkBuildComplete(mod)) {
                    // Structural pass complete — transition to FINISHING
                    LOGGER.info("[BuildTask] Structural pass complete! Transitioning to FINISHING phase.");
                    phase = Phase.FINISHING;
                    phaseStarted = false;
                    ticksSinceLastProgress = 0;
                    ticksSinceRealProgress = 0;
                    lastKnownBlockCount = -1;
                    hasJumpedThisCycle = false;

                    // Clear the deferred ignore list so Baritone will place them now
                    enableFinishingPhaseSettings();
                    LOGGER.info("[BuildTask] Phase 3: FINISHING. buildIgnoreBlocks cleared, placing deferred blocks.");
                    return null; // Next tick starts finishing
                }

                // Track consecutive restarts with no block progress.
                // If we keep restarting but never place anything, the remaining blocks
                // are unreachable or need materials we can't get — skip to FINISHING.
                // This check runs BEFORE the gather check so that inventory-cleanup
                // gathers (which complete instantly) don't prevent the counter from
                // reaching the threshold.
                int currentPlaced = countCorrectBlocks(mod);
                if (placedAtLastRestart >= 0 && currentPlaced <= placedAtLastRestart) {
                    consecutiveNoProgressRestarts++;
                    LOGGER.info("[BuildTask] No progress since last restart ({} placed, {} consecutive stalls)",
                            currentPlaced, consecutiveNoProgressRestarts);
                    if (consecutiveNoProgressRestarts >= MAX_NO_PROGRESS_RESTARTS) {
                        LOGGER.warn("[BuildTask] BUILD phase stuck after {} restarts with no progress ({} placed). "
                                + "Remaining structural blocks are unreachable — skipping to FINISHING phase.",
                                consecutiveNoProgressRestarts, currentPlaced);
                        phase = Phase.FINISHING;
                        phaseStarted = false;
                        ticksSinceLastProgress = 0;
                        ticksSinceRealProgress = 0;
                        lastKnownBlockCount = -1;
                        hasJumpedThisCycle = false;
                        consecutiveNoProgressRestarts = 0;
                        enableFinishingPhaseSettings();
                        LOGGER.info("[BuildTask] Phase 3: FINISHING (forced). Placing deferred blocks.");
                        return null;
                    }
                } else {
                    consecutiveNoProgressRestarts = 0; // Reset — we made progress
                }
                placedAtLastRestart = currentPlaced;

                // Builder stopped but build isn't complete — check for missing materials.
                // Skip deferred block materials — they're not needed until FINISHING.
                Task gatherTask = checkAndGatherMissing(mod);
                if (gatherTask != null) {
                    // Only reset no-progress counter for REAL gathers (not inventory cleanup).
                    // Cleanup tasks have empty lastGatherItem and complete instantly,
                    // causing the counter to never reach the threshold.
                    if (!lastGatherItem.isEmpty()) {
                        consecutiveNoProgressRestarts = 0;
                    }
                    gatherCheckCooldown = 200; // 10s before next scan after gather completes
                    activeGatherTask = gatherTask;
                    // Record current item count to detect success later
                    ClientPlayerEntity player = mod.getPlayer();
                    if (player != null && !lastGatherItem.isEmpty()) {
                        Item targetItem = Registries.ITEM.get(Identifier.of("minecraft:" + lastGatherItem));
                        gatherStartCount = 0;
                        for (int i = 0; i < player.getInventory().size(); i++) {
                            ItemStack s = player.getInventory().getStack(i);
                            if (!s.isEmpty() && s.getItem() == targetItem) gatherStartCount += s.getCount();
                        }
                    }
                    LOGGER.info("[BuildTask] Starting gather subtask for '{}' (have {})", lastGatherItem, gatherStartCount);
                    return activeGatherTask;
                }

                LOGGER.info("[BuildTask] Builder stopped after {} ticks, restarting...", tickCount);
                gatherCheckCooldown = 100; // Don't re-scan for 5 seconds
            }

            // Log inventory on first build start
            if (!phaseStarted) {
                logInventorySummary(mod);
            }

            builder.build(buildName, schematic, origin);
            phaseStarted = true;
            gatherCheckCooldown = 0; // Reset cooldown — builder just started
            lastGatherItem = "";
            LOGGER.info("[BuildTask] Called builder.build() — builderActive={}", builder.isActive());
            setDebugState("Building: " + buildName);
            return null;
        }

        // Builder is active — monitor
        setDebugState("Building: " + buildName);
        monitorProgress(mod, builder);
        return null;
    }

    /**
     * Phase 3: Finishing — place deferred blocks (doors, torches, ladders, etc.).
     * buildIgnoreBlocks has been cleared so Baritone now tries to place them.
     * Walls and floors already exist from the structural pass.
     */
    private Task tickFinishing(AltoClef mod, IBuilderProcess builder) {
        // If a gather subtask is active, keep returning it (same persistence as BUILD phase)
        if (activeGatherTask != null) {
            if (activeGatherTask.isFinished()) {
                LOGGER.info("[BuildTask] Finishing gather task completed: {}", lastGatherItem);
                ClientPlayerEntity player = mod.getPlayer();
                if (player != null && lastGatherItem != null && !lastGatherItem.isEmpty()) {
                    Item targetItem = Registries.ITEM.get(Identifier.of("minecraft:" + lastGatherItem));
                    int currentCount = 0;
                    for (int i = 0; i < player.getInventory().size(); i++) {
                        ItemStack s = player.getInventory().getStack(i);
                        if (!s.isEmpty() && s.getItem() == targetItem) currentCount += s.getCount();
                    }
                    if (currentCount > gatherStartCount) {
                        gatherFailCount.remove(lastGatherItem);
                        LOGGER.info("[BuildTask] Finishing gather succeeded for '{}': {} -> {}", lastGatherItem, gatherStartCount, currentCount);
                    }
                }
                activeGatherTask = null;
                builder.build(buildName + " (finishing)", schematic, origin);
                phaseStarted = true;
                gatherCheckCooldown = 200;
                LOGGER.info("[BuildTask] Restarted finishing builder after gather. builderActive={}", builder.isActive());
                return null;
            }
            return activeGatherTask;
        }

        if (!builder.isActive()) {
            if (phaseStarted) {
                // Throttle expensive schematic scans (same as BUILD phase)
                if (gatherCheckCooldown > 0) {
                    gatherCheckCooldown--;
                    return null;
                }

                // Builder stopped — check if ALL blocks (including deferred) are placed
                if (checkBuildComplete(mod)) {
                    buildComplete = true;
                    LOGGER.info("[BuildTask] Build '{}' complete! (all blocks including deferred)", buildName);
                    return null;
                }

                // Check for missing finishing materials
                Task gatherTask = checkAndGatherMissing(mod);
                if (gatherTask != null) {
                    gatherCheckCooldown = 200;
                    activeGatherTask = gatherTask;
                    ClientPlayerEntity player = mod.getPlayer();
                    if (player != null && !lastGatherItem.isEmpty()) {
                        Item targetItem = Registries.ITEM.get(Identifier.of("minecraft:" + lastGatherItem));
                        gatherStartCount = 0;
                        for (int i = 0; i < player.getInventory().size(); i++) {
                            ItemStack s = player.getInventory().getStack(i);
                            if (!s.isEmpty() && s.getItem() == targetItem) gatherStartCount += s.getCount();
                        }
                    }
                    LOGGER.info("[BuildTask] Starting finishing gather for '{}' (have {})", lastGatherItem, gatherStartCount);
                    return activeGatherTask;
                }

                // Track consecutive no-progress restarts in FINISHING too.
                // After enough stalls, accept the build as done — remaining blocks
                // are unreachable or need ungatherable materials.
                int currentPlaced = countCorrectBlocks(mod);
                if (placedAtLastRestart >= 0 && currentPlaced <= placedAtLastRestart) {
                    consecutiveNoProgressRestarts++;
                    LOGGER.info("[BuildTask] FINISHING: no progress since last restart ({} correct, {} consecutive stalls)",
                            currentPlaced, consecutiveNoProgressRestarts);
                    if (consecutiveNoProgressRestarts >= MAX_NO_PROGRESS_RESTARTS) {
                        LOGGER.warn("[BuildTask] FINISHING stuck after {} restarts ({} correct/{}). Accepting build as done.",
                                consecutiveNoProgressRestarts, currentPlaced, totalBlocks);
                        buildComplete = true;
                        return null;
                    }
                } else {
                    consecutiveNoProgressRestarts = 0;
                }
                placedAtLastRestart = currentPlaced;

                LOGGER.info("[BuildTask] Finishing builder stopped after {} ticks, restarting...", tickCount);
                gatherCheckCooldown = 100; // Don't re-scan for 5 seconds
            }

            if (!phaseStarted) {
                logInventorySummary(mod);
            }

            builder.build(buildName + " (finishing)", schematic, origin);
            phaseStarted = true;
            gatherCheckCooldown = 0; // Reset cooldown — builder just started
            lastGatherItem = "";
            LOGGER.info("[BuildTask] Called builder.build() for finishing — builderActive={}", builder.isActive());
            setDebugState("Finishing: " + buildName);
            return null;
        }

        setDebugState("Finishing: " + buildName);
        monitorProgress(mod, builder);
        return null;
    }

    /**
     * Compare schematic requirements vs player inventory to find missing materials.
     * If a missing item is found in AltoClef's TaskCatalogue, return a ResourceTask
     * to gather/craft it. AltoClef will run that task, then return to building.
     *
     * During BUILD phase, skips deferred blocks (doors, torches, etc.) since they
     * won't be placed until FINISHING phase. During FINISHING, checks all blocks.
     *
     * Caps gather quantity to what fits in available inventory slots to prevent
     * overflow when many material types are needed. Gathers one type at a time;
     * after each gather, Baritone resumes building and places what it can before
     * the next checkAndGatherMissing call finds the next deficit.
     *
     * @return a gather/craft Task for the first missing item, or null if nothing needed
     */
    private Task checkAndGatherMissing(AltoClef mod) {
        ClientPlayerEntity player = mod.getPlayer();
        ClientWorld world = mod.getWorld();
        if (player == null || world == null) return null;

        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        Set<Block> deferredSet = Set.copyOf(BaritoneAPI.getSettings().okIfAir.value);

        // Count what the schematic still needs (blocks not yet placed in the world)
        Map<Block, Integer> needed = new HashMap<>();
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState desired = schematic.desiredState(x, y, z,
                            Blocks.AIR.getDefaultState(), java.util.List.of());
                    if (desired.isAir()) continue;

                    // Skip deferred blocks during BUILD phase (okIfAir populated)
                    if (deferredSet.contains(desired.getBlock())) continue;

                    BlockPos worldPos = new BlockPos(ox + x, oy + y, oz + z);
                    BlockState actual = world.getBlockState(worldPos);
                    if (actual.getBlock() != desired.getBlock()) {
                        needed.merge(desired.getBlock(), 1, Integer::sum);
                    }
                }
            }
        }

        if (needed.isEmpty()) return null;

        // Count what's in inventory and track free slots
        Map<Item, Integer> inventory = new HashMap<>();
        int usedSlots = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                inventory.merge(stack.getItem(), stack.getCount(), Integer::sum);
                if (i < 36) usedSlots++;  // Only count main inventory (not armor/offhand)
            }
        }
        // Player inventory: 36 main + 4 armor + 1 offhand = 41 total slots
        // Only count main inventory (36 slots) for capacity — armor/offhand aren't usable for materials
        int mainInventorySize = 36;
        int freeSlots = Math.max(0, mainInventorySize - usedSlots);

        // Cap gather quantity: at most 2 stacks (128) per gather call, and never more
        // than what fits in free inventory slots. This prevents overflow when 20+ material
        // types are needed. After gathering one batch, Baritone builds with it, freeing
        // slots, then the next checkAndGatherMissing call gathers the next type.
        int maxGatherPerCall = Math.min(128, freeSlots * 64);
        if (maxGatherPerCall <= 0) {
            // Inventory full — make room by dropping items not needed for the build.
            // Scan the ENTIRE schematic (all phases, including deferred) to know what to keep.
            Set<Item> allSchematicItems = getAllSchematicItems();

            // Check if there are droppable items (not essential, not needed by any phase)
            boolean hasDroppable = false;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                if (isEssentialItem(stack.getItem())) continue;
                if (allSchematicItems.contains(stack.getItem())) continue;
                hasDroppable = true;
                break;
            }

            if (hasDroppable) {
                // Build ItemTarget list of unneeded items to deposit into nearest chest
                java.util.List<ItemTarget> depositList = new java.util.ArrayList<>();
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = player.getInventory().getStack(i);
                    if (stack.isEmpty()) continue;
                    if (isEssentialItem(stack.getItem())) continue;
                    if (allSchematicItems.contains(stack.getItem())) continue;
                    depositList.add(new ItemTarget(stack.getItem(), stack.getCount()));
                }
                LOGGER.info("[BuildTask] Inventory full ({} used slots) — depositing {} unneeded item types to nearby chest.", usedSlots, depositList.size());
                return new StoreInAnyContainerTask(false, depositList.toArray(new ItemTarget[0]));
            }

            // All items are schematic materials or essential — can't free space by type.
            // Let Baritone try to build with what we have (placing blocks frees slots).
            LOGGER.info("[BuildTask] Inventory full ({} used slots), all items are needed or essential. Waiting for Baritone to place blocks.", usedSlots);
            return null;
        }

        // Find first block type that's needed but not in inventory
        for (Map.Entry<Block, Integer> entry : needed.entrySet()) {
            Block block = entry.getKey();
            int count = entry.getValue();
            Item blockItem = block.asItem();

            // Check if this item's block matches (some blocks have different items)
            if (blockItem == null || blockItem == Items.AIR) continue;

            int haveCount = inventory.getOrDefault(blockItem, 0);
            if (haveCount >= count) continue;  // Have enough

            int deficit = count - haveCount;
            // Cap to what fits in inventory — gather in batches, not all at once
            int gatherQty = Math.min(deficit, maxGatherPerCall);
            String itemName = Registries.ITEM.getId(blockItem).getPath();

            // Skip items that have failed too many times (likely ungatherable)
            int failures = gatherFailCount.getOrDefault(itemName, 0);
            if (failures >= MAX_GATHER_FAILURES) {
                if (failures == MAX_GATHER_FAILURES) {
                    LOGGER.warn("[BuildTask] Blacklisted '{}' after {} failed gather attempts — skipping for this build session.",
                            itemName, MAX_GATHER_FAILURES);
                    gatherFailCount.put(itemName, failures + 1); // prevent re-logging
                }
                continue;
            }

            // Check if AltoClef knows how to get this item
            if (TaskCatalogue.taskExists(itemName)) {
                LOGGER.info("[BuildTask] Missing material: {} x{} (have {}, gathering {} of {} deficit, attempt {}). Free slots: {}",
                        itemName, deficit, haveCount, gatherQty, deficit, failures + 1, freeSlots);
                lastGatherItem = itemName;
                gatherFailCount.merge(itemName, 1, Integer::sum);
                return TaskCatalogue.getItemTask(itemName, gatherQty);
            } else if (TaskCatalogue.taskExists(blockItem)) {
                LOGGER.info("[BuildTask] Missing material: {} x{} (have {}, gathering {} of {} deficit, attempt {}). Free slots: {}",
                        itemName, deficit, haveCount, gatherQty, deficit, failures + 1, freeSlots);
                lastGatherItem = itemName;
                gatherFailCount.merge(itemName, 1, Integer::sum);
                return TaskCatalogue.getItemTask(blockItem, gatherQty);
            } else {
                LOGGER.warn("[BuildTask] Missing material: {} x{} — NOT in TaskCatalogue, cannot auto-gather.",
                        itemName, deficit);
            }
        }

        return null;
    }

    /**
     * Scan the entire schematic to build a set of all items needed across ALL build phases.
     * Includes both structural and deferred blocks — used during inventory cleanup to
     * decide what items to keep vs drop. Does NOT filter by okIfAir.
     */
    private Set<Item> getAllSchematicItems() {
        Set<Item> items = new HashSet<>();
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState desired = schematic.desiredState(x, y, z,
                            Blocks.AIR.getDefaultState(), java.util.List.of());
                    if (desired.isAir()) continue;
                    Item item = desired.getBlock().asItem();
                    if (item != null && item != Items.AIR) {
                        items.add(item);
                    }
                }
            }
        }
        return items;
    }

    /**
     * Determines if an item is essential and should never be dropped during inventory cleanup.
     * Essential: tools/weapons/armor (have durability) and food.
     */
    private static boolean isEssentialItem(Item item) {
        // Tools, weapons, armor, shields: characterized by having durability
        if (item.getDefaultStack().getMaxDamage() > 0) return true;
        // Food items
        if (ItemVer.isFood(item)) return true;
        return false;
    }

    /**
     * Common progress monitoring for both phases: stuck detection, auto-jump, restart.
     */
    private void monitorProgress(AltoClef mod, IBuilderProcess builder) {
        boolean isPathing = mod.getClientBaritone().getPathingBehavior().isPathing();
        ClientPlayerEntity player = mod.getPlayer();
        boolean isSneaking = player != null && player.isSneaking();

        if (isPathing) {
            ticksSinceLastProgress = 0;
            hasJumpedThisCycle = false;
        } else {
            ticksSinceLastProgress++;
        }

        // Track REAL progress (actual block count changes) every 5 seconds.
        // isPathing can be true while crouched and stuck — this catches that.
        if (tickCount % 100 == 0) {
            int currentCount;
            if (phase == Phase.BUILDING || phase == Phase.FINISHING) {
                currentCount = countPlacedBlocks(mod);
                String phaseLabel = phase == Phase.FINISHING ? "FINISH" : "BUILD";
                LOGGER.info("[BuildTask] tick={} phase={} pathing={} sneaking={} stuckTicks={} realStuck={} placed={}",
                        tickCount, phaseLabel, isPathing, isSneaking, ticksSinceLastProgress, ticksSinceRealProgress, currentCount);
            } else {
                currentCount = countNonAirInFootprint(mod);
                LOGGER.info("[BuildTask] tick={} phase=CLEAR pathing={} sneaking={} stuckTicks={} realStuck={} remaining={}",
                        tickCount, isPathing, isSneaking, ticksSinceLastProgress, ticksSinceRealProgress, currentCount);
            }

            if (lastKnownBlockCount < 0) {
                lastKnownBlockCount = currentCount;
            } else if (currentCount != lastKnownBlockCount) {
                // Real progress happened
                lastKnownBlockCount = currentCount;
                ticksSinceRealProgress = 0;
            } else {
                ticksSinceRealProgress += 100; // 5 more seconds with no change
            }
        }

        // Stage 1: Force-uncrouch after 5s of no pathing progress.
        // Baritone crouches to place blocks on edges but sometimes gets stuck sneaking.
        if (ticksSinceLastProgress == UNCROUCH_TICKS) {
            if (player != null && player.isSneaking()) {
                player.setSneaking(false);
                LOGGER.info("[BuildTask] Force-uncrouch: player was stuck sneaking (phase={})", phase);
            }
        }

        // Stage 2: Auto-jump after 10s of no pathing progress
        if (ticksSinceLastProgress == JUMP_UNSTICK_TICKS && !hasJumpedThisCycle) {
            if (player != null && player.isOnGround()) {
                // Also uncrouch before jumping in case we missed it
                if (player.isSneaking()) {
                    player.setSneaking(false);
                }
                player.jump();
                hasJumpedThisCycle = true;
                LOGGER.info("[BuildTask] Auto-jump to unstick pathfinding (phase={}, stuckTicks={})",
                        phase, ticksSinceLastProgress);
            }
        }

        // Stage 3: Restart after 30s of no pathing activity
        if (ticksSinceLastProgress > STUCK_TIMEOUT_TICKS) {
            LOGGER.warn("[BuildTask] No pathing for 30s (phase={}), restarting...", phase);
            if (player != null && player.isSneaking()) {
                player.setSneaking(false);
            }
            builder.onLostControl();
            ticksSinceLastProgress = 0;
            ticksSinceRealProgress = 0;
            lastKnownBlockCount = -1;
            hasJumpedThisCycle = false;
        }

        // Stage 4: Restart if no REAL block progress for 45s (catches isPathing-true loops)
        if (ticksSinceRealProgress > REAL_PROGRESS_TIMEOUT_TICKS) {
            LOGGER.warn("[BuildTask] No real block progress for 45s (phase={}, pathing={}), restarting...",
                    phase, isPathing);
            if (player != null && player.isSneaking()) {
                player.setSneaking(false);
            }
            builder.onLostControl();
            ticksSinceLastProgress = 0;
            ticksSinceRealProgress = 0;
            lastKnownBlockCount = -1;
            hasJumpedThisCycle = false;
        }
    }

    /**
     * Enable settings specific to the BUILD phase (not appropriate during CLEARING).
     * With a cleared site, buildInLayers gives structural ordering (foundation → walls
     * → roof). layerHeight=2 handles all double-block items (doors, tall flowers).
     * skipFailedLayers prevents the builder from self-pausing if any block in a layer
     * is temporarily unreachable — it moves on to the next layer instead.
     *
     * Also populates okIfAir with the full Deferred Block Registry —
     * blocks that can't be reliably placed until walls/floors exist (attachment-
     * dependent) or that cause right-click interaction issues (interactable).
     *
     * KEY BARITONE SEMANTICS:
     *  - buildIgnoreBlocks = "if schematic wants AIR and this block is there, don't clear it"
     *    → used for snow/grass (tolerate at air positions)
     *  - okIfAir = "if position is AIR and schematic wants this block, skip placing it"
     *    → used for deferred blocks (don't place doors/torches/etc. during BUILD)
     */
    private void enableBuildPhaseSettings() {
        // Build bottom-up, 2 Y-levels per pass (cumulative: pass 1 sees Y[0-1],
        // pass 2 sees Y[0-3], pass 3 sees Y[0-5], etc.).
        BaritoneAPI.getSettings().buildInLayers.value = true;
        BaritoneAPI.getSettings().layerOrder.value = false;  // bottom-up
        BaritoneAPI.getSettings().layerHeight.value = 2;
        // Skip layers if stuck instead of self-pausing ("Unable to do it. Pausing.")
        BaritoneAPI.getSettings().skipFailedLayers.value = true;

        // buildIgnoreBlocks: "if schematic wants AIR here and this block exists, don't
        // remove it." Correct for snow/grass — we don't care about them at air positions.
        List<Block> ignoreAtAir = BaritoneAPI.getSettings().buildIgnoreBlocks.value;
        ignoreAtAir.add(Blocks.SNOW);
        ignoreAtAir.add(Blocks.SHORT_GRASS);
        ignoreAtAir.add(Blocks.TALL_GRASS);

        // ── Deferred Block Registry ──────────────────────────────────────────
        // okIfAir: "if position is currently AIR and schematic wants this block type,
        // treat it as valid (skip placing it)." This is how we defer blocks to FINISHING.
        //
        // Two categories:
        //  Category 1: Attachment-dependent — need a wall/floor/ceiling to attach to.
        //    Baritone's getPlacementState() simulates in empty context → returns null.
        //  Category 2: Interactable — right-click opens a GUI or toggles state instead
        //    of placing adjacent blocks. Causes right-click loops during BUILD.
        //
        // Uses instanceof to catch all variants automatically (e.g. DoorBlock catches
        // oak_door, iron_door, spruce_door, etc.)
        List<Block> okIfAir = BaritoneAPI.getSettings().okIfAir.value;

        int deferredCount = 0;
        for (Block block : Registries.BLOCK) {
            boolean defer = false;

            // Category 1: Attachment-dependent
            if (block instanceof LadderBlock
                    || block instanceof TorchBlock          // torch, soul_torch
                    || block instanceof WallTorchBlock       // wall_torch, soul_wall_torch, redstone_wall_torch
                    || block instanceof ButtonBlock          // stone_button, oak_button, etc.
                    || block instanceof AbstractSignBlock    // all sign variants (wall, post, hanging)
                    || block instanceof LeverBlock
                    || block instanceof LanternBlock
                    || block instanceof VineBlock
                    || block instanceof PressurePlateBlock   // all pressure plates
                    || block instanceof CarpetBlock
                    || block instanceof PlantBlock           // flowers, saplings, crops, sugar_cane
                    || block instanceof FlowerPotBlock       // all potted variants
                    || block instanceof DeadCoralWallFanBlock
                    || block instanceof ScaffoldingBlock
                    || block instanceof ChainBlock
                    || block instanceof LightningRodBlock
                    || block instanceof LilyPadBlock
                    || block instanceof AbstractCandleBlock) {
                defer = true;
            }

            // Category 2: Interactable
            if (block instanceof DoorBlock              // oak_door, iron_door, etc.
                    || block instanceof TrapdoorBlock
                    || block instanceof FenceGateBlock
                    || block instanceof ChestBlock          // chest, trapped_chest
                    || block instanceof EnderChestBlock
                    || block instanceof BarrelBlock
                    || block instanceof ShulkerBoxBlock
                    || block instanceof HopperBlock
                    || block instanceof AbstractFurnaceBlock // furnace, smoker, blast_furnace
                    || block instanceof CraftingTableBlock
                    || block instanceof CartographyTableBlock
                    || block instanceof LoomBlock
                    || block instanceof StonecutterBlock
                    || block instanceof GrindstoneBlock
                    || block instanceof AnvilBlock
                    || block instanceof EnchantingTableBlock
                    || block instanceof BrewingStandBlock
                    || block instanceof LecternBlock
                    || block instanceof CrafterBlock
                    || block instanceof DispenserBlock      // dispenser + dropper
                    || block instanceof NoteBlock
                    || block instanceof RepeaterBlock
                    || block instanceof ComparatorBlock
                    || block instanceof JukeboxBlock
                    || block instanceof CampfireBlock
                    || block instanceof ComposterBlock
                    || block instanceof CauldronBlock       // all cauldron variants
                    || block instanceof BedBlock
                    || block instanceof CakeBlock) {
                defer = true;
            }

            if (defer) {
                okIfAir.add(block);
                deferredCount++;
            }
        }
        LOGGER.info("[BuildTask] Deferred {} block types to FINISHING phase via okIfAir (attachment + interactable)", deferredCount);
    }

    /**
     * Enable settings for FINISHING phase — clears okIfAir so Baritone will now
     * attempt to place doors, torches, ladders, etc. that were deferred.
     * Walls and floors already exist from the structural BUILD phase.
     *
     * buildIgnoreBlocks (snow/grass) stays untouched — still valid.
     */
    private void enableFinishingPhaseSettings() {
        // Clear okIfAir — we want to place ALL deferred blocks now.
        // buildIgnoreBlocks (snow/grass) stays — that's a different concern
        // ("don't clear snow where schematic wants air").
        BaritoneAPI.getSettings().okIfAir.value.clear();

        // Disable layer-based building for FINISHING. With layers on, Baritone
        // tries layer 1 blocks (which may be INSIDE the sealed structure) before
        // the door is placed, causing it to get stuck. Without layers, Baritone
        // freely picks blocks by reachability — it places the door first (reachable
        // from outside), then paths inside for interior items (torches, ladders, etc.).
        BaritoneAPI.getSettings().buildInLayers.value = false;
    }

    /**
     * Count blocks in the schematic footprint that need to be cleared.
     * Only counts positions where the schematic wants AIR but the world has a
     * non-air block. Positions where the schematic wants a non-air block are
     * NOT counted — those are supposed to be there (or will be replaced during BUILD).
     *
     * This prevents the clearing phase from looping forever when a previous build
     * already placed schematic blocks in the footprint.
     */
    private int countNonAirInFootprint(AltoClef mod) {
        ClientWorld world = mod.getWorld();
        if (world == null) return -1;

        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int count = 0;
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState desired = schematic.desiredState(x, y, z,
                            Blocks.AIR.getDefaultState(), java.util.List.of());
                    // Only count positions where the schematic wants air but world has something
                    if (desired.isAir()) {
                        BlockState actual = world.getBlockState(new BlockPos(ox + x, oy + y, oz + z));
                        if (!actual.isAir()) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * Count schematic blocks that are already correctly placed in the world.
     * Used to detect whether this is a fresh build or a resume of a previous attempt.
     * Checks ALL non-air schematic positions (ignoring okIfAir/deferred status) and
     * compares block type only (ignores orientation/state per buildIgnoreDirection).
     *
     * @return number of positions where world block matches schematic block
     */
    private int countCorrectBlocks(AltoClef mod) {
        ClientWorld world = mod.getWorld();
        if (world == null) return 0;

        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int correct = 0;
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState desired = schematic.desiredState(x, y, z,
                            Blocks.AIR.getDefaultState(), java.util.List.of());
                    if (desired.isAir()) continue;
                    BlockState actual = world.getBlockState(new BlockPos(ox + x, oy + y, oz + z));
                    if (actual.getBlock() == desired.getBlock()) {
                        correct++;
                    }
                }
            }
        }
        return correct;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null) return;

        IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();

        // IMPORTANT: SingleTaskChain.onInterrupt() calls Task.interrupt(null),
        // so interruptTask is ALWAYS null whether it's an interruption or clean stop.
        // We use isActive() to distinguish: if the task is still active after onStop,
        // it was interrupted (Task.interrupt keeps active=true); if not, it was stopped
        // (Task.stop sets active=false AFTER onStop).
        //
        // Since stop() calls onStop() BEFORE setting active=false, we can't use
        // isActive() either. Instead, we track it ourselves: if buildComplete is true
        // or if stopped() is about to be set, it's a clean stop. If not, it's an interrupt.
        //
        // Simplest reliable approach: check if the build is done or being cancelled.
        // If buildComplete, we're done. Otherwise, assume interruption and pause.
        if (!buildComplete) {
            // Interrupted by a higher-priority chain (MobDefense, Food, etc.)
            // Pause the builder to preserve its internal state — resume on next onStart().
            if (builder.isActive()) {
                builder.pause();
                LOGGER.info("[BuildTask] Paused (chain interrupted, phase={}, tick={})", phase, tickCount);
            }
            // Mark that we were interrupted by a chain — onStart will resume instead of re-init
            interruptedByChain = true;
            // Keep behaviourPushed — don't pop, we're coming back
        } else {
            // Clean stop — build is complete. Fully release builder.
            if (builder.isActive()) {
                builder.onLostControl();
            }

            // Pop behaviour state — removes our block-break protection
            if (behaviourPushed) {
                mod.getBehaviour().pop();
                behaviourPushed = false;
                LOGGER.info("[BuildTask] Popped behaviour state (build complete)");
            }

            // Restore Baritone settings to defaults
            BaritoneAPI.getSettings().buildIgnoreDirection.value = false;
            BaritoneAPI.getSettings().incorrectSize.value = 100;
            BaritoneAPI.getSettings().allowInventory.value = false;
            BaritoneAPI.getSettings().buildInLayers.value = false;
            BaritoneAPI.getSettings().layerOrder.value = false;
            BaritoneAPI.getSettings().layerHeight.value = 1;
            BaritoneAPI.getSettings().skipFailedLayers.value = false;
            // Clear all settings we added
            BaritoneAPI.getSettings().buildIgnoreBlocks.value.clear();    // snow/grass
            BaritoneAPI.getSettings().okIfAir.value.clear();              // deferred blocks
            BaritoneAPI.getSettings().buildIgnoreProperties.value.clear();// connection/state props
        }
    }

    @Override
    public boolean isFinished() {
        return buildComplete;
    }

    /**
     * Check if schematic blocks are placed in the world.
     *
     * During BUILD phase: skips blocks in okIfAir (deferred blocks).
     * This means 100% completion = all STRUCTURAL blocks placed.
     *
     * During FINISHING phase: checks ALL blocks (okIfAir is cleared).
     * 100% completion = everything placed.
     *
     * This is the definitive completion check — doesn't rely on builder state.
     */
    private boolean checkBuildComplete(AltoClef mod) {
        ClientWorld world = mod.getWorld();
        if (world == null) return false;

        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        Set<Block> deferredSet = Set.copyOf(BaritoneAPI.getSettings().okIfAir.value);

        int missing = 0;
        int placed = 0;
        int deferred = 0;
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState desired = schematic.desiredState(x, y, z,
                            Blocks.AIR.getDefaultState(), java.util.List.of());
                    if (desired.isAir()) continue; // Don't check air positions

                    // Skip deferred blocks during BUILD phase (okIfAir is populated)
                    if (deferredSet.contains(desired.getBlock())) {
                        deferred++;
                        continue;
                    }

                    BlockPos worldPos = new BlockPos(ox + x, oy + y, oz + z);
                    BlockState actual = world.getBlockState(worldPos);

                    // Check if the block type matches (ignore exact state properties
                    // like facing — Baritone handles orientation)
                    if (actual.getBlock() != desired.getBlock()) {
                        missing++;
                    } else {
                        placed++;
                    }
                }
            }
        }

        LOGGER.info("[BuildTask] Completion check (phase={}) — placed={} missing={} deferred={} total={}",
                phase, placed, missing, deferred, totalBlocks);
        return missing == 0;
    }

    /**
     * Quick count of how many schematic blocks have been placed.
     * Used for periodic tick logging without the full completion overhead.
     * Respects okIfAir — during BUILD phase, only counts structural blocks.
     */
    private int countPlacedBlocks(AltoClef mod) {
        ClientWorld world = mod.getWorld();
        if (world == null) return -1;

        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        Set<Block> deferredSet = Set.copyOf(BaritoneAPI.getSettings().okIfAir.value);
        int placed = 0;
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState desired = schematic.desiredState(x, y, z,
                            Blocks.AIR.getDefaultState(), java.util.List.of());
                    if (desired.isAir()) continue;
                    if (deferredSet.contains(desired.getBlock())) continue;
                    BlockState actual = world.getBlockState(new BlockPos(ox + x, oy + y, oz + z));
                    if (actual.getBlock() == desired.getBlock()) placed++;
                }
            }
        }
        return placed;
    }

    /**
     * Log a summary of the player's inventory — helps debug "no blocks placed" issues.
     */
    private void logInventorySummary(AltoClef mod) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null) return;

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String name = stack.getItem().toString();
                counts.merge(name, stack.getCount(), Integer::sum);
            }
        }

        if (counts.isEmpty()) {
            LOGGER.warn("[BuildTask] INVENTORY IS EMPTY — cannot place blocks!");
        } else {
            StringBuilder sb = new StringBuilder("[BuildTask] Inventory: ");
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> sb.append(e.getKey()).append("x").append(e.getValue()).append(", "));
            LOGGER.info(sb.toString());
        }
    }

    /**
     * Explicitly cancel the build — used when a new task replaces this one.
     * Must be called externally before the task is stopped.
     */
    public void cancel() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null) return;

        IBuilderProcess builder = mod.getClientBaritone().getBuilderProcess();
        if (builder.isActive()) {
            builder.onLostControl();
        }
        if (behaviourPushed) {
            mod.getBehaviour().pop();
            behaviourPushed = false;
        }
        LOGGER.info("[BuildTask] Build cancelled");
    }

    /**
     * Check if a world position has a correctly-placed schematic block.
     * Only protects blocks that are ALREADY placed correctly — this lets the
     * builder break wrong blocks (grass, dirt) to make room for placement,
     * while protecting finished blocks from defense-chain pathfinding.
     */
    private boolean isSchematicBlock(BlockPos pos) {
        int rx = pos.getX() - origin.getX();
        int ry = pos.getY() - origin.getY();
        int rz = pos.getZ() - origin.getZ();

        if (rx < 0 || rx >= schematic.widthX()
                || ry < 0 || ry >= schematic.heightY()
                || rz < 0 || rz >= schematic.lengthZ()) {
            return false;
        }

        // Only protect positions where the desired block type matches what's in the world.
        // This allows: builder clearing grass to place planks (grass != planks → not protected)
        // This blocks: defense pathfinding breaking placed planks (planks == planks → protected)
        BlockState desired = schematic.desiredState(rx, ry, rz,
                Blocks.AIR.getDefaultState(), java.util.List.of());
        if (desired.isAir()) return false;

        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getWorld() == null) return false;

        BlockState current = mod.getWorld().getBlockState(pos);
        return current.getBlock() == desired.getBlock();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof BuildSchematicTask task) {
            return task.buildName.equals(buildName)
                    && task.origin.equals(origin);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Build[" + buildName + " at " + origin.getX() + "," + origin.getY() + "," + origin.getZ() + "]";
    }

    // ── Inventory Management ─────────────────────────────────────────────────

    /**
     * Sub-task that drops inventory items not needed by the schematic.
     * Uses SlotActionType.THROW (button=1 → Ctrl+Q) to throw full stacks with velocity.
     * Each tick throws one stack. Thrown items get a 40-tick pickup delay so Emma
     * Inventory overflow during builds is now handled by StoreInAnyContainerTask
     * which deposits unneeded items into the nearest chest (or places one).
     * See checkAndGatherMissing() — the old DropUnneededItemsTask was removed.
     */
}
