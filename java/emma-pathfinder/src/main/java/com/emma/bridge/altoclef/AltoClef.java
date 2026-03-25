package adris.altoclef;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import adris.altoclef.butler.Butler;
import adris.altoclef.chains.DeathMenuChain;
import adris.altoclef.chains.FoodChain;
import adris.altoclef.chains.MLGBucketFallChain;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.chains.PlayerDefenseChain;
import adris.altoclef.chains.PlayerInteractionFixChain;
import adris.altoclef.chains.PreEquipItemChain;
import adris.altoclef.chains.UnstuckChain;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.chains.WorldSurvivalChain;
import adris.altoclef.commands.BlockScanner;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.control.InputCleanup;
import adris.altoclef.control.InputControls;
import adris.altoclef.control.PlayerExtraController;
import adris.altoclef.control.SlotHandler;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientRenderEvent;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.eventbus.events.TitleScreenEntryEvent;
import adris.altoclef.multiversion.DrawContextWrapper;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.CraftingRecipeTracker;
import adris.altoclef.trackers.EntityStuckTracker;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.trackers.MiscBlockTracker;
import adris.altoclef.trackers.SimpleChunkTracker;
import adris.altoclef.trackers.TrackerManager;
import adris.altoclef.trackers.UserBlockRangeTracker;
import adris.altoclef.trackers.storage.ContainerSubTracker;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import adris.altoclef.ui.AltoClefTickChart;
import adris.altoclef.ui.CommandStatusOverlay;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.ui.MessageSender;
import adris.altoclef.util.helpers.AutoTorchPlacer;
import adris.altoclef.util.helpers.InputHelper;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Central access point for AltoClef.
 *
 * Player2/ChatClef LLM bridge has been removed — Emma's Python orchestrator
 * handles all LLM, TTS, and STT via the WebSocket bridge in com.emma.bridge.
 */
public class AltoClef implements ModInitializer {

    // Static access to altoclef
    private static final Queue<Consumer<AltoClef>> _postInitQueue = new ArrayDeque<>();

    // Flag used by MessageSender to prevent re-processing bot's own chat messages
    public static boolean avoidNextMessageFlag = false;

    // Central Managers
    private static CommandExecutor commandExecutor;
    private TaskRunner taskRunner;
    private TrackerManager trackerManager;
    private BotBehaviour botBehaviour;
    private PlayerExtraController extraController;
    // Task chains
    private UserTaskChain userTaskChain;
    private FoodChain foodChain;
    private MobDefenseChain mobDefenseChain;
    private MLGBucketFallChain mlgBucketChain;
    // Trackers
    private ItemStorageTracker storageTracker;
    private ContainerSubTracker containerSubTracker;
    private EntityTracker entityTracker;
    private BlockScanner blockScanner;
    private SimpleChunkTracker chunkTracker;
    private MiscBlockTracker miscBlockTracker;
    private CraftingRecipeTracker craftingRecipeTracker;
    private EntityStuckTracker entityStuckTracker;
    private UserBlockRangeTracker userBlockRangeTracker;
    // Renderers
    private CommandStatusOverlay commandStatusOverlay;
    private AltoClefTickChart altoClefTickChart;
    // Settings
    private adris.altoclef.Settings settings;
    // Misc managers/input
    private MessageSender messageSender;
    private InputControls inputControls;
    private SlotHandler slotHandler;
    // Butler
    private Butler butler;
    // Auto torch placement (Phase 58a)
    private final AutoTorchPlacer torchPlacer = new AutoTorchPlacer();
    // Pausing
    private boolean paused = false;
    private Task storedTask;

    // stopping logic
    public boolean isStopping = false;

    private static AltoClef instance;
    private static boolean _loadInitialized = false;

    private boolean inGame = false;

    // Are we in game (playing in a server/world)
    public static boolean inGame() {
        return MinecraftClient.getInstance().player != null
                && MinecraftClient.getInstance().getNetworkHandler() != null;
    }

    /**
     * Executes commands (ex. `@get`/`@gamer`)
     * Falls back to lazy initialization if the TitleScreen mixin didn't fire.
     */
    public static CommandExecutor getCommandExecutor() {
        if (commandExecutor == null && instance != null) {
            Debug.logWarning("[AltoClef] CommandExecutor null — attempting late initialization");
            instance.onInitializeLoad();
        }
        return commandExecutor;
    }

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // As such, nothing will be loaded here but basic initialization.
        EventBus.subscribe(TitleScreenEntryEvent.class, evt -> onInitializeLoad());
        if (instance != null) {
            throw new IllegalStateException("AltoClef already loaded!");
        }
        instance = this;
    }

    public void onInitializeLoad() {
        // This code should be run after Minecraft loads everything else in.
        // This is the actual start point, controlled by a mixin.
        if (_loadInitialized) return;
        _loadInitialized = true;

        // Central Managers
        commandExecutor = new CommandExecutor(this);
        taskRunner = new TaskRunner(this);
        trackerManager = new TrackerManager(this);
        extraController = new PlayerExtraController(this);

        // Task chains
        userTaskChain = new UserTaskChain(taskRunner);
        mobDefenseChain = new MobDefenseChain(taskRunner);
        new DeathMenuChain(taskRunner);
        new PlayerInteractionFixChain(taskRunner);
        mlgBucketChain = new MLGBucketFallChain(taskRunner);
        new UnstuckChain(taskRunner);
        new PreEquipItemChain(taskRunner);
        new WorldSurvivalChain(taskRunner);
        foodChain = new FoodChain(taskRunner);
        new PlayerDefenseChain(taskRunner);

        // Trackers
        storageTracker = new ItemStorageTracker(this, trackerManager, container -> containerSubTracker = container);
        entityTracker = new EntityTracker(trackerManager);
        blockScanner = new BlockScanner(this);
        chunkTracker = new SimpleChunkTracker(this);
        miscBlockTracker = new MiscBlockTracker(this);
        craftingRecipeTracker = new CraftingRecipeTracker(trackerManager);
        entityStuckTracker = new EntityStuckTracker(trackerManager);
        userBlockRangeTracker = new UserBlockRangeTracker(trackerManager);

        // Renderers
        commandStatusOverlay = new CommandStatusOverlay();
        altoClefTickChart = new AltoClefTickChart(MinecraftClient.getInstance().textRenderer);

        // Misc managers
        messageSender = new MessageSender();
        inputControls = new InputControls();
        slotHandler = new SlotHandler(this);

        butler = new Butler(this);

        // Baritone
        initializeBaritoneSettings();

        // Initialize behavior (after baritone and other state that is set to start)
        botBehaviour = new BotBehaviour(this);

        initializeCommands();

        // Load settings
        adris.altoclef.Settings.load(newSettings -> {
            settings = newSettings;
            // Baritone's `acceptableThrowawayItems` should match our own.
            List<Item> baritoneCanPlace = Arrays.stream(settings.getThrowawayItems(true))
                    .filter(item -> item != Items.SOUL_SAND && item != Items.MAGMA_BLOCK && item != Items.SAND
                            && item != Items.GRAVEL)
                    .toList();
            getClientBaritoneSettings().acceptableThrowawayItems.value.addAll(baritoneCanPlace);
            // If we should run an idle command...
            if ((!getUserTaskChain().isActive() || getUserTaskChain().isRunningIdleTask())
                    && getModSettings().shouldRunIdleCommandWhenNotActive()) {
                getUserTaskChain().signalNextTaskToBeIdleTask();
                getCommandExecutor().executeWithPrefix(getModSettings().getIdleCommand());
            }
            // Don't break blocks or place blocks where we are explicitly protected.
            getExtraBaritoneSettings().avoidBlockBreak(blockPos -> settings.isPositionExplicitlyProtected(blockPos));
            getExtraBaritoneSettings().avoidBlockPlace(blockPos -> settings.isPositionExplicitlyProtected(blockPos));
        });

        // Receive + cancel chat — only handle AltoClef @commands
        EventBus.subscribe(SendChatEvent.class, evt -> {
            String line = evt.message;
            if (AltoClef.avoidNextMessageFlag) {
                return;
            } else if (getCommandExecutor().isClientCommand(line)) {
                evt.cancel();
                getCommandExecutor().execute(line);
            }
        });

        // Tick with the client
        EventBus.subscribe(ClientTickEvent.class, evt -> {
            long nanos = System.nanoTime();
            onClientTick();
            altoClefTickChart.pushTickNanos(System.nanoTime() - nanos);
        });

        // Render
        EventBus.subscribe(ClientRenderEvent.class, evt -> onClientRenderOverlay(evt.context));

        // Playground
        Playground.IDLE_TEST_INIT_FUNCTION(this);

        // External mod initialization
        runEnqueuedPostInits();
    }

    public void stop() {
        getUserTaskChain().cancel(this);
        if (taskRunner.getCurrentTaskChain() != null) {
            taskRunner.getCurrentTaskChain().stop();
        }
        // also disable idle, but we can re-enable it as soon as any task runs
        getTaskRunner().disable();
        // Extra reset. Sometimes baritone is laggy and doesn't properly reset our press
        getClientBaritone().getPathingBehavior().forceCancel();
        InputCleanup.reset();
    }

    // Client tick
    private int _tickCount = 0;
    private static final int TASK_LOG_INTERVAL = 200; // Log task chain state every ~10s

    private void onClientTick() {
        // Guard: don't tick if initialization hasn't completed
        if (taskRunner == null || trackerManager == null || settings == null) {
            return;
        }

        try {
            runEnqueuedPostInits();

            inputControls.onTickPre();

            // Cancel shortcut
            if (InputHelper.isKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL) && InputHelper.isKeyPressed(GLFW.GLFW_KEY_K)) {
                stop();
            }

            storageTracker.setDirty();
            containerSubTracker.onServerTick();
            miscBlockTracker.tick();
            trackerManager.tick();
            blockScanner.tick();
            taskRunner.tick();
            torchPlacer.tick();

            messageSender.tick();

            inputControls.onTickPost();
            if (!inGame && AltoClef.inGame()) {
                inGame = true;
            }

            // Periodic task chain diagnostic logging
            _tickCount++;
            if (_tickCount % TASK_LOG_INTERVAL == 0 && taskRunner.isActive()) {
                logTaskChainState();
            }
        } catch (Exception e) {
            Debug.logWarning("[AltoClef] Exception in onClientTick: " + e.getMessage());
            e.printStackTrace();
            // Don't rethrow — keep the tick handler alive
        }
    }

    private void logTaskChainState() {
        if (userTaskChain == null || !userTaskChain.isActive()) return;
        var currentTask = userTaskChain.getCurrentTask();
        if (currentTask == null) return;

        var chainTasks = userTaskChain.getTasks();
        StringBuilder sb = new StringBuilder("[AltoClef] Task chain: ");
        for (int i = 0; i < chainTasks.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(chainTasks.get(i).toString());
        }
        Debug.logMessage(sb.toString());
    }

    /// GETTERS AND SETTERS

    private void onClientRenderOverlay(DrawContextWrapper context) {
        // In 1.21.8, GUI rendering no longer uses RenderLayer for fill/line operations.
        if (settings != null && settings.shouldShowTaskChain()) {
            commandStatusOverlay.render(this, context);
        }

        if (settings != null && settings.shouldShowDebugTickMs()) {
            altoClefTickChart.render(this, context, 1, context.getScaledWindowWidth() / 2 - 124);
        }
    }

    private void initializeBaritoneSettings() {
        getExtraBaritoneSettings().canWalkOnEndPortal(false);
        // avoid block place on stuck entity
        getExtraBaritoneSettings().avoidBlockPlace(entityStuckTracker::isBlockedByEntity);
        // avoid breaking near user blocks
        getExtraBaritoneSettings().avoidBlockBreak(userBlockRangeTracker::isNearUserTrackedBlock);
        getClientBaritoneSettings().freeLook.value = false;
        getClientBaritoneSettings().overshootTraverse.value = false;
        getClientBaritoneSettings().allowOvershootDiagonalDescend.value = true;
        getClientBaritoneSettings().allowInventory.value = true;
        getClientBaritoneSettings().allowParkour.value = false;
        getClientBaritoneSettings().allowParkourAscend.value = false;
        getClientBaritoneSettings().allowParkourPlace.value = false;
        getClientBaritoneSettings().allowDiagonalDescend.value = false;
        getClientBaritoneSettings().allowDiagonalAscend.value = false;
        getClientBaritoneSettings().blocksToAvoid.value = new LinkedList<>(List.of(Blocks.FLOWERING_AZALEA,
                Blocks.AZALEA,
                Blocks.POWDER_SNOW, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.CAVE_VINES,
                Blocks.CAVE_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.SWEET_BERRY_BUSH,
                Blocks.WARPED_ROOTS, Blocks.VINE, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
                Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN));

        // dont try to break nether portal block
        getClientBaritoneSettings().blocksToAvoidBreaking.value.add(Blocks.NETHER_PORTAL);
        getClientBaritoneSettings().blocksToDisallowBreaking.value.add(Blocks.NETHER_PORTAL);

        // Let baritone move items to hotbar to use them
        // Reduces a bit of far rendering to save FPS
        getClientBaritoneSettings().fadePath.value = true;
        // Don't let baritone scan dropped items, we handle that ourselves.
        getClientBaritoneSettings().mineScanDroppedItems.value = false;
        // Don't let baritone wait for drops, we handle that ourselves.
        getClientBaritoneSettings().mineDropLoiterDurationMSThanksLouca.value = 0L;

        // Water bucket placement will be handled by us exclusively
        getExtraBaritoneSettings().configurePlaceBucketButDontFall(true);

        // For render smoothing
        getClientBaritoneSettings().randomLooking.value = 0.0;
        getClientBaritoneSettings().randomLooking113.value = 0.0;

        // Give baritone more time to calculate paths. Sometimes they can be really far
        // away.
        // Was: 2000L
        getClientBaritoneSettings().failureTimeoutMS.reset();
        // Was: 5000L
        getClientBaritoneSettings().planAheadFailureTimeoutMS.reset();
        // Was 100
        getClientBaritoneSettings().movementTimeoutTicks.reset();
    }

    // List all command sources here.
    private void initializeCommands() {
        try {
            // This creates the commands. If you want any more commands feel free to
            // initialize new command lists.
            AltoClefCommands.init();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @return the instance of this class or null if it has not been initialized yet
     */
    public static AltoClef getInstance() {
        return instance;
    }

    /**
     * Runs the highest priority task chain
     * (task chains run the task tree)
     */
    public TaskRunner getTaskRunner() {
        return taskRunner;
    }

    /**
     * The user task chain (runs your command. Ex. Get Diamonds, Beat the Game)
     */
    public UserTaskChain getUserTaskChain() {
        return userTaskChain;
    }

    /**
     * Controls bot behaviours, like whether to temporarily "protect" certain blocks
     * or items
     */
    public BotBehaviour getBehaviour() {
        return botBehaviour;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean pausing) {
        this.paused = pausing;
    }

    public void setStoredTask(Task currentTask) {
        this.storedTask = currentTask;
    }

    public Task getStoredTask() {
        return storedTask;
    }

    public ItemStorageTracker getItemStorage() {
        return storageTracker;
    }

    public EntityTracker getEntityTracker() {
        return entityTracker;
    }

    public CraftingRecipeTracker getCraftingRecipeTracker() {
        return craftingRecipeTracker;
    }

    public BlockScanner getBlockScanner() {
        return blockScanner;
    }

    public SimpleChunkTracker getChunkTracker() {
        return chunkTracker;
    }

    public MiscBlockTracker getMiscBlockTracker() {
        return miscBlockTracker;
    }

    public Baritone getClientBaritone() {
        if (getPlayer() == null) {
            return (Baritone) BaritoneAPI.getProvider().getPrimaryBaritone();
        }
        return (Baritone) BaritoneAPI.getProvider().getBaritoneForPlayer(getPlayer());
    }

    public Settings getClientBaritoneSettings() {
        return Baritone.settings();
    }

    public AltoClefSettings getExtraBaritoneSettings() {
        return AltoClefSettings.getInstance();
    }

    public adris.altoclef.Settings getModSettings() {
        return settings;
    }

    public Butler getButler() {
        return butler;
    }

    public MessageSender getMessageSender() {
        return messageSender;
    }

    public SlotHandler getSlotHandler() {
        return slotHandler;
    }

    public ClientPlayerEntity getPlayer() {
        return MinecraftClient.getInstance().player;
    }

    public ClientWorld getWorld() {
        return MinecraftClient.getInstance().world;
    }

    public ClientPlayerInteractionManager getController() {
        return MinecraftClient.getInstance().interactionManager;
    }

    public PlayerExtraController getControllerExtras() {
        return extraController;
    }

    public InputControls getInputControls() {
        return inputControls;
    }

    public void runUserTask(Task task) {
        runUserTask(task, () -> {
        });
    }

    public void runUserTask(Task task, Runnable onFinish) {
        userTaskChain.runTask(this, task, onFinish);
    }

    public void cancelUserTask() {
        userTaskChain.cancel(this);
    }

    public FoodChain getFoodChain() {
        return foodChain;
    }

    public MobDefenseChain getMobDefenseChain() {
        return mobDefenseChain;
    }

    public MLGBucketFallChain getMLGBucketChain() {
        return mlgBucketChain;
    }

    public AutoTorchPlacer getTorchPlacer() {
        return torchPlacer;
    }

    public void log(String message) {
        log(message, MessagePriority.TIMELY);
    }

    public void log(String message, MessagePriority priority) {
        Debug.logMessage(message);
    }

    public void logWarning(String message) {
        logWarning(message, MessagePriority.TIMELY);
    }

    public void logWarning(String message, MessagePriority priority) {
        Debug.logWarning(message);
    }

    private void runEnqueuedPostInits() {
        synchronized (_postInitQueue) {
            while (!_postInitQueue.isEmpty()) {
                _postInitQueue.poll().accept(this);
            }
        }
    }

}
