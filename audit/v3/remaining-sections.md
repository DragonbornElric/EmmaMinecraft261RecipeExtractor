# Audit V3 — Remaining Sections (Bulk)

This file covers sections where the architectural shift makes per-file comparison less meaningful. These are predominantly NOT_NEEDED (infrastructure replaced by fundamentally different architecture) or IMPLEMENTED (functionality present in different form).

---

## 01: Core (7 files) — AltoClef root

| File | Old Purpose | Emma Equivalent | Verdict |
|------|-------------|-----------------|---------|
| AltoClef.java | God-object: owns all subsystems | EmmaBridgeClient.java + EmmaBridgeMod.java | IMPLEMENTED — wiring in startBridge() |
| AltoClefCommands.java | Chat command registration | CommandRouter + 50 ICommandHandler implementations | IMPLEMENTED — more commands |
| BotBehaviour.java | Push/pop settings stack | GoapStateFlags singleton + action-level preconditions | IMPLEMENTED — different mechanism |
| Debug.java | Logging utilities | Standard SLF4J LOGGER in each class | IMPLEMENTED |
| Playground.java | Ad-hoc test utility | AgentDebugHandler + debug WebSocket commands | IMPLEMENTED |
| Settings.java | Mod settings (food thresholds, move delays, throwaway items) | BridgeConfig.java (config.json) + Python-side gamer.configure() | IMPLEMENTED — split between Java config and Python config |
| TaskCatalogue.java | Item name → collection task registry | ItemRecipeRegistry.java (3121 entries) + GoalDecomposer | IMPLEMENTED — data-driven replacement |

---

## 02: Butler (5 files) — In-game whisper commands

| File | Old Purpose | Emma Equivalent | Verdict |
|------|-------------|-----------------|---------|
| Butler.java | Processes whisper commands from authorized users | ChatCommandInterceptor (@ prefix commands) | PARTIAL — no whisper-based, uses @ prefix instead |
| ButlerConfig.java | Butler configuration | BridgeConfig.java | IMPLEMENTED |
| UserAuth.java | User authentication | Not present (single-player AI, no auth needed) | NOT_NEEDED |
| UserListFile.java | Whitelist/blacklist files | Not present | NOT_NEEDED |
| WhisperChecker.java | Whisper message parsing | ChatCommandInterceptor parses @ prefix | IMPLEMENTED — different trigger |

---

## 04: Commands (35 files)

All old AltoClef commands map to CommandRouter handlers in Emma. Key mapping:

| Old Command | Emma Handler | Verdict |
|-------------|-------------|---------|
| GotoCommand | GotoHandler | IMPLEMENTED |
| GetCommand | SetGoalsHandler (have_item goal) | IMPLEMENTED |
| InventoryCommand | InventoryHandler | IMPLEMENTED |
| StopCommand | CancelHandler | IMPLEMENTED |
| StatusCommand | StatusHandler | IMPLEMENTED |
| FoodCommand | SetGoalsHandler (stay_fed goal) | IMPLEMENTED |
| SleepCommand | SetGoalsHandler (manage_sleep goal) | IMPLEMENTED |
| DepositCommand | StoreItemsAction (bridge request) | IMPLEMENTED |
| EquipCommand | Not present | GAP |
| FollowCommand | Not present | GAP |
| HeroCommand | SetGoalsHandler (hunt_hostiles goal) | IMPLEMENTED |
| GamerCommand | SetModeHandler | IMPLEMENTED |
| MeatCommand | SetGoalsHandler (have_item cooked_beef) | IMPLEMENTED |
| TorchCommand | TorchHandler | IMPLEMENTED |
| BlockScanner/ScanCommand | ScanHandler/WorldScanHandler | IMPLEMENTED |
| Remaining 20 commands | Various or not present | MIXED |

---

## 05: Command System (8 files)

| File | Old Purpose | Emma Equivalent | Verdict |
|------|-------------|-----------------|---------|
| Arg/ArgBase/ArgParser | Typed command argument parsing | JSON params in ICommandHandler.execute(JsonObject) | IMPLEMENTED — JSON instead of string parsing |
| Command.java | Abstract command base | ICommandHandler interface | IMPLEMENTED |
| CommandException.java | Command errors | Exception handling in CommandRouter | IMPLEMENTED |
| CommandExecutor.java | Command dispatch | CommandRouter.java | IMPLEMENTED |
| GotoTarget.java | Parsed goto target | GotoHandler parses JSON directly | IMPLEMENTED |
| ItemList.java | Parsed item list | JSON arrays in handler params | IMPLEMENTED |

---

## 07: Event System (20 files)

| File | Old Purpose | Emma Equivalent | Verdict |
|------|-------------|-----------------|---------|
| EventBus.java + Subscription.java | Pub/sub event dispatcher | Fabric API callbacks + polling EventReporter | NOT_NEEDED — Fabric events replace custom bus |
| All 18 event types | Various game events | Fabric API equivalents or per-tick polling in WorldState | NOT_NEEDED — events replaced by polling |

---

## 08: Mixins (28 files)

Old had 28 mixins. Emma has 3 bridge mixins + 3 Emmatone mixins = 6 total.

| Category | Old Count | New Count | Notes |
|----------|-----------|-----------|-------|
| Bridge mixins | 28 | 3 | AbstractFurnaceScreenHandlerAccessor, DeathScreenAccessor, StatisticsListenerMixin |
| Emmatone mixins | N/A | 3 | MixinChunkArray, MixinClientChunkProvider, MixinClientPlayNetHandler |
| Total | 28 | 6 | 22 fewer mixins |

Most old mixins were for events (now Fabric API), Baritone integration (now Emmatone), or version compatibility (now unobfuscated MC 26.1).

**Verdict:** NOT_NEEDED — Fabric API + MC 26.1 unobfuscated source eliminates most mixins.

---

## 09: Multiversion (37 files)

All 37 multiversion compatibility files are NOT_NEEDED. MC 26.1 is the first unobfuscated Minecraft release. No version-specific wrappers needed.

**Verdict:** NOT_NEEDED — All 37 files obsolete.

---

## 17: tasks/slot/ (11 files)

| File | Old Purpose | Emma Equivalent | Verdict |
|------|-------------|-----------------|---------|
| ClickSlotTask | Click specific slot | Inline in actions (handleContainerInput calls) | IMPLEMENTED |
| EnsureFreeCursorSlotTask | Clear cursor | Not present as dedicated task | GAP (missing cursor cleanup) |
| EnsureFreeInventorySlotTask | Free inventory slot by discarding | Not present | GAP (no item discard) |
| EnsureFreePlayerCraftingGridTask | Clear 2x2 grid | Not present | GAP |
| MoveInaccessibleItemToInventoryTask | Move stuck items | Not present | GAP |
| MoveItemToSlot*Tasks (3 files) | Item movement utilities | Inline shift-clicks in actions | IMPLEMENTED |
| ReceiveCraftingOutputSlotTask | Extract crafted output | CraftItemAction EXTRACT phase | IMPLEMENTED |
| ThrowCursorTask | Throw cursor item | ToolEquipReflex drops cursor (slot -999) | IMPLEMENTED |
| WithdrawFromOverflowTask | EndinvBridge withdrawal | EndinvBridge.extractToSlot() | IMPLEMENTED |

---

## 19: tasks/squashed/ (4 files)

| File | Old Purpose | Emma Equivalent | Verdict |
|------|-------------|-----------------|---------|
| CataloguedResourceTask | Batch resource collection | GoalDecomposer creates multiple sub-goals | IMPLEMENTED |
| CraftSquasher | Merge multiple craft tasks | CraftItemAction chains crafts (findCraftableInChain) | IMPLEMENTED |
| SmithingSquasher | Merge smithing tasks | Not present (no smithing action) | GAP |
| TypeSquasher | Generic task merging | Not needed (GOAP scores independently) | NOT_NEEDED |

---

## 20: Task System (7 files)

| File | Old Purpose | Emma Equivalent | Verdict |
|------|-------------|-----------------|---------|
| Task.java | Abstract task base | GoapAction.java | NOT_NEEDED — different architecture |
| TaskChain.java | Prioritized task container | ReflexLayer.java | NOT_NEEDED |
| TaskRunner.java | Ticks chains, selects active | GoapTicker.java | NOT_NEEDED |
| ITaskCanForce/OverridesGrounded/RequiresGrounded/UsesCraftingGrid | Task interfaces | Not needed (flat GOAP) | NOT_NEEDED |

---

## 22-30: UI, Utils, Serialization, Slots, Time, Skinchanger, Baritone

These are overwhelmingly NOT_NEEDED or IMPLEMENTED inline:

- **UI (7 files):** AltoClefTickChart, CommandStatusOverlay, etc. → No in-game overlay (debug via WebSocket). NOT_NEEDED.
- **Util (10 files):** CraftingRecipe, ItemTarget, SmeltTarget, etc. → Replaced by ItemRecipeEntry, GoalSet goals. NOT_NEEDED.
- **Util/helpers (16 files):** Various helper utilities → Distributed across Emma actions and utility classes. Most IMPLEMENTED inline or NOT_NEEDED.
  - **ProjectileHelper** (trajectory calc): GAP — not present in Emma
  - **ItemHelper** (item constants): IMPLEMENTED in ItemClassifier + TagGroups
  - **StorageHelper**: PARTIAL — inline in actions but missing centralized helpers
  - **WorldHelper**: IMPLEMENTED in WorldState + GoapTicker
  - **CraftingHelper**: IMPLEMENTED in GoalDecomposer + CraftItemAction
- **Util/baritone (9 files):** Baritone goal types → Emmatone equivalents. IMPLEMENTED.
- **Util/progresscheck (5 files):** Movement/distance progress checkers → UnstuckAction replaces. PARTIAL.
- **Util/serialization (12 files):** JSON serializers for BlockPos, Vec3d, etc. → JSON handled by Gson in WebSocket protocol. NOT_NEEDED.
- **Util/slots (11 files):** Slot index constants → Hardcoded in actions. IMPLEMENTED inline.
- **Util/time (4 files):** Timer classes → System.currentTimeMillis() or tick counters. NOT_NEEDED.
- **Skinchanger (2 files):** Skin changing → Not present. NOT_NEEDED.
- **AltoClefSettings (1 file):** Baritone settings → Emmatone settings in BridgeConfig. IMPLEMENTED.
