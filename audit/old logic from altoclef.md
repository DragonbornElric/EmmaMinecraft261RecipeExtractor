# Bridge Mod Architecture — Java File Reference

## C:\Users\Owner\emma-ai-phase-57b-debug\emma-ai-phase-57b-debug\gamer\minecraft\bridge_mod\src\main\java\adris\altoclef— Core

| File                      | Description                                                                                           |
| ------------------------- | ----------------------------------------------------------------------------------------------------- |
| `AltoClef.java`         | Main mod entry point — orchestrates task chains, event bus, trackers, and game state                 |
| `AltoClefCommands.java` | Registers all in-game chat commands                                                                   |
| `BotBehaviour.java`     | Dynamic behavior settings (escape lava, avoid mining, protected items) — push/pop stack during tasks |
| `Debug.java`            | Logging utilities for internal/warning/error output                                                   |
| `Playground.java`       | Ad-hoc testing utility for task experimentation                                                       |
| `Settings.java`         | Mod settings: crafting book toggle, food thresholds, move delays, throwaway items                     |
| `TaskCatalogue.java`    | Registry mapping item names to collection/crafting tasks                                              |

## adris/altoclef/butler/ — Chat Butler

| File                    | Description                                              |
| ----------------------- | -------------------------------------------------------- |
| `Butler.java`         | Processes in-game whisper commands from authorized users |
| `ButlerConfig.java`   | Butler configuration (enabled, auth settings)            |
| `UserAuth.java`       | User authentication for butler commands                  |
| `UserListFile.java`   | Persistent whitelist/blacklist file management           |
| `WhisperChecker.java` | Detects and parses incoming whisper messages             |

## adris/altoclef/chains/ — Task Chains

| File                               | Description                                                     |
| ---------------------------------- | --------------------------------------------------------------- |
| `DeathMenuChain.java`            | Auto-respawn on death screen                                    |
| `FoodChain.java`                 | Automatic eating when hunger is low                             |
| `MLGBucketFallChain.java`        | Water bucket clutch when falling from height                    |
| `MobDefenseChain.java`           | Auto-combat against hostile mobs                                |
| `PlayerDefenseChain.java`        | Defense against hostile players                                 |
| `PlayerInteractionFixChain.java` | Fixes stuck interaction states                                  |
| `PreEquipItemChain.java`         | Pre-equips tools/weapons before tasks need them                 |
| `SingleTaskChain.java`           | Abstract base for chains executing a single main task           |
| `UnstuckChain.java`              | Detects and resolves stuck pathing situations                   |
| `UserTaskChain.java`             | Executes user-issued tasks with timing and completion callbacks |
| `WorldSurvivalChain.java`        | Critical survival: drowning, lava, fire, portal stuck detection |

## adris/altoclef/commands/ — Chat Commands

| File                               | Description                          |
| ---------------------------------- | ------------------------------------ |
| `AttackPlayerOrMobCommand.java`  | Attack a named player or mob type    |
| `BlockScanner.java`              | Scan for block types nearby          |
| `CreateFarmCommand.java`         | Create an automated farm             |
| `DefenseCommand.java`            | Toggle defense behavior              |
| `DepositCommand.java`            | Deposit items into nearby containers |
| `EquipCommand.java`              | Equip a specific item                |
| `FarmCommand.java`               | Start farming task                   |
| `FollowCommand.java`             | Follow a player                      |
| `FoodCommand.java`               | Collect food                         |
| `GamerCommand.java`              | Toggle AI bridge mode                |
| `GetCommand.java`                | Obtain a specific item               |
| `GiveCommand.java`               | Give items to a player               |
| `GotoCommand.java`               | Navigate to coordinates              |
| `HeroCommand.java`               | Run the beat-the-game task           |
| `IdleCommand.java`               | Enter idle state                     |
| `InventoryCommand.java`          | Display inventory contents           |
| `ListCommand.java`               | List available commands              |
| `LocateStructureCommand.java`    | Find a structure type                |
| `MeatCommand.java`               | Collect meat specifically            |
| `PauseCommand.java`              | Pause current task                   |
| `ReloadSettingsCommand.java`     | Reload settings from file            |
| `ResetMemoryCommand.java`        | Clear tracked block/entity memory    |
| `RespawnCommand.java`            | Force respawn                        |
| `SetAIBridgeEnabledCommand.java` | Enable/disable AI bridge connection  |
| `SetGammaCommand.java`           | Set brightness level                 |
| `SleepCommand.java`              | Find and sleep in a bed              |
| `StashCommand.java`              | Store items in a stash location      |
| `StatusCommand.java`             | Show current task status             |
| `StopCommand.java`               | Stop all tasks                       |
| `TorchCommand.java`              | Place torches                        |
| `TorchLevelCommand.java`         | Set torch placement light threshold  |
| `UnPauseCommand.java`            | Resume paused task                   |
| `random/CycleTestCommand.java`   | Test command cycling                 |
| `random/DummyTaskCommand.java`   | Dummy task for testing               |
| `random/ScanCommand.java`        | Debug scan command                   |

## adris/altoclef/commandsystem/ — Command Framework

| File                      | Description                                          |
| ------------------------- | ---------------------------------------------------- |
| `Arg.java`              | Typed command argument                               |
| `ArgBase.java`          | Base class for command arguments                     |
| `ArgParser.java`        | Parses command argument strings                      |
| `Command.java`          | Abstract base for all commands                       |
| `CommandException.java` | Command parsing/execution exception                  |
| `CommandExecutor.java`  | Dispatches command strings to registered commands    |
| `GotoTarget.java`       | Parsed goto target (coordinates, block type, entity) |
| `ItemList.java`         | Parsed list of item targets from command args        |

## adris/altoclef/control/ — Input & Interaction

| File                           | Description                                                                                                  |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------ |
| `BlockInteraction.java`      | Direct Mojang API wrappers for block interaction — reach checks, face computation, breaking, right-clicking |
| `DirectInput.java`           | Mojang API wrappers for movement inputs — sneak, sprint, item use, jump                                     |
| `InputCleanup.java`          | Resets all input state (sneak, sprint, jump) on task cancel/completion                                       |
| `InputControls.java`         | Deprecated input layer (Phase 52 locked) — silent no-ops except `forceLook()`                             |
| `KillAura.java`              | Combat control — entity targeting, weapon equipping, shielding, attack automation                           |
| `ManualSteering.java`        | Lightweight KeyboardInput replacement for manual movement outside Baritone                                   |
| `PlayerExtraController.java` | Block-breaking progress tracking and entity range/attack helpers                                             |
| `SlotHandler.java`           | Slot click API — wraps `ClientPlayerInteractionManager.clickSlot()` server packets                        |

## adris/altoclef/eventbus/ — Event System

| File                  | Description                        |
| --------------------- | ---------------------------------- |
| `EventBus.java`     | Publish/subscribe event dispatcher |
| `Subscription.java` | Event subscription handle          |

### eventbus/events/

| File                                   | Description                               |
| -------------------------------------- | ----------------------------------------- |
| `BlockBreakingCancelEvent.java`      | Fired when block breaking is cancelled    |
| `BlockBreakingEvent.java`            | Fired during block breaking               |
| `BlockInteractEvent.java`            | Fired on block interaction                |
| `BlockPlaceEvent.java`               | Fired on block placement                  |
| `ChatMessageEvent.java`              | Fired on incoming chat message            |
| `ChunkLoadEvent.java`                | Fired when a chunk loads                  |
| `ChunkUnloadEvent.java`              | Fired when a chunk unloads                |
| `ClientRenderEvent.java`             | Fired each render frame                   |
| `ClientTickEvent.java`               | Fired each client tick                    |
| `EntityDeathEvent.java`              | Fired when an entity dies                 |
| `EntitySwungEvent.java`              | Fired when an entity swings               |
| `PlayerCollidedWithEntityEvent.java` | Fired on player-entity collision          |
| `PlayerDamageEvent.java`             | Fired when player takes damage            |
| `ScreenOpenEvent.java`               | Fired when a screen/GUI opens             |
| `SendChatEvent.java`                 | Fired when sending a chat message         |
| `SlotClickChangedEvent.java`         | Fired when a slot click changes inventory |
| `TaskFinishedEvent.java`             | Fired when a task completes               |
| `TitleScreenEntryEvent.java`         | Fired on return to title screen           |

## adris/altoclef/mixins/ — Fabric Mixins

| File                                          | Description                                      |
| --------------------------------------------- | ------------------------------------------------ |
| `AbstractFurnaceScreenHandlerAccessor.java` | Accessor for furnace burn/cook progress          |
| `ChatInputMixin.java`                       | Intercepts outgoing chat for command processing  |
| `ChatReadMixin.java`                        | Intercepts incoming chat for event firing        |
| `ClientBlockBreakAccessor.java`             | Accessor for block break progress state          |
| `ClientBlockBreakMixin.java`                | Hooks block break events                         |
| `ClientConnectionAccessor.java`             | Accessor for network connection state            |
| `ClientInteractWithBlockMixin.java`         | Hooks block interaction events                   |
| `ClientOpenScreenMixin.java`                | Hooks screen open events                         |
| `ClientTickMixin.java`                      | Hooks client tick for main loop                  |
| `ClientUIMixin.java`                        | Hooks UI rendering for overlays                  |
| `ConnectScreenInvoker.java`                 | Invoker for server connection screen             |
| `DeathScreenAccessor.java`                  | Accessor for death screen state                  |
| `EntityAccessor.java`                       | Accessor for entity internal state               |
| `EntityAnimationSwungMixin.java`            | Hooks entity swing animation                     |
| `EntryMixin.java`                           | Game entry point mixin                           |
| `LoadChunkMixin.java`                       | Hooks chunk load/unload                          |
| `MixinLocalPlayer.java`                     | Hooks local player methods                       |
| `MobDeathMixin.java`                        | Hooks mob death events                           |
| `MovementHelperBlockProtectionMixin.java`   | Prevents Baritone from breaking protected blocks |
| `MovementHelperMixin.java`                  | Modifies Baritone movement helper behavior       |
| `PersistentProjectileEntityAccessor.java`   | Accessor for arrow/projectile state              |
| `PlayerCollidesWithEntityMixin.java`        | Hooks player-entity collision                    |
| `PlayerDamageMixin.java`                    | Hooks player damage events                       |
| `PortalManagerAccessor.java`                | Accessor for portal cooldown state               |
| `SimpleOptionMixin.java`                    | Allows programmatic gamma/option changes         |
| `SlotClickMixin.java`                       | Hooks slot click events for tracking             |
| `ToolSetMixin.java`                         | Modifies tool effectiveness checks               |
| `WorldBlockModifiedMixin.java`              | Hooks block state changes in the world           |

## adris/altoclef/multiversion/ — Version Compatibility

| File                                          | Description                                                  |
| --------------------------------------------- | ------------------------------------------------------------ |
| `BlockStateVer.java`                        | Version-specific block state methods                         |
| `BlockTagVer.java`                          | Version-specific block tag lookups                           |
| `ConnectScreenVer.java`                     | Version-specific server connection screen                    |
| `CraftingRecipeVer.java`                    | Version-specific recipe output extraction                    |
| `DamageSourceVer.java`                      | Version-specific damage source creation                      |
| `DamageSourceWrapper.java`                  | Cross-version damage source wrapper                          |
| `DrawContextWrapper.java`                   | Cross-version draw context wrapper                           |
| `EnchantmentHelperVer.java`                 | Version-specific enchantment queries                         |
| `FoodComponentWrapper.java`                 | Cross-version food component wrapper                         |
| `IdentifierVer.java`                        | Version-specific resource identifier creation                |
| `InGameHudVer.java`                         | Version-specific HUD access                                  |
| `InteractionManagerVer.java`                | Version-specific interaction manager methods                 |
| `MessageTypeVer.java`                       | Version-specific chat message types                          |
| `MethodWrapper.java`                        | Reflection wrapper for version-specific methods              |
| `MinecraftClientVer.java`                   | Version-specific MinecraftClient access                      |
| `OptionsVer.java`                           | Version-specific game options                                |
| `Pattern.java`                              | Version-specific pattern matching                            |
| `RecipeVer.java`                            | Version-specific recipe output resolution                    |
| `RenderLayerVer.java`                       | Version-specific render layers                               |
| `ToolMaterialVer.java`                      | Version-specific tool material properties                    |
| `blockpos/BlockPosHelper.java`              | BlockPos utility methods                                     |
| `blockpos/BlockPosVer.java`                 | Version-specific BlockPos creation                           |
| `box/BoxHelper.java`                        | Bounding box utility methods                                 |
| `box/BoxVer.java`                           | Version-specific bounding box creation                       |
| `entity/EntityHelper.java`                  | Entity utility methods                                       |
| `entity/EntityVer.java`                     | Version-specific entity methods                              |
| `entity/LivingEntityVer.java`               | Version-specific living entity methods                       |
| `entity/PlayerVer.java`                     | Version-specific player methods                              |
| `item/ItemHelper.java`                      | Item utility methods                                         |
| `item/ItemVer.java`                         | Version-specific item methods                                |
| `recipemanager/RecipeManagerWrapper.java`   | Wraps Minecraft's recipe manager for cross-version iteration |
| `recipemanager/WrappedRecipeEntry.java`     | Cross-version recipe entry wrapper                           |
| `versionedfields/Blocks.java`               | Version-specific block references                            |
| `versionedfields/Entities.java`             | Version-specific entity type references                      |
| `versionedfields/Items.java`                | Version-specific item references                             |
| `versionedfields/VersionedFieldHelper.java` | Reflection helper for versioned field access                 |
| `world/WorldHelper.java`                    | World utility methods                                        |
| `world/WorldVer.java`                       | Version-specific world methods                               |

## adris/altoclef/tasks/ — Task Implementations

### Core Tasks

| File                                     | Description                                                               |
| ---------------------------------------- | ------------------------------------------------------------------------- |
| `ResourceTask.java`                    | Base class for item-collection tasks — searches ground, chests, crafting |
| `AbstractDoToClosestObjectTask.java`   | Base for tasks that act on the nearest matching object                    |
| `CraftGenericManuallyTask.java`        | Manual slot-by-slot crafting fallback                                     |
| `CraftGenericWithRecipeBooksTask.java` | Recipe book crafting — calls `clickRecipe()` server packet             |
| `CraftInInventoryTask.java`            | 2x2 inventory crafting entry point                                        |
| `CreateFarmTask.java`                  | Automated farm creation                                                   |
| `DoToClosestBlockTask.java`            | Act on the nearest matching block                                         |
| `GetRidOfExtraWaterBucketTask.java`    | Dump extra water buckets                                                  |
| `InteractWithBlockTask.java`           | Walk to and interact with a specific block                                |
| `SafeNetherPortalTask.java`            | Safe nether portal entry                                                  |

### tasks/container/ — Container Interaction

| File                                      | Description                                                      |
| ----------------------------------------- | ---------------------------------------------------------------- |
| `AbstractDoToStorageContainerTask.java` | Base for tasks that interact with storage containers             |
| `ContainerStoredTracker.java`           | Tracks what items are stored in which containers                 |
| `CraftInAnvilTask.java`                 | Anvil crafting                                                   |
| `CraftInTableTask.java`                 | 3x3 crafting table entry point (contains `DoCraftInTableTask`) |
| `DoStuffInContainerTask.java`           | Base class: find/place/walk-to/open any container type           |
| `LootContainerTask.java`                | Loot items from a container                                      |
| `PickupFromContainerTask.java`          | Pick up specific items from a container                          |
| `SmeltInBlastFurnaceTask.java`          | Smelt in blast furnace                                           |
| `SmeltInFurnaceTask.java`               | Smelt in regular furnace                                         |
| `SmeltingHelper.java`                   | Shared smelting utilities                                        |
| `SmeltInSmokerTask.java`                | Smelt in smoker                                                  |
| `StoreInAnyContainerTask.java`          | Store items in any available container                           |
| `StoreInContainerTask.java`             | Store items in a specific container                              |
| `StoreInStashTask.java`                 | Store items in a designated stash                                |
| `UpgradeInSmithingTableTask.java`       | Smithing table upgrades (netherite, trims)                       |

### tasks/construction/ — Building

| File                                                | Description                            |
| --------------------------------------------------- | -------------------------------------- |
| `BuildSchematicTask.java`                         | Build from a Baritone schematic        |
| `ClearLiquidTask.java`                            | Clear liquid blocks in an area         |
| `ClearRegionTask.java`                            | Clear all blocks in a region           |
| `DestroyBlockTask.java`                           | Break a specific block                 |
| `PlaceBlockNearbyTask.java`                       | Place a block near the player          |
| `PlaceBlockTask.java`                             | Place a block at specific coordinates  |
| `PlaceObsidianBucketTask.java`                    | Place obsidian using lava+water bucket |
| `PlaceStructureBlockTask.java`                    | Place a structure block                |
| `ProjectileProtectionWallTask.java`               | Build a wall for projectile protection |
| `PutOutFireTask.java`                             | Extinguish fire blocks                 |
| `compound/ConstructIronGolemTask.java`            | Build an iron golem                    |
| `compound/ConstructNetherPortalBucketTask.java`   | Build nether portal with bucket method |
| `compound/ConstructNetherPortalObsidianTask.java` | Build nether portal with obsidian      |
| `compound/ConstructNetherPortalSpeedrunTask.java` | Speedrun nether portal construction    |

### tasks/entity/ — Entity Interaction

| File                                    | Description                       |
| --------------------------------------- | --------------------------------- |
| `AbstractDoToEntityTask.java`         | Base for tasks acting on entities |
| `AbstractKillEntityTask.java`         | Base for entity killing tasks     |
| `DoToClosestEntityTask.java`          | Act on nearest matching entity    |
| `GiveItemToPlayerTask.java`           | Give an item to another player    |
| `HeroTask.java`                       | Kill all nearby hostile mobs      |
| `KillEntitiesTask.java`               | Kill multiple entity types        |
| `KillEntityTask.java`                 | Kill a specific entity            |
| `KillPlayerTask.java`                 | Kill a specific player            |
| `ShearSheepTask.java`                 | Shear a sheep                     |
| `ShootArrowSimpleProjectileTask.java` | Shoot an arrow at a target        |
| `ThrowSplashPotionTask.java`          | Throw a splash potion             |

### tasks/misc/

| File                             | Description                           |
| -------------------------------- | ------------------------------------- |
| `EquipArmorTask.java`          | Equip armor pieces                    |
| `LootDesertTempleTask.java`    | Loot a desert temple                  |
| `PlaceBedAndSetSpawnTask.java` | Place bed and set spawn point         |
| `RavageDesertTemplesTask.java` | Find and loot multiple desert temples |
| `RavageRuinedPortalsTask.java` | Find and loot ruined portals          |
| `SleepThroughNightTask.java`   | Sleep through the night               |

### tasks/movement/ — Navigation

| File                                         | Description                            |
| -------------------------------------------- | -------------------------------------- |
| `ChunkSearchTask.java`                     | Search chunks for a target             |
| `CustomBaritoneGoalTask.java`              | Execute a custom Baritone goal         |
| `DefaultGoToDimensionTask.java`            | Navigate to a different dimension      |
| `DefenseTask.java`                         | Move to defensive position             |
| `DodgeProjectilesTask.java`                | Dodge incoming projectiles             |
| `EnterNetherPortalTask.java`               | Enter a nether portal                  |
| `EscapeFromLavaTask.java`                  | Escape from lava                       |
| `FastTravelTask.java`                      | Fast travel using nether highways      |
| `FollowPlayerTask.java`                    | Follow a player entity                 |
| `GetCloseToBlockTask.java`                 | Get close to a block position          |
| `GetOutOfWaterTask.java`                   | Get out of water                       |
| `GetToBlockTask.java`                      | Navigate to a specific block           |
| `GetToChunkTask.java`                      | Navigate to a specific chunk           |
| `GetToEntityTask.java`                     | Navigate to a specific entity          |
| `GetToXZTask.java`                         | Navigate to X/Z coordinates            |
| `GetToYTask.java`                          | Navigate to a Y level                  |
| `GetWithinRangeOfBlockTask.java`           | Get within range of a block            |
| `GoInDirectionXZTask.java`                 | Walk in a compass direction            |
| `GoToStrongholdPortalTask.java`            | Navigate to stronghold end portal      |
| `IdleTask.java`                            | Do nothing (idle state)                |
| `LocateDesertTempleTask.java`              | Find a desert temple                   |
| `LocateStrongholdCoordinatesTask.java`     | Locate stronghold via ender eyes       |
| `MLGBucketTask.java`                       | Execute MLG water bucket               |
| `PickupDroppedItemTask.java`               | Pick up dropped items                  |
| `RunAwayFromCreepersTask.java`             | Flee from creepers                     |
| `RunAwayFromEntitiesTask.java`             | Flee from entities                     |
| `RunAwayFromHostilesTask.java`             | Flee from all hostiles                 |
| `RunAwayFromPositionTask.java`             | Flee from a position                   |
| `SafeRandomShimmyTask.java`                | Random safe movement                   |
| `SearchChunkForBlockTask.java`             | Search a chunk for a block type        |
| `SearchChunksExploreTask.java`             | Explore chunks searching for something |
| `SearchWithinBiomeTask.java`               | Search within a biome                  |
| `ThrowEnderPearlSimpleProjectileTask.java` | Throw an ender pearl                   |
| `TimeoutWanderTask.java`                   | Wander randomly with timeout           |

### tasks/resources/ — Resource Collection

| File                                          | Description                                            |
| --------------------------------------------- | ------------------------------------------------------ |
| `CarveThenCollectTask.java`                 | Carve block (e.g. pumpkin) then collect result         |
| `CollectAmethystBlockTask.java`             | Collect amethyst blocks                                |
| `CollectBedTask.java`                       | Collect a bed                                          |
| `CollectBlazeRodsTask.java`                 | Kill blazes for rods                                   |
| `CollectBlockByOneTask.java`                | Mine blocks one at a time                              |
| `CollectBucketLiquidTask.java`              | Fill bucket with liquid                                |
| `CollectCoarseDirtTask.java`                | Collect coarse dirt                                    |
| `CollectCocoaBeansTask.java`                | Collect cocoa beans                                    |
| `CollectCropTask.java`                      | Harvest crops                                          |
| `CollectDripstoneBlockTask.java`            | Collect dripstone                                      |
| `CollectEggsTask.java`                      | Collect eggs                                           |
| `CollectFlintTask.java`                     | Collect flint from gravel                              |
| `CollectFlowerTask.java`                    | Collect flowers                                        |
| `CollectFoodTask.java`                      | Collect food items                                     |
| `CollectFuelTask.java`                      | Collect fuel items                                     |
| `CollectGoldIngotTask.java`                 | Obtain gold ingots                                     |
| `CollectGoldNuggetsTask.java`               | Collect gold nuggets                                   |
| `CollectHayBlockTask.java`                  | Collect hay blocks                                     |
| `CollectHoneycombTask.java`                 | Collect honeycomb                                      |
| `CollectIronIngotTask.java`                 | Obtain iron ingots                                     |
| `CollectMagmaCreamTask.java`                | Collect magma cream                                    |
| `CollectMeatTask.java`                      | Collect meat                                           |
| `CollectMilkTask.java`                      | Collect milk                                           |
| `CollectNetherBricksTask.java`              | Collect nether bricks                                  |
| `CollectObsidianTask.java`                  | Collect obsidian                                       |
| `CollectPlanksTask.java`                    | Collect planks (any wood type)                         |
| `CollectQuartzTask.java`                    | Collect quartz                                         |
| `CollectRecipeCataloguedResourcesTask.java` | Collect all ingredients for a recipe via TaskCatalogue |
| `CollectRedSandstoneTask.java`              | Collect red sandstone                                  |
| `CollectSandstoneTask.java`                 | Collect sandstone                                      |
| `CollectSaplingsTask.java`                  | Collect saplings                                       |
| `CollectSticksTask.java`                    | Collect sticks                                         |
| `CollectStrippedLogTask.java`               | Collect stripped logs                                  |
| `CollectWheatSeedsTask.java`                | Collect wheat seeds                                    |
| `CollectWheatTask.java`                     | Collect wheat                                          |
| `CollectWoolTask.java`                      | Collect wool                                           |
| `CraftWithMatchingMaterialsTask.java`       | Craft using any matching material variant              |
| `CraftWithMatchingPlanksTask.java`          | Craft using any plank type                             |
| `CraftWithMatchingStrippedLogsTask.java`    | Craft using any stripped log type                      |
| `CraftWithMatchingWoolTask.java`            | Craft using any wool color                             |
| `GetBuildingMaterialsTask.java`             | Collect generic building materials                     |
| `GetSmithingTemplateTask.java`              | Obtain smithing template                               |
| `KillAndLootTask.java`                      | Kill entity and collect drops                          |
| `KillEndermanTask.java`                     | Kill enderman for pearls                               |
| `MineAndCollectTask.java`                   | Mine and collect a block type                          |
| `SatisfyMiningRequirementTask.java`         | Obtain required mining tool tier                       |
| `ShearAndCollectBlockTask.java`             | Shear and collect (e.g. beehive)                       |
| `TradeWithPiglinsTask.java`                 | Trade gold with piglins                                |
| `wood/CollectBoatTask.java`                 | Craft/collect a boat                                   |
| `wood/CollectFenceGateTask.java`            | Craft/collect fence gates                              |
| `wood/CollectFenceTask.java`                | Craft/collect fences                                   |
| `wood/CollectHangingSignTask.java`          | Craft/collect hanging signs                            |
| `wood/CollectSignTask.java`                 | Craft/collect signs                                    |
| `wood/CollectWoodenButtonTask.java`         | Craft/collect wooden buttons                           |
| `wood/CollectWoodenDoorTask.java`           | Craft/collect wooden doors                             |
| `wood/CollectWoodenPressurePlateTask.java`  | Craft/collect wooden pressure plates                   |
| `wood/CollectWoodenSlabTask.java`           | Craft/collect wooden slabs                             |
| `wood/CollectWoodenStairsTask.java`         | Craft/collect wooden stairs                            |
| `wood/CollectWoodenTrapDoorTask.java`       | Craft/collect wooden trapdoors                         |

### tasks/slot/ — Slot Manipulation

| File                                         | Description                                              |
| -------------------------------------------- | -------------------------------------------------------- |
| `ClickSlotTask.java`                       | Click a specific slot with configurable action type      |
| `EnsureFreeCursorSlotTask.java`            | Clear items from cursor slot                             |
| `EnsureFreeInventorySlotTask.java`         | Free up an inventory slot by discarding junk             |
| `EnsureFreePlayerCraftingGridTask.java`    | Clear the 2x2 player crafting grid                       |
| `MoveInaccessibleItemToInventoryTask.java` | Move items from container-only slots to player inventory |
| `MoveItemToSlotFromContainerTask.java`     | Move item from container slot to target slot             |
| `MoveItemToSlotFromInventoryTask.java`     | Move item from inventory to a specific slot              |
| `MoveItemToSlotTask.java`                  | Generic item-to-slot movement                            |
| `ReceiveCraftingOutputSlotTask.java`       | Extract crafted items from output slot via server packet |
| `ThrowCursorTask.java`                     | Throw item held on cursor                                |
| `WithdrawFromOverflowTask.java`            | Withdraw items from EndInv overflow storage              |

### tasks/speedrun/ — Beat-the-Game

| File                                                 | Description                                    |
| ---------------------------------------------------- | ---------------------------------------------- |
| `BeatMinecraftConfig.java`                         | Configuration for speedrun task                |
| `DragonBreathTracker.java`                         | Tracks dragon breath timing                    |
| `KillEnderDragonTask.java`                         | Kill the ender dragon                          |
| `KillEnderDragonWithBedsTask.java`                 | Kill dragon using bed explosions               |
| `OneCycleTask.java`                                | One-cycle dragon kill                          |
| `WaitForDragonAndPearlTask.java`                   | Wait for dragon perch and pearl                |
| `beatgame/BeatMinecraftTask.java`                  | Full beat-the-game task orchestration          |
| `beatgame/UselessItems.java`                       | Items safe to discard during speedrun          |
| `beatgame/prioritytask/tasks/*.java`               | Priority-based subtask scheduling for speedrun |
| `beatgame/prioritytask/prioritycalculators/*.java` | Priority scoring for speedrun subtasks         |

### tasks/squashed/ — Task Optimization

| File                            | Description                                                        |
| ------------------------------- | ------------------------------------------------------------------ |
| `CataloguedResourceTask.java` | Batched resource collection from TaskCatalogue                     |
| `CraftSquasher.java`          | Merges multiple CraftInTableTask instances into one optimized task |
| `SmithingSquasher.java`       | Merges multiple smithing tasks                                     |
| `TypeSquasher.java`           | Base squasher for combining same-type tasks                        |

## adris/altoclef/tasksystem/ — Task Framework

| File                            | Description                                                       |
| ------------------------------- | ----------------------------------------------------------------- |
| `ITaskCanForce.java`          | Interface for tasks that can force execution                      |
| `ITaskOverridesGrounded.java` | Interface for tasks that override grounded requirement            |
| `ITaskRequiresGrounded.java`  | Interface for tasks requiring player to be on ground              |
| `ITaskUsesCraftingGrid.java`  | Interface marking tasks that use crafting grid                    |
| `Task.java`                   | Abstract base class — lifecycle, subtask management, debug state |
| `TaskChain.java`              | Abstract prioritized task container                               |
| `TaskRunner.java`             | Ticks all chains, selects highest-priority active chain           |

## adris/altoclef/trackers/ — World Tracking

| File                                          | Description                                                  |
| --------------------------------------------- | ------------------------------------------------------------ |
| `CraftingRecipeTracker.java`                | Item-to-recipe mappings from ServerRecipeManager             |
| `EntityStuckTracker.java`                   | Detects entities that are stuck                              |
| `EntityTracker.java`                        | Searchable entity caches (items, mobs, players, projectiles) |
| `MiscBlockTracker.java`                     | Tracks misc block positions (beds, portals)                  |
| `SimpleChunkTracker.java`                   | Tracks loaded/scanned chunks                                 |
| `Tracker.java`                              | Base tracker class                                           |
| `TrackerManager.java`                       | Manages all tracker instances                                |
| `UserBlockRangeTracker.java`                | Tracks user-specified block ranges                           |
| `blacklisting/AbstractObjectBlacklist.java` | Base for object blacklisting                                 |
| `blacklisting/EntityLocateBlacklist.java`   | Blacklist for entity searches                                |
| `blacklisting/WorldLocateBlacklist.java`    | Blacklist for block searches                                 |
| `storage/ContainerCache.java`               | Cached container contents                                    |
| `storage/ContainerSubTracker.java`          | Tracks container contents                                    |
| `storage/ContainerType.java`                | Container type enum                                          |
| `storage/InventorySubTracker.java`          | Tracks player inventory state                                |
| `storage/ItemStorageTracker.java`           | Unified storage access (inventory + containers)              |

## adris/altoclef/ui/ — Overlays & UI

| File                            | Description                      |
| ------------------------------- | -------------------------------- |
| `AltoClefTickChart.java`      | Performance tick timing chart    |
| `ChatclefToggleButton.java`   | UI toggle for chat command mode  |
| `CommandStatusOverlay.java`   | On-screen task status overlay    |
| `MessagePriority.java`        | Chat message priority levels     |
| `MessageSender.java`          | Rate-limited chat message sender |
| `PlayerModeToggleButton.java` | UI toggle for player mode        |
| `STTfeedback.java`            | Speech-to-text feedback display  |

## adris/altoclef/util/ — Utilities

| File                               | Description                                                      |
| ---------------------------------- | ---------------------------------------------------------------- |
| `BlockRange.java`                | Represents a 3D block range                                      |
| `CraftingRecipe.java`            | Recipe data structure (slots array, shape, output count)         |
| `Dimension.java`                 | Dimension enum (overworld, nether, end)                          |
| `ItemTarget.java`                | Target item(s) with count                                        |
| `JankCraftingRecipeMapping.java` | Recipe book lookup: Item → NetworkRecipeId via ClientRecipeBook |
| `MiningRequirement.java`         | Mining tier requirements (wood, stone, iron, diamond)            |
| `Pair.java`                      | Generic pair tuple                                               |
| `RecipeTarget.java`              | Recipe + output item + target count                              |
| `SmeltTarget.java`               | Smelting target (input item → output item)                      |
| `WoodType.java`                  | Wood type variants                                               |

### util/helpers/

| File                       | Description                                                        |
| -------------------------- | ------------------------------------------------------------------ |
| `AutoTorchPlacer.java`   | Automatic torch placement in dark areas                            |
| `BaritoneHelper.java`    | Baritone pathfinding utilities                                     |
| `ConfigHelper.java`      | Configuration file I/O                                             |
| `ContainerHelper.java`   | Container interaction utilities                                    |
| `CraftingHelper.java`    | Recursive recipe validation against current inventory              |
| `EntityHelper.java`      | Entity query and classification utilities                          |
| `FuzzySearchHelper.java` | Fuzzy string matching for item names                               |
| `InputHelper.java`       | Input state utilities                                              |
| `ItemHelper.java`        | Item classification, comparison, and constants (LOG, PLANKS, etc.) |
| `LookHelper.java`        | Rotation/raycasting utilities for player look direction            |
| `MathsHelper.java`       | Math utilities                                                     |
| `ProjectileHelper.java`  | Projectile trajectory calculation                                  |
| `StlHelper.java`         | Collection/stream utilities                                        |
| `StorageHelper.java`     | Container/slot/inventory state queries and manipulation            |
| `TaskHelper.java`        | Task utility methods                                               |
| `WorldHelper.java`       | World query utilities (block checks, biome, reachability)          |

### util/baritone/

| File                             | Description                                      |
| -------------------------------- | ------------------------------------------------ |
| `CachedProjectile.java`        | Cached projectile data for dodge calculations    |
| `GoalAnd.java`                 | Composite goal (all sub-goals must be satisfied) |
| `GoalBlockSide.java`           | Goal to reach a specific side of a block         |
| `GoalChunk.java`               | Goal to reach a chunk                            |
| `GoalDirectionXZ.java`         | Goal to move in a direction                      |
| `GoalDodgeProjectiles.java`    | Goal to dodge projectiles                        |
| `GoalFollowEntity.java`        | Goal to follow an entity                         |
| `GoalRunAwayFromEntities.java` | Goal to flee from entities                       |
| `PlaceBlockSchematic.java`     | Single-block schematic for Baritone placement    |

### util/progresscheck/

| File                             | Description                               |
| -------------------------------- | ----------------------------------------- |
| `DistanceProgressChecker.java` | Progress check based on distance traveled |
| `IProgressChecker.java`        | Progress checker interface                |
| `LinearProgressChecker.java`   | Linear value progress checker             |
| `MovementProgressChecker.java` | Movement-based stuck detection            |
| `ProgressCheckerRetry.java`    | Progress checker with retry logic         |

### util/serialization/

| File                                | Description                     |
| ----------------------------------- | ------------------------------- |
| `AbstractVectorDeserializer.java` | Base vector JSON deserializer   |
| `AbstractVectorSerializer.java`   | Base vector JSON serializer     |
| `BlockPosDeserializer.java`       | BlockPos from JSON              |
| `BlockPosSerializer.java`         | BlockPos to JSON                |
| `ChunkPosDeserializer.java`       | ChunkPos from JSON              |
| `ChunkPosSerializer.java`         | ChunkPos to JSON                |
| `IFailableConfigFile.java`        | Config file with error handling |
| `IListConfigFile.java`            | List-based config file          |
| `ItemDeserializer.java`           | Item from JSON                  |
| `ItemSerializer.java`             | Item to JSON                    |
| `Vec3dDeserializer.java`          | Vec3d from JSON                 |
| `Vec3dSerializer.java`            | Vec3d to JSON                   |

### util/slots/

| File                       | Description                                                      |
| -------------------------- | ---------------------------------------------------------------- |
| `BlastFurnaceSlot.java`  | Blast furnace slot indices                                       |
| `BrewingStandSlot.java`  | Brewing stand slot indices                                       |
| `ChestSlot.java`         | Chest slot indices                                               |
| `CraftingTableSlot.java` | Crafting table slot indices (0=output, 1-9=grid)                 |
| `CursorSlot.java`        | Cursor (held item) slot                                          |
| `FurnaceSlot.java`       | Furnace slot indices                                             |
| `PlayerSlot.java`        | Player inventory slot indices (hotbar, armor, offhand, 2x2 grid) |
| `Slot.java`              | Base slot class with window-slot mapping                         |
| `SlotScreenMapping.java` | Maps slot types to screen handler indices                        |
| `SmithingTableSlot.java` | Smithing table slot indices                                      |
| `SmokerSlot.java`        | Smoker slot indices                                              |

### util/time/

| File               | Description                  |
| ------------------ | ---------------------------- |
| `BaseTimer.java` | Abstract timer base          |
| `Stopwatch.java` | Elapsed time measurement     |
| `TimerGame.java` | Game-tick based timer        |
| `TimerReal.java` | Real-time (wall clock) timer |

## adris/altoclef/skinchanger/

| File                 | Description                  |
| -------------------- | ---------------------------- |
| `SkinChanger.java` | Player skin changing utility |
| `SkinType.java`    | Skin type enum               |

## baritone/altoclef/

| File                      | Description                                   |
| ------------------------- | --------------------------------------------- |
| `AltoClefSettings.java` | AltoClef-specific Baritone settings overrides |

---

## C:\Users\Owner\emma-ai-phase-57b-debug\emma-ai-phase-57b-debug\gamer\minecraft\bridge_mod\src\main\java\com\emma\bridge— Emma WebSocket Bridge

### Core

| File                      | Description                                                                    |
| ------------------------- | ------------------------------------------------------------------------------ |
| `EmmaBridgeMod.java`    | Common mod initializer — logs version on startup                              |
| `EmmaBridgeClient.java` | Client initializer — registers all 30+ command handlers and event reporters   |
| `BridgeServer.java`     | WebSocket server bridging Python orchestrator ↔ Minecraft/Baritone            |
| `BridgeConfig.java`     | Configuration loader — reads/writes emma_bridge.json with mode-aware settings |

### bridge/commands/ — WebSocket Command Handlers

| File                             | Description                                                              |
| -------------------------------- | ------------------------------------------------------------------------ |
| `CommandRouter.java`           | Routes incoming command names to handlers (mode-aware: camera vs player) |
| `TaskRegistry.java`            | Correlates command-issued task IDs with type/state for events            |
| `HandlerUtils.java`            | Shared utilities for command handlers                                    |
| `ICommandHandler.java`         | Synchronous command handler interface                                    |
| `IAsyncCommandHandler.java`    | Async command handler interface                                          |
| `AltoClefHandler.java`         | Dispatches AltoClef task commands                                        |
| `AltoClefStatusHandler.java`   | Returns AltoClef task status                                             |
| `AttackHandler.java`           | Attack entity command                                                    |
| `BiomeHandler.java`            | Query biome at position                                                  |
| `BreakBlockHandler.java`       | Break a block at coordinates                                             |
| `BuildHandler.java`            | Build from schematic                                                     |
| `CameraHandler.java`           | Camera position/mode control                                             |
| `CancelHandler.java`           | Cancel current task                                                      |
| `ChatHandler.java`             | Send chat message                                                        |
| `ClearAreaHandler.java`        | Clear blocks in area                                                     |
| `ClickSlotHandler.java`        | Generic slot click (crafting, furnace, any screen)                       |
| `CloseScreenHandler.java`      | Close open GUI screen                                                    |
| `ConfigureCameraHandler.java`  | Configure camera presets                                                 |
| `CreateFarmHandler.java`       | Create farm command                                                      |
| `DismountHandler.java`         | Dismount from entity                                                     |
| `DropItemHandler.java`         | Drop items from inventory                                                |
| `FarmHandler.java`             | Farm command handler                                                     |
| `FurnaceStatusHandler.java`    | Read furnace burn/cook state                                             |
| `GetEffectsHandler.java`       | Query active potion effects                                              |
| `GetEntitiesHandler.java`      | Query nearby entities                                                    |
| `GetTargetedBlockHandler.java` | Get block player is looking at                                           |
| `GotoHandler.java`             | Navigate to position                                                     |
| `HeightmapHandler.java`        | Query heightmap data                                                     |
| `InteractBlockHandler.java`    | Right-click a block                                                      |
| `InteractEntityHandler.java`   | Interact with an entity                                                  |
| `InventoryHandler.java`        | Read full inventory state                                                |
| `LookAtHandler.java`           | Rotate player to look at position                                        |
| `MineHandler.java`             | Mine a block type                                                        |
| `MountHandler.java`            | Mount an entity                                                          |
| `MoveItemHandler.java`         | Move item between slots                                                  |
| `OverflowHandler.java`         | EndInv overflow operations                                               |
| `PanicTeleportHandler.java`    | Emergency teleport                                                       |
| `PlaceBlockHandler.java`       | Place block at coordinates                                               |
| `ReadScreenHandler.java`       | Read current screen type and slot contents                               |
| `RespawnHandler.java`          | Respawn after death                                                      |
| `ScanHandler.java`             | Scan for blocks/entities                                                 |
| `SetPresetHandler.java`        | Set camera preset                                                        |
| `SetSlotHandler.java`          | Set specific slot contents                                               |
| `StatusHandler.java`           | Player status (health, hunger, position)                                 |
| `StorageHandler.java`          | Query storage containers                                                 |
| `SurfaceMapHandler.java`       | Surface block map data                                                   |
| `SwapHandsHandler.java`        | Swap main/offhand items                                                  |
| `TaskTreeHandler.java`         | Returns full AltoClef task hierarchy as JSON                             |
| `TorchHandler.java`            | Place torches command                                                    |
| `UseItemHandler.java`          | Use held item                                                            |
| `WorldInfoHandler.java`        | World info (time, weather, dimension)                                    |
| `WorldScanHandler.java`        | Broad world block scanning                                               |

### bridge/events/ — Event Reporters

| File                           | Description                                                         |
| ------------------------------ | ------------------------------------------------------------------- |
| `EventReporter.java`         | Central event dispatcher — coordinates all sub-reporters each tick |
| `AltoClefEventReporter.java` | Monitors AltoClef task state, emits WebSocket events                |
| `BlockEventListener.java`    | Block change events                                                 |
| `DamageListener.java`        | Damage events                                                       |
| `EntityScanner.java`         | Periodic nearby entity scanning                                     |
| `HeadRecenter.java`          | Auto-recenters pitch to 0° after task completion (3 ticks)         |
| `HealthTracker.java`         | Health change events                                                |
| `InventoryTracker.java`      | Inventory change events                                             |
| `PanicTeleport.java`         | Emergency teleport when health critically low                       |
| `PositionTracker.java`       | Position change events                                              |
| `SubtitleListener.java`      | Subtitle/sound events                                               |
| `TaskListener.java`          | Task lifecycle events                                               |
| `TravelLookOverride.java`    | Blends yaw toward travel direction during navigation                |

### bridge/camera/

| File                   | Description                                                                |
| ---------------------- | -------------------------------------------------------------------------- |
| `CameraTracker.java` | Receives position updates, teleports CameraBot to computed camera position |
| `CameraPresets.java` | Camera preset configs (offset, pitch, yaw) with runtime overrides          |

### bridge/schematic/

| File                           | Description                                                                  |
| ------------------------------ | ---------------------------------------------------------------------------- |
| `GuideSchematic.java`        | Baritone schematic backed by 3D BlockState array (no .schematic file needed) |
| `GuideSchematicAdapter.java` | Adapter for GuideSchematic to Baritone's schematic interface                 |

### bridge/websocket/

| File                    | Description                                                    |
| ----------------------- | -------------------------------------------------------------- |
| `JsonProtocol.java`   | Utility for building well-formed JSON protocol messages        |
| `MessageHandler.java` | Parses incoming JSON, dispatches commands via tick-based queue |
