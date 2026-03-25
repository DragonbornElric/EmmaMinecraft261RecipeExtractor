package com.emma.bridge;

import com.emma.bridge.commands.AttackHandler;
import com.emma.bridge.commands.BiomeHandler;
import com.emma.bridge.commands.BreakBlockHandler;
import com.emma.bridge.commands.BuildHandler;
import com.emma.bridge.commands.CameraHandler;
import com.emma.bridge.commands.CancelHandler;
import com.emma.bridge.commands.ChatHandler;
import com.emma.bridge.commands.CombatLogHandler;
import com.emma.bridge.commands.AgentDebugHandler;
import com.emma.bridge.commands.SetGoalsHandler;
import com.emma.bridge.commands.SetModeHandler;
import com.emma.bridge.commands.SetPersonalityHandler;
import com.emma.bridge.commands.ClearAreaHandler;
import com.emma.bridge.commands.ClickSlotHandler;
import com.emma.bridge.commands.CloseScreenHandler;
import com.emma.bridge.commands.CommandRouter;
import com.emma.bridge.commands.DismountHandler;
import com.emma.bridge.commands.PanicTeleportHandler;
import com.emma.bridge.commands.DropItemHandler;
import com.emma.bridge.commands.FarmHandler;
import com.emma.bridge.commands.CreateFarmHandler;
import com.emma.bridge.commands.FurnaceStatusHandler;
import com.emma.bridge.commands.GetEffectsHandler;
import com.emma.bridge.commands.GetEntitiesHandler;
import com.emma.bridge.commands.GetTargetedBlockHandler;
import com.emma.bridge.commands.GotoHandler;
import com.emma.bridge.commands.HeightmapHandler;

import com.emma.bridge.commands.InteractBlockHandler;
import com.emma.bridge.commands.InteractEntityHandler;
import com.emma.bridge.commands.InventoryHandler;
import com.emma.bridge.commands.LookAtHandler;
import com.emma.bridge.commands.MineHandler;
import com.emma.bridge.commands.MountHandler;
import com.emma.bridge.commands.MoveItemHandler;
import com.emma.bridge.commands.PlaceBlockHandler;
import com.emma.bridge.commands.ReadScreenHandler;
import com.emma.bridge.commands.RespawnHandler;
import com.emma.bridge.commands.ScanHandler;
import com.emma.bridge.commands.ConfigureCameraHandler;
import com.emma.bridge.commands.SetPresetHandler;
import com.emma.bridge.commands.SetSlotHandler;
import com.emma.bridge.commands.StatusHandler;
import com.emma.bridge.commands.StorageHandler;
import com.emma.bridge.commands.SurfaceMapHandler;
import com.emma.bridge.commands.SwapHandsHandler;
import com.emma.bridge.commands.TorchHandler;
import com.emma.bridge.commands.UseItemHandler;
import com.emma.bridge.commands.WorldInfoHandler;
import com.emma.bridge.commands.WorldScanHandler;
import com.emma.bridge.camera.CameraTracker;
import com.google.gson.JsonObject;
import com.emma.bridge.events.BlockEventListener;
import com.emma.bridge.events.DamageListener;
import com.emma.bridge.events.EventReporter;
import com.emma.bridge.events.PanicTeleport;
import com.emma.bridge.events.TaskListener;
import com.emma.bridge.events.HeadRecenter;
import com.emma.bridge.events.TravelLookOverride;
import com.emma.bridge.goap.ActionRegistry;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.GoapTicker;
import com.emma.bridge.websocket.JsonProtocol;
import com.emma.bridge.websocket.MessageHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
/**
 * Emma Bridge Mod — Client-side initializer.
 *
 * Starts the WebSocket server that the Python orchestrator connects to.
 * All game-state queries and Emmatone commands flow through this bridge.
 *
 * Lifecycle:
 *   1. MC client starts → onInitializeClient() registers lifecycle hooks
 *   2. Client ready     → load config, build command router, start WS server
 *   3. Every tick        → event reporters check for state changes
 *   4. Client stopping  → shut down WebSocket server gracefully
 */
public class EmmaBridgeClient implements ClientModInitializer {

    private BridgeServer bridgeServer;
    private MessageHandler messageHandler;
    private EventReporter eventReporter;
    private TaskListener taskListener;
    private DamageListener damageListener;
    private BlockEventListener blockEventListener;
    private TravelLookOverride travelLookOverride;
    private PanicTeleport panicTeleport;
    private GoapTicker goapTicker;
    private com.emma.bridge.goap.actions.BuildStructureAction buildStructureAction;
    private CameraTracker cameraTracker;
    private com.emma.bridge.events.ContainerTracker containerTracker;

    @Override
    public void onInitializeClient() {
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Client initializer starting...");

        // Register Emmatone tick/world event dispatch via Fabric callbacks
        emmatone.EmmatoneTickDispatcher.register();

        // Start WebSocket server once the client is fully ready
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            startBridge();
        });

        // Clean shutdown when client stops
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopBridgeServer();
        });

        // Tick-based event reporters + command queue processing
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Process queued WebSocket commands on the tick thread
            // (safe for Emmatone — runs inside the game loop, not execute())
            if (messageHandler != null) {
                messageHandler.processPendingCommands();
            }

            // PANIC TELEPORT — runs FIRST, before all other tick processing
            // so it fires even if other systems are stuck
            if (panicTeleport != null && client.player != null) {
                panicTeleport.tick(client.player, bridgeServer);
            }

            if (eventReporter != null) {
                eventReporter.tick();
            }
            if (containerTracker != null && client.player != null) {
                containerTracker.tick(client.player);
            }
            if (taskListener != null) {
                taskListener.tick(bridgeServer);
            }
            if (damageListener != null && client.player != null) {
                damageListener.tick(client.player, bridgeServer);
            }
            if (blockEventListener != null) {
                blockEventListener.tick();
            }
            // Travel look override — runs AFTER all other tick handlers
            // so Emmatone has already set its rotation for this tick
            if (travelLookOverride != null) {
                travelLookOverride.tick();
            }

            // Head recenter — smooth pitch-to-level after task completion
            // Runs LAST so it doesn't conflict with travel look override
            HeadRecenter.tick();

            // Tick-based hold/release for use_item
            UseItemHandler.tick();

            // GOAP planner — score actions and execute winner
            if (goapTicker != null) {
                goapTicker.tick(client);
            }

            // Camera tracker — apply position from Emma's bridge
            if (cameraTracker != null) {
                cameraTracker.tick();
            }

        });

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Client initializer registered lifecycle hooks.");
    }

    private void startBridge() {
        try {
            // Load config from emma_bridge.json
            BridgeConfig.load();

            // Load item catalogue (recipes, block drops, mob drops, loot tables)
            com.emma.bridge.catalogue.ItemRecipeRegistry.load();

            // Build command router with mode awareness
            boolean cameraMode = BridgeConfig.isCameraMode();
            CommandRouter router = new CommandRouter(cameraMode);

            // Register player-mode commands (Emmatone-dependent)
            StorageHandler storageHandler = null;
            if (!cameraMode) {
                router.registerPlayerHandler(new GotoHandler());
                router.registerPlayerHandler(new MineHandler());
                router.registerPlayerHandler(new BuildHandler());
                router.registerPlayerHandler(new ScanHandler());
                router.registerPlayerHandler(new InventoryHandler());
                router.registerPlayerHandler(new LookAtHandler());
                router.registerPlayerHandler(new AttackHandler());
                router.registerPlayerHandler(new CancelHandler());
                storageHandler = new StorageHandler();
                router.registerPlayerHandler(storageHandler);
                router.registerPlayerHandler(new ClearAreaHandler());

                // Atomic action primitives (Phase 48)
                router.registerPlayerHandler(new UseItemHandler());
                router.registerPlayerHandler(new InteractEntityHandler());
                router.registerPlayerHandler(new InteractBlockHandler());
                router.registerPlayerHandler(new PlaceBlockHandler());
                router.registerPlayerHandler(new BreakBlockHandler());
                router.registerPlayerHandler(new SetSlotHandler());
                router.registerPlayerHandler(new MoveItemHandler());
                router.registerPlayerHandler(new DropItemHandler());
                router.registerPlayerHandler(new SwapHandsHandler());
                router.registerPlayerHandler(new ReadScreenHandler());
                router.registerPlayerHandler(new ClickSlotHandler());
                router.registerPlayerHandler(new CloseScreenHandler());
                router.registerPlayerHandler(new FurnaceStatusHandler());
                router.registerPlayerHandler(new GetEntitiesHandler());
                router.registerPlayerHandler(new GetEffectsHandler());

                router.registerPlayerHandler(new MountHandler());
                router.registerPlayerHandler(new DismountHandler());
                router.registerPlayerHandler(new FarmHandler());
                router.registerPlayerHandler(new CreateFarmHandler());
                router.registerPlayerHandler(new CombatLogHandler());

                // GOAP planner — utility AI (Phase 58g)
                GoalSet goalSet = new GoalSet();
                ActionRegistry actionRegistry = new ActionRegistry();
                actionRegistry.register(new com.emma.bridge.goap.actions.EatFoodAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.AttackEntityAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.NavigateToAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.MineBlockAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.CraftItemAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.SmeltItemAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.StoreItemsAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.PlaceTorchAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.FleeFromAction());
                // EquipBestArmor moved to reflex layer — always fires, never competes in auction
                actionRegistry.register(new com.emma.bridge.goap.actions.DeathRecoveryAction());
                // New scored actions (Phase 58n — Batch 3)
                actionRegistry.register(new com.emma.bridge.goap.actions.EnvironmentalHazardAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.CollectFoodAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.ProjectileDodgeAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.UnstuckAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.SleepAction());
                // Goal decomposition actions (Phase 58 — recursive subgoals)
                actionRegistry.register(new com.emma.bridge.goap.actions.HuntMobAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.HuntHostileAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.PickupItemAction());
                // TRANSFORM + INTERACT obtain method actions
                actionRegistry.register(new com.emma.bridge.goap.actions.EntityInteractAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.TransformBlockAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.ExploreAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.EstablishBaseAction());
                // Special-case pipeline actions (kill_dragon pipeline)
                actionRegistry.register(new com.emma.bridge.goap.actions.BuildNetherPortalAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.EnterPortalAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.LocateStrongholdAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.ActivateEndPortalAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.DestroyEndCrystalsAction());
                actionRegistry.register(new com.emma.bridge.goap.actions.DragonCombatAction());
                // Build as GOAP action
                var buildPlanRegistry = new com.emma.bridge.goap.BuildPlanRegistry();
                buildStructureAction = new com.emma.bridge.goap.actions.BuildStructureAction(buildPlanRegistry, goalSet);
                actionRegistry.register(buildStructureAction);

                goapTicker = new GoapTicker(goalSet, actionRegistry);

                // Wire GoalSet to CollectFoodAction for Mode B goal chains
                for (GoapAction action : actionRegistry.getAllActions()) {
                    if (action instanceof com.emma.bridge.goap.actions.CollectFoodAction collectFood) {
                        collectFood.setGoalSet(goalSet);
                        break;
                    }
                }

                // Register reflexes (Phase 58n — Batch 2)
                goapTicker.getReflexLayer().register(new com.emma.bridge.goap.reflex.ShieldBlockReflex());
                goapTicker.getReflexLayer().register(new com.emma.bridge.goap.reflex.ForceFieldReflex());
                goapTicker.getReflexLayer().register(new com.emma.bridge.goap.reflex.MLGBucketReflex());
                goapTicker.getReflexLayer().register(new com.emma.bridge.goap.reflex.ToolEquipReflex());
                goapTicker.getReflexLayer().register(new com.emma.bridge.goap.reflex.PreEquipWeaponReflex());
                goapTicker.getReflexLayer().register(new com.emma.bridge.goap.reflex.AutoRespawnReflex());
                goapTicker.getReflexLayer().register(new com.emma.bridge.goap.reflex.ArmorEquipReflex());

                // Portal registry — persistent portal location tracking
                var portalRegistry = new com.emma.bridge.goap.PortalRegistry();
                goapTicker.setPortalRegistry(portalRegistry);

                // Base registry — persistent base location tracking
                var baseRegistry = new com.emma.bridge.goap.BaseRegistry();
                goapTicker.setBaseRegistry(baseRegistry);

                // Wire PortalRegistry + BaseRegistry + WorldState to pipeline actions
                var ws = goapTicker.getWorldState();
                for (GoapAction action : actionRegistry.getAllActions()) {
                    if (action instanceof com.emma.bridge.goap.actions.BuildNetherPortalAction a) a.setPortalRegistry(portalRegistry);
                    if (action instanceof com.emma.bridge.goap.actions.EnterPortalAction a) a.setPortalRegistry(portalRegistry);
                    if (action instanceof com.emma.bridge.goap.actions.LocateStrongholdAction a) a.setWorldState(ws);
                    if (action instanceof com.emma.bridge.goap.actions.ActivateEndPortalAction a) {
                        a.setPortalRegistry(portalRegistry);
                        a.setWorldState(ws);
                    }
                    if (action instanceof com.emma.bridge.goap.actions.CollectFoodAction a) a.setBaseRegistry(baseRegistry);
                    if (action instanceof com.emma.bridge.goap.actions.EstablishBaseAction a) {
                        a.setBaseRegistry(baseRegistry);
                        a.setWorldState(ws);
                    }
                }

                router.registerPlayerHandler(new SetGoalsHandler(goalSet, goapTicker));
                router.registerPlayerHandler(new com.emma.bridge.commands.AddGoalHandler(goalSet, goapTicker));
                router.registerPlayerHandler(new com.emma.bridge.commands.RemoveGoalHandler(goalSet, goapTicker));
                router.registerPlayerHandler(new SetPersonalityHandler(goapTicker.getScorer()));
                router.registerPlayerHandler(new SetModeHandler(goalSet, goapTicker, goapTicker.getScorer()));
                router.registerPlayerHandler(new AgentDebugHandler(goapTicker));
                router.registerPlayerHandler(new com.emma.bridge.commands.SetBuildGoalHandler(goalSet, goapTicker, buildPlanRegistry));
                router.registerPlayerHandler(new TorchHandler(actionRegistry));
                router.registerPlayerHandler(new com.emma.bridge.commands.SavePortalHandler(portalRegistry));
                router.registerPlayerHandler(new com.emma.bridge.commands.GetPortalsHandler(portalRegistry));
                router.registerPlayerHandler(new com.emma.bridge.commands.SetBaseHandler(baseRegistry));
                router.registerPlayerHandler(new com.emma.bridge.commands.GetBasesHandler(baseRegistry));
                router.registerPlayerHandler(new com.emma.bridge.commands.RemoveBaseHandler(baseRegistry));
                if (BridgeConfig.isGoapEnabled()) {
                    goapTicker.setEnabled(true);
                }

                // Panic teleport safety system
                panicTeleport = new PanicTeleport();
                if (BridgeConfig.isPanicTeleportEnabled()) {
                    panicTeleport.setEnabled(true);
                    panicTeleport.setHealthThreshold(BridgeConfig.getPanicThreshold());
                    panicTeleport.setSafeCoords(
                            BridgeConfig.getPanicSafeX(),
                            BridgeConfig.getPanicSafeY(),
                            BridgeConfig.getPanicSafeZ());
                    panicTeleport.setCooldownMs(BridgeConfig.getPanicCooldownMs());
                }
                router.registerPlayerHandler(new PanicTeleportHandler(panicTeleport));

            }

            // Register shared commands (available in both modes)
            router.registerSharedHandler(new StatusHandler());
            router.registerSharedHandler(new HeightmapHandler());
            router.registerSharedHandler(new SurfaceMapHandler());
            router.registerSharedHandler(new WorldScanHandler());
            router.registerSharedHandler(new BiomeHandler());
            router.registerSharedHandler(new WorldInfoHandler());
            router.registerSharedHandler(new GetTargetedBlockHandler());
            router.registerSharedHandler(new RespawnHandler());
            router.registerSharedHandler(new ChatHandler());

            // Register camera-only commands
            if (cameraMode) {
                // Apply camera presets from config before creating tracker
                JsonObject presets = BridgeConfig.getCameraPresets();
                if (presets != null) {
                    com.emma.bridge.camera.CameraPresets.applyOverrides(presets);
                }

                // Start the CameraTracker — connects to Emma's bridge for position tracking
                cameraTracker = new CameraTracker(BridgeConfig.getPlayerPort());
                cameraTracker.setLerpSpeed(BridgeConfig.getCameraLerpSpeed());
                cameraTracker.setTpDistance(BridgeConfig.getCameraTpDistance());
                cameraTracker.start();

                router.registerCameraHandler(new CameraHandler());
                router.registerCameraHandler(new SetPresetHandler(cameraTracker));
                router.registerCameraHandler(new ConfigureCameraHandler(cameraTracker));
                // Also allow inventory and scan in camera mode
                router.registerCameraHandler(new InventoryHandler());
            }

            // Create message handler (stored as field for tick-based command processing)
            messageHandler = new MessageHandler(router);

            // Start WebSocket server
            int port = BridgeConfig.getPort();
            bridgeServer = new BridgeServer(port, messageHandler);
            bridgeServer.start();

            // Wire BridgeServer to BuildStructureAction for build_phase_complete events
            if (buildStructureAction != null) {
                buildStructureAction.setBridgeServer(bridgeServer);
            }

            // Wire BridgeServer to GoapTicker for goap_briefing events
            if (goapTicker != null) {
                goapTicker.setBridgeServer(bridgeServer);
            }

            // Initialize event reporters (mode-aware — some depend on Emmatone)
            eventReporter = new EventReporter(bridgeServer);
            damageListener = new DamageListener();

            // Register Fabric API event callbacks
            blockEventListener = new BlockEventListener(bridgeServer);
            blockEventListener.register();

            // Register @ command interceptor for in-game chat testing
            // Intercepts "@command args" messages and routes to CommandRouter
            new com.emma.bridge.events.ChatCommandInterceptor(router).register();

            // Emmatone-dependent reporters — player mode only
            // (TaskListener → EmmatoneAPI, TravelLookOverride → EmmatoneAPI
            //  — loading these in camera mode crashes)
            if (!cameraMode) {
                taskListener = new TaskListener();
                travelLookOverride = new TravelLookOverride();

                // ContainerTracker — caches container contents on screen open/close
                containerTracker = new com.emma.bridge.events.ContainerTracker(bridgeServer);
                if (storageHandler != null) {
                    storageHandler.setContainerTracker(containerTracker);
                }
                if (goapTicker != null) {
                    goapTicker.setContainerTracker(containerTracker);
                }
            }

            EmmaBridgeMod.LOGGER.info("[Emma Bridge] Bridge started — mode={}, port={}",
                    cameraMode ? "camera" : "player", port);

        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.error("[Emma Bridge] Failed to start bridge", e);
        }
    }

    private void stopBridgeServer() {
        if (cameraTracker != null) {
            cameraTracker.stop();
            cameraTracker = null;
        }
        if (bridgeServer != null) {
            try {
                bridgeServer.stop(1000);
                EmmaBridgeMod.LOGGER.info("[Emma Bridge] WebSocket server stopped.");
            } catch (Exception e) {
                EmmaBridgeMod.LOGGER.error("[Emma Bridge] Error stopping WebSocket server", e);
            }
        }
    }
}
