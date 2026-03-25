# Emma Endless Inventory — 26.1 Port Notes

## Source
Forked from [kwwsyk/Endless-Inventory](https://github.com/kwwsyk/Endless-Inventory) branch `1.21.11` (v1.1.3.3).

## What Changed in This Port

### Architecture
- **Flattened** multi-loader (common + fabric + neoforge + forge) into single Fabric module
- Package renamed: `com.kwwsyk.endinv.common` / `com.kwwsyk.endinv.fabric` → `com.emma.endinv`
- Removed NeoForge, Forge, JEI integration, ClothConfig integration
- Multi-loader abstractions (`AbstractModInitializer`, `IPlatform`, `IPacketDistributor`) kept as-is since they don't hurt and avoiding a rewrite reduces risk

### Build Config
- MC `1.21.11` → `26.1-pre-2`
- Java `21` → `25`
- Fabric Loader `0.18.4` (same)
- Fabric Loom `1.15-SNAPSHOT` → `1.15.5`
- Fabric API `0.141.3+1.21.11` → `0.143.14+26.1`
- Parchment mappings removed (26.1 is unobfuscated, identity mappings)
- Mixin compatibility level `JAVA_17` → `JAVA_25`

### Removed Dependencies
- ClothConfig (`cloth-config-fabric`) — config screen, optional
- JEI (`jei-fabric-api`) — recipe transfer, optional
- Parchment (`parchment-1.21.11`) — not needed on unobfuscated MC

## Potential Compile Issues (Fix as They Appear)

### HIGH PROBABILITY
1. **`net.minecraft.util.Util`** — May have moved to `net.minecraft.Util` in 26.1 unobfuscated.
   Fix: Change import in `SourceInventory.java` and anywhere else it's used.

2. **`Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`** — The constants may have been renamed.
   Used in: `EndInvCommand.java`, `ConfigCommand.java`
   Fix: Check what the 26.1 `Commands` class actually exposes. May need `source.hasPermission(2)` instead.

3. **`SavedDataType` constructor** — The 4-arg constructor `new SavedDataType<>(name, factory, codec, dataFixType)` may have changed between 1.21.11 and 26.1.
   Used in: `EndlessInventoryData.java`
   Fix: Check the 26.1 `SavedDataType` constructor signature.

### MEDIUM PROBABILITY
4. **`FeatureFlags.DEFAULT_FLAGS`** — Used in `MenuType` constructor in `ModInit.java`.
   May be renamed or the `MenuType` constructor signature may have changed.

5. **`Item.CODEC`** / `Item.STREAM_CODEC`** — Used in codec definitions for serialization.
   Used in: `EndlessInventory.java`, `EndInvCodecStrategy.java`, `ItemKey.java`, `ItemStackLike.java`
   These codecs are core Mojang infrastructure and unlikely to change, but check.

6. **`DataComponentPatch.CODEC`** — Used in `EndlessInventory.ITEM_MAP_CODEC`.
   DataComponents were refactored in 1.21.x; the codec should be stable by 26.1.

### LOW PROBABILITY
7. **`NonNullList`** — Standard utility, used heavily. Unlikely to change.

8. **Mixin targets** — `LivingEntity.dropFromLootTable`, `AbstractContainerScreen.mouseDragged`, `AbstractContainerScreen.renderBackground`, etc. These are stable MC methods.

9. **Fabric API** — `AttachmentRegistry`, `PayloadTypeRegistry`, `ServerPlayNetworking`, `ServerWorldEvents`, `PlayerBlockBreakEvents` — all stable Fabric API.

## Build Command
```bash
cd java/emma-endinv
./gradlew.bat build
```

Output JAR: `build/libs/emma-endinv-1.2.0.jar`

## Deploy
Copy to both Emma and CameraBot mod folders (if needed):
- `%APPDATA%\PrismLauncher\instances\Emma\.minecraft\mods\`
- `%APPDATA%\PrismLauncher\instances\CameraBot\.minecraft\mods\`
