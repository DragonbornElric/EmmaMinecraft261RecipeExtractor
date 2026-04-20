# API Commands: Mod Packs → Fabric → Minecraft

> Running list of every Fabric API and Minecraft API call used across all 5 mods.

---

## 1. emma-pathfinder (Main Mod)

### Fabric API Callbacks

| Callback | File | Purpose |
|----------|------|---------|
| `ClientLifecycleEvents.CLIENT_STARTED` | EmmaBridgeClient.java:106 | Init WebSocket server |
| `ClientLifecycleEvents.CLIENT_STOPPING` | EmmaBridgeClient.java:111 | Shutdown WebSocket server |
| `ClientTickEvents.START_CLIENT_TICK` | EmmatoneTickDispatcher.java:49 | Pre-tick Emmatone events (PlayerUpdateEvent.PRE) |
| `ClientTickEvents.END_CLIENT_TICK` | EmmatoneTickDispatcher.java:69, EmmaBridgeClient.java:116 | Post-tick events, GOAP ticker, command processing |
| `ClientChunkEvents.CHUNK_LOAD` | EmmatoneTickDispatcher.java:102 | Fire ChunkEvent.POPULATE_FULL |
| `ClientChunkEvents.CHUNK_UNLOAD` | EmmatoneTickDispatcher.java:113 | Fire ChunkEvent.UNLOAD, pack chunk data |
| `PlayerBlockBreakEvents.AFTER` | BlockEventListener.java:40 | Broadcast block_broken events via WebSocket |
| `UseBlockCallback.EVENT` | BlockEventListener.java:63 | Track pending block placements |
| `ClientSendMessageEvents.ALLOW_CHAT` | ChatCommandInterceptor.java:46 | Intercept "@" chat commands |

### Minecraft Client API

| API Call | Files | Purpose |
|----------|-------|---------|
| `Minecraft.getInstance()` | Throughout | Get client instance |
| `.player` | StatusHandler, InventoryHandler, etc. | Access LocalPlayer |
| `.level` | EmmatoneTickDispatcher, WorldState, etc. | Access ClientLevel |
| `.screen` | DamageListener, ContainerTracker | Detect current GUI screen |
| `.gameMode` | StatusHandler:63 | Get player game mode |
| `.execute()` | ChatCommandInterceptor:62 | Schedule tasks on game thread |

### LocalPlayer API

| API Call | Files | Purpose |
|----------|-------|---------|
| `.getHealth()` | StatusHandler:53, DamageListener:41 | Current HP |
| `.getMaxHealth()` | StatusHandler:54 | Max HP |
| `.getFoodData().getFoodLevel()` | StatusHandler:55 | Hunger level |
| `.getFoodData().getSaturationLevel()` | StatusHandler:71 | Saturation |
| `.getArmorValue()` | StatusHandler:56 | Armor defense |
| `.getInventory()` | InventoryHandler:32, WorldState:206+ | Player inventory access |
| `.getX()`, `.getY()`, `.getZ()` | StatusHandler, many | Position coordinates |
| `.getYRot()`, `.getXRot()` | StatusHandler:48-49 | Yaw/pitch rotation |
| `.experienceLevel` | StatusHandler:69 | XP level |
| `.totalExperience` | StatusHandler:70 | Total XP |
| `.getAirSupply()` / `.getMaxAirSupply()` | StatusHandler:72-73 | Breath underwater |
| `.getRemainingFireTicks()` | StatusHandler:74 | Fire damage timer |
| `.getTicksFrozen()` | StatusHandler:75 | Powder snow freeze |
| `.getAbsorptionAmount()` | StatusHandler:76 | Absorption hearts |
| `.isOnFire()` / `.isUnderWater()` / `.isInLava()` | StatusHandler:77-79 | Environmental status |
| `.getScore()` | StatusHandler:80 | Player score |
| `.containerMenu` | ContainerTracker:67 | Current open container |
| `.blockPosition()` | Various | Block coords of player |
| `.getActiveEffectsMap()` | GetEntitiesHandler:95 | Active status effects |
| `.getBoundingBox().inflate(radius)` | GetEntitiesHandler:56 | AABB for entity scanning |
| `.distanceTo(entity)` | Various | Distance calculation |
| `.getInventory().getSelectedSlot()` | InventoryHandler:59 | Active hotbar slot |
| `.getInventory().setSelectedSlot(slot)` | DragonCombatAction:475,483 | Change hotbar slot |
| `.getInventory().getItem(i)` | InventoryHandler:36, CraftItemAction:461 | Get item at slot |
| `.getInventory().contains(itemStack)` | CollectFoodAction:553 | Check item in inventory |

### ClientLevel API

| API Call | Files | Purpose |
|----------|-------|---------|
| `.getBlockState(pos)` | BlockEventListener:92, WorldState:180, GOAP actions | Get block at position |
| `.getEntities(player, aabb)` | AttackEntityAction:259, WorldState:323 | Get entities in area |
| `.getEntitiesOfClass(ItemEntity.class, aabb)` | DeathRecoveryAction:234 | Get dropped items |
| `.dimension().identifier()` | Various | Current dimension ID |
| `.isThundering()` / `.isRaining()` | WorldState:180-181, WorldInfoHandler:41-43 | Weather status |
| `.getDifficulty()` | WorldInfoHandler | Game difficulty |
| `.getGameTime()` | Various | World time in ticks |

### Entity & LivingEntity API

| API Call | Files | Purpose |
|----------|-------|---------|
| `Entity.getType()` | GetEntitiesHandler:67 | Entity type for registry |
| `Entity.getX/Y/Z()` | GetEntitiesHandler | Position |
| `Entity.isAlive()` | GetEntitiesHandler:65 | Alive check |
| `LivingEntity.getHealth()` | GetEntitiesHandler:85 | Current HP |
| `LivingEntity.getMaxHealth()` | GetEntitiesHandler:86 | Max HP |
| `LivingEntity.getActiveEffectsMap()` | GetEntitiesHandler:95 | Status effects |
| `LivingEntity.getEquipment(slot)` | GetEntitiesHandler:89 | Equipped item |

### ItemStack API

| API Call | Files | Purpose |
|----------|-------|---------|
| `.isEmpty()` | InventoryHandler, many | Check empty stack |
| `.getCount()` | InventoryHandler | Stack size |
| `.getDamageValue()` | InventoryHandler:42 | Durability damage |
| `.getMaxDamage()` | InventoryHandler | Max durability |
| `.getItem()` | InventoryHandler | Get Item type |
| `.getComponents()` / `.getData(DataComponents.xxx)` | WorldState | Data component access |

### Registry Access

| Registry | Files | Purpose |
|----------|-------|---------|
| `BuiltInRegistries.BLOCK.getKey(block)` | BlockEventListener:48, ScanHandler | Block → ResourceLocation |
| `BuiltInRegistries.ITEM.getKey(item)` | InventoryHandler:40 | Item → ResourceLocation |
| `BuiltInRegistries.ENTITY_TYPE.getKey(type)` | GetEntitiesHandler:67 | Entity → ResourceLocation |
| `BuiltInRegistries.MOB_EFFECT.getKey(effect)` | GetEntitiesHandler:100 | Effect → ResourceLocation |

### BlockState / BlockPos API

| API Call | Files | Purpose |
|----------|-------|---------|
| `BlockState.getBlock()` | Various | Get Block from state |
| `BlockState.isAir()` | GOAP actions | Air check |
| `BlockState.isSolid()` | GOAP actions | Collision check |
| `BlockState.getProperties()` | Various | State properties (age, half, facing) |
| `BlockState.is(Blocks.CRAFTING_TABLE)` | CraftItemAction | Block identity check |
| `BlockPos.relative(direction)` | BlockEventListener:67 | Offset in direction |
| `BlockPos.below()` / `.above()` / `.offset(x,y,z)` | Various | Coordinate manipulation |
| `BlockPos.getCenter()` | Various | Center point of block |

### Screen / Component API

| API Call | Files | Purpose |
|----------|-------|---------|
| `DeathScreen` detection | DamageListener:52 | Detect death screen |
| `Component.literal(text)` | ChatCommandInterceptor:506 | Create text component |
| `Component.getString()` | Various | Get text from component |

### Enchantment / Effect API

| API Call | Files | Purpose |
|----------|-------|---------|
| `EnchantmentHelper.getEnchantments(stack)` | WorldState | Get enchantments on item |
| `DataComponents.ENCHANTMENTS` | WorldState | Enchantment data component |
| `MobEffectInstance.getEffect()` | GetEntitiesHandler | Effect type |
| `MobEffectInstance.getDuration()` | GetEntitiesHandler | Remaining ticks |
| `MobEffectInstance.getAmplifier()` | GetEntitiesHandler | Effect level |

### Mixins (3 Emmatone + ~34 Bridge)

**Emmatone core:**
| Mixin | Target | Purpose |
|-------|--------|---------|
| MixinClientPlayNetHandler | ClientPacketListener | Block change packet interception |
| MixinClientChunkProvider | ClientChunkCache | Thread-safe chunk snapshots |
| MixinChunkArray | Chunk storage | Chunk array access |

**Bridge/AltoCleF (~34 mixins):**
| Mixin | Target | Purpose |
|-------|--------|---------|
| MixinClientTickMixin | Minecraft | Tick loop integration |
| MixinClientInteractWithBlockMixin | Block interaction | Block use event hooks |
| MixinClientBlockBreakMixin | Block breaking | Break mechanics |
| MixinClientUIMixin | Screen/GUI | GUI integration |
| SlotClickMixin | Container slots | Slot click interception |
| PlayerDamageMixin | Damage calc | Damage event hooks |
| SimplOptionMixin | Settings | Settings integration |
| *(~27 more)* | Various | Various pathfinder integrations |

### GOAP WorldState Snapshot (per tick)

Captured every tick by GoapTicker via `END_CLIENT_TICK`:
- `player.getHealth()`, `player.getFoodData()`, position, dimension, armor value
- `player.getInventory()` — full inventory scan
- `world.getBlockState()` — nearby block checks
- `world.getEntities(player, scanBox)` — threat detection
- Weather, time, light level

---

## 2. emma-gameplay-logger (external repo)

Note: `emma-gameplay-logger` has since moved to its own repository: `https://github.com/DragonbornElric/EmmaMinecraft261Logger`

### Fabric API Callbacks

| Callback | File | Purpose |
|----------|------|---------|
| `ServerTickEvents.END_SERVER_TICK` | GameplayLoggerMod.java:66 | Drive state snapshots + pending placements |
| `ServerPlayConnectionEvents.JOIN` | GameplayLoggerMod.java:52 | Auto-start logging on player join |
| `ServerPlayConnectionEvents.DISCONNECT` | GameplayLoggerMod.java:61 | Stop logging on disconnect |
| `CommandRegistrationCallback.EVENT` | GameplayLoggerMod.java:79 | Register `/logger` commands |
| `PlayerBlockBreakEvents.AFTER` | BlockEventLogger.java:34 | Log block breaking with tool + coords |
| `UseBlockCallback.EVENT` | BlockEventLogger.java:53 | Log block placement candidates |

### Minecraft API

| API Call | Files | Purpose |
|----------|-------|---------|
| `ServerPlayer.getUUID()` | PlayerLogger | Player identity |
| `ServerPlayer.getMainHandItem()` | BlockEventLogger | Tool used for breaking |
| `ServerPlayer.hasDisconnected()` | GameplayLoggerMod | Connection check |
| `ServerPlayer.getName()` | GameplayLoggerMod | Player name |
| `ServerPlayer.getInventory()` | PlayerStateCapture | Inventory snapshot |
| `ServerPlayer.getHealth()` | PlayerStateCapture | Health capture |
| `ServerPlayer.getFoodData()` | PlayerStateCapture | Hunger/saturation |
| `ServerPlayer.level()` | Various | Get ServerLevel |
| `ServerLevel.getBlockState()` | BlockEventLogger | Block verification |
| `ServerLevel.getServer()` | Various | Server access |
| `MinecraftServer.getTickCount()` | GameplayLoggerMod | Tick timing |
| `MinecraftServer.getPlayerList().getPlayer(UUID)` | GameplayLoggerMod | Player lookup |
| `MinecraftServer.getPlayerList().getPlayers()` | GameplayLoggerMod | All players |
| `DamageSource.type()` / `.msgId()` / `.getEntity()` | ServerPlayerDamageMixin | Damage info |
| `DamageSource.getLocalizedDeathMessage()` | LivingEntityDeathMixin | Death message |
| `LivingEntity.distanceTo()` | LivingEntityDeathMixin | Kill distance |
| `LivingEntity.getUseItem()` / `.getMainHandItem()` | ItemConsumeServerMixin | Consumable tracking |
| `AbstractContainerMenu.getType()` / `.removed()` | ScreenHandlerMixin | Container tracking |
| `BuiltInRegistries.BLOCK.getKey()` | BlockEventLogger | Block ID |
| `BuiltInRegistries.ITEM.getKey()` | BlockEventLogger, PlayerStateCapture | Item ID |
| `BuiltInRegistries.ENTITY_TYPE.getKey()` | LivingEntityDeathMixin | Entity ID |
| `BlockHitResult.getDirection()` | BlockEventLogger | Placement direction |
| `BlockPos.relative(Direction)` | BlockEventLogger | Adjacent block |
| `Component.literal()` | GameplayLoggerMod | Chat feedback |

### Commands (Brigadier)

| Command | Purpose |
|---------|---------|
| `/logger start <player>` | Start logging a player |
| `/logger stop <player>` | Stop logging a player |
| `/logger startall` | Start logging all players |
| `/logger status` | Show logging status |

### Mixins (5)

| Mixin | Target | Injection | Purpose |
|-------|--------|-----------|---------|
| ServerChatMixin | ServerGamePacketListenerImpl | HEAD (handleChat) | Intercept "!note " for annotations |
| ServerPlayerDamageMixin | LivingEntity | RETURN (actuallyHurt) | Log damage taken |
| LivingEntityDeathMixin | LivingEntity | HEAD (die) | Log entity deaths/kills |
| ItemConsumeServerMixin | LivingEntity | HEAD+RETURN (completeUsingItem) | Log food/potion consumption |
| ScreenHandlerMixin | AbstractContainerMenu | HEAD (removed) | Log container closes |

---

## 3. emma-endinv (Endless Inventory)

### Fabric API Callbacks

| Callback | File | Purpose |
|----------|------|---------|
| `AttachmentRegistry.create()` (×2) | ModInit.java:38,49 | ENDINV_UUID + SYNCED_CONFIG attachments |
| `CommandRegistrationCallback.EVENT` | Commands.java:13 | `/endinv`, `/config` commands |
| `ServerTickEvents.END_SERVER_TICK` | PlayerEvents.java:30 | Flush player sync queue |
| `ServerPlayConnectionEvents.JOIN` | PlayerEvents.java:31 | Sync EndInv on join |
| `ServerPlayerEvents.COPY_FROM` | PlayerEvents.java:34 | Copy attachments on respawn |
| `ServerLevelEvents.LOAD` | LevelEvents.java:14 | Init EndlessInventoryData per level |
| `PlayerBlockBreakEvents.BEFORE` | BlockBreakRedirect.java:14 | Capture breaker for autopick |
| `PlayerBlockBreakEvents.AFTER` | BlockBreakRedirect.java:22 | Clear breaker tracking |
| `ClientLifecycleEvents.CLIENT_STOPPING` | ClientModInit.java:42 | Save client configs |
| `ClientTickEvents.END_CLIENT_TICK` | KeyMappingTrigger.java:16 | Poll key mappings |

### Fabric Networking

**C2S (Client → Server) Payloads:**
| Payload Type | File | Purpose |
|-------------|------|---------|
| ItemClickPayload | FabricNetworking.java | Item click action |
| CreativeItemModPayload | FabricNetworking.java | Creative mode item mod |
| ItemPageContext | FabricNetworking.java | Page navigation context |
| OpenEndInvPayload | FabricNetworking.java | Open EndInv GUI |
| QuickMoveToPagePayload | FabricNetworking.java | Quick-move to specific page |
| BulkQuickMoveFromPagePayload | FabricNetworking.java | Bulk quick-move from page |
| StarItemPayload | FabricNetworking.java | Star/favorite item |
| ToggleCraftingPayload | FabricNetworking.java | Toggle crafting mode |
| SyncedConfig | FabricNetworking.java | Config sync |

**S2C (Server → Client) Payloads:**
| Payload Type | File | Purpose |
|-------------|------|---------|
| EndInvContent | FabricNetworking.java | Inventory content sync |
| EndInvMetadata | FabricNetworking.java | Inventory metadata |
| ItemPickedUpPayload | FabricNetworking.java | Item pickup notification |
| SetItemDisplayContentPayload | FabricNetworking.java | Display content update |
| SetStarredPagePayload | FabricNetworking.java | Starred page sync |
| MenuAttachabilityPayload | FabricNetworking.java | Menu attachment state |
| SyncedConfig | FabricNetworking.java | Config sync |

**Networking API:**
- `PayloadTypeRegistry.serverboundPlay()` — Register C2S packets
- `PayloadTypeRegistry.clientboundPlay()` — Register S2C packets
- `ServerPlayNetworking.registerGlobalReceiver()` — Server handlers
- `ClientPlayNetworking.registerGlobalReceiver()` — Client handlers
- `ServerPlayNetworking.send()` — Send S2C
- `ClientPlayNetworking.send()` — Send C2S

### Fabric Client API

| API Call | File | Purpose |
|----------|------|---------|
| `KeyMappingHelper.registerKeyMapping()` (×3) | ClientModInit.java:36-38 | OPEN_MENU, QUICK_MOVE, STAR_ITEM_ALTER |
| `HudElementRegistry.addLast()` | PickingUpTip.java:18 | Autopick HUD feedback |
| `ScreenEvents.BEFORE_INIT` / `AFTER_INIT` | ScreenAttachment.java:47,54 | Screen lifecycle hooks |
| `ScreenEvents.remove` / `afterExtract` | ScreenAttachment.java:105,116 | Screen cleanup hooks |
| `ScreenKeyboardEvents.allowKeyPress` / `afterKeyPress` | ScreenAttachment.java:150,28 | Keyboard events |
| `ScreenMouseEvents.allowMouseClick/Release/Scroll` | ScreenAttachment.java:146-148 | Mouse events |
| `ScreenCharTypedEvents.BEFORE_CHAR_TYPED` | ScreenAttachment.java:43 | Character input |

### Minecraft API

| API Call | Files | Purpose |
|----------|-------|---------|
| `BuiltInRegistries.ITEM.register()` | ModInit | Register custom items |
| `BuiltInRegistries.MENU.register()` | ModInit | Register menu types |
| `Registry.register()` | ModInit | Direct registration |
| `AttachmentType` builder chain | ModInit | Persistent player data |
| `SlotAccess`, `ClickAction` | Item interaction | Stack interaction |
| `Player.level().enabledFeatures()` | Various | Feature flag checks |
| `ItemStack.isItemEnabled()` | Various | Feature gate items |
| `ItemStack.overrideStackedOnOther()` | Various | Custom stack behavior |
| `ItemStack.overrideOtherStackedOnMe()` | Various | Custom stack behavior |
| `FabricLoader.getInstance().isModLoaded()` | Various | Mod compatibility |
| `FabricLoader.getInstance().getConfigDir()` | Various | Config path |
| `MenuType` constructor | ModInit | Menu registration |
| `Component.literal()` | Various | Text components |

### Commands (Brigadier)

| Command | Purpose |
|---------|---------|
| `/endinv` | Open Endless Inventory |
| `/config` | Configure EndInv settings |

### Mixins (11)

| Mixin | Target | Purpose |
|-------|--------|---------|
| BlockDropMixin | Block.playerDestroy | Redirect block drops to EndInv |
| ItemEntityPickupMixin | ItemEntity.playerTouch | Intercept pickup for autopick |
| LivingEntityDropMixin | LivingEntity.dropFromLootTable | Capture killer for mob drops |
| AbstractContainerScreenMixin | AbstractContainerScreen | Menu UI integration |
| ScreenMixin | Screen | Screen event attachment |
| GuiEventListenerCharTypedMixin | GuiEventListener | Character input events |
| RecipeBookComponentMixin | RecipeBookComponent | Recipe book integration |
| ServerPlaceRecipeMixin | ServerPlaceRecipe | Recipe placement interception |
| AbstractContainerScreenAccessor | AbstractContainerScreen (accessor) | getLeftPos, getTopPos, getImageWidth, getImageHeight |
| ScreenAccessor | Screen (accessor) | Screen field access |
| ExperienceOrbAwardMixin | ExperienceOrb | XP distribution |

---

## 4. emma-twitch

Note: `emma-twitch` has since moved to its own repository: `https://github.com/DragonbornElric/emmaminecraft261twitch`

### Fabric API Callbacks

| Callback | File | Purpose |
|----------|------|---------|
| `ServerLifecycleEvents.SERVER_STARTED` | EmmaTwitchMod.java:45 | Init TwitchConfig, CrowdControl, EventSub, WebServer |
| `ServerLifecycleEvents.SERVER_STOPPING` | EmmaTwitchMod.java:46 | Shutdown EventSub, web server, save config |

### Fabric Networking

| API Call | File | Purpose |
|----------|------|---------|
| `ServerPlayNetworking.canSend()` | FabricCrowdControlPlugin.java:68 | Check if player can receive packet |
| `ServerPlayNetworking.send()` | FabricCrowdControlPlugin.java:70 | Send crowd control effect packets |
| `FabricLoader.getInstance()` | FabricCrowdControlPlugin.java:34,61 | Mod metadata, asset loading |

### Minecraft API

| API Call | Files | Purpose |
|----------|-------|---------|
| `MinecraftServer.getServerVersion()` | FabricCrowdControlPlugin | Server version |
| `MinecraftServer.getServerModName()` | FabricCrowdControlPlugin | Mod loader name |
| `MinecraftServer.overworld()` | Various | Overworld level access |
| `ServerPlayer` (many methods) | Crowd control commands | Health, XP, effects, movement |
| `EntityType.spawn()` | Crowd control commands | Mob spawning with EntitySpawnReason |
| `BuiltInRegistries.BLOCK/ITEM/ENTITY_TYPE` | Crowd control commands | Registry tagging |
| `BlockTags` | Various | Block tag queries |
| `MobEffects` registry | Crowd control commands | Apply status effects |
| `Difficulty`, `GameType` enums | Various | Game rule manipulation |
| `ItemStack` modification | Crowd control commands | Durability/enchant changes |
| `Enchantment` registry | Crowd control commands | Enchantment access |
| `CommandSourceStack` → `ServerPlayer` | CrowdControl | Player targeting |

### Commands

CrowdControl framework provides its own command registration — mod acts as a Fabric plugin.

### Mixins

None directly — delegates to CrowdControl library.

---

## 5. emma-recipe-extractor

### Fabric API Callbacks

| Callback | File | Purpose |
|----------|------|---------|
| `CommandRegistrationCallback.EVENT` | RecipeExtractorMod.java:28 | Register `/emma_extract` command (op only) |

### Minecraft API — Registry Access

| Registry | Purpose |
|----------|---------|
| `BuiltInRegistries.BLOCK` | All block IDs |
| `BuiltInRegistries.ITEM` | All item IDs |
| `BuiltInRegistries.ENTITY_TYPE` | All entity types |
| `BuiltInRegistries.MENU` | Menu types |
| `BuiltInRegistries.ENTITY_DATA` | Entity attributes |

### Minecraft API — Recipe System

| API Call | Purpose |
|----------|---------|
| `RecipeManager.recipes` (via ServerLevel) | All recipe data |
| `Recipe<?>.getResultItem()` | Recipe output |
| `Recipe<?>.getIngredients()` | Recipe inputs |
| `ShapedRecipe` / `ShapelessRecipe` | Crafting recipes |
| `FurnaceRecipe` / `SmeltingRecipe` | Furnace recipes |
| `SmokingRecipe` / `BlastingRecipe` | Cooking recipes |
| `CampfireCookingRecipe` | Campfire recipes |
| `StonecutterRecipe` | Stonecutter recipes |
| `SmithingRecipe` | Smithing table recipes |
| `RecipeDisplay` + `SlotDisplayContext` | Recipe display extraction |

### Minecraft API — Loot Tables (via reflection)

| API Call | Purpose |
|----------|---------|
| `LootTable.pools` (reflected) | Loot pool access |
| `LootPool.entries/conditions/functions/rolls/bonusRolls` (reflected) | Pool internals |
| `LootItem`, `NestedLootTable`, `CompositeEntryBase` (reflected) | Entry types |

### Minecraft API — Potion Brewing (via reflection)

| API Call | Purpose |
|----------|---------|
| `PotionBrewing.potionMixes` (reflected) | Potion recipes |
| `PotionBrewing.containerMixes` (reflected) | Container mixes |
| `PotionBrewing.containers` (reflected) | Brewing containers |

### Minecraft API — Other

| API Call | Purpose |
|----------|---------|
| `Enchantment` registry + properties | Enchantment data |
| `Enchantable` / `Repairable` interfaces | Item capabilities |
| `EquipmentSlotGroup` | Armor slot mapping |
| `BlockState.getProperties()` | Block property extraction |
| `EntityType`, `MobCategory`, `MobSpawnSettings` | Mob data |
| `Biome` spawn settings | Biome mob data |
| `SharedConstants.getCurrentVersion().id()` | MC version string |
| `BlockTags` | Block tag sets |

### Commands (Brigadier)

| Command | Purpose |
|---------|---------|
| `/emma_extract` | One-shot full data extraction → `emma_extracted_recipes.json` |

### Mixins

None.

---

## Cross-Mod Summary

### Fabric Callbacks Totals

| Mod | Count | Heaviest Categories |
|-----|-------|---------------------|
| emma-pathfinder | 9 | Client ticks, chunk events, player actions |
| emma-gameplay-logger (external repo) | 6 | Server ticks, connection events, block events |
| emma-endinv | 22+ | Attachments, networking (13 payloads), screen/input events |
| emma-twitch (external repo) | 2 | Server lifecycle only |
| emma-recipe-extractor | 1 | Command registration only |

### Mixin Totals

| Mod | Count | Targets |
|-----|-------|---------|
| emma-pathfinder | ~37 | Client tick, block interaction, packets, chunk cache |
| emma-gameplay-logger (external repo) | 5 | Chat, damage, death, consumption, containers |
| emma-endinv | 11 | Block drops, item pickup, screens, recipes, XP |
| emma-twitch (external repo) | 0 | (via CrowdControl library) |
| emma-recipe-extractor | 0 | — |

### Registry Usage

| Registry | Used By |
|----------|---------|
| `BuiltInRegistries.BLOCK` | pathfinder, logger, endinv, twitch, recipe-extractor |
| `BuiltInRegistries.ITEM` | pathfinder, logger, endinv, twitch, recipe-extractor |
| `BuiltInRegistries.ENTITY_TYPE` | pathfinder, logger, twitch, recipe-extractor |
| `BuiltInRegistries.MOB_EFFECT` | pathfinder |
| `BuiltInRegistries.MENU` | endinv, recipe-extractor |
| `BuiltInRegistries.ENTITY_DATA` | recipe-extractor |

### All Slash Commands

| Command | Mod | Side |
|---------|-----|------|
| `/logger start/stop/startall/status` | emma-gameplay-logger (external repo) | Server |
| `/endinv` | emma-endinv | Server |
| `/config` | emma-endinv | Server |
| `/emma_extract` | emma-recipe-extractor | Server (op) |
| CrowdControl commands | emma-twitch (external repo) | Server (via CC framework) |
