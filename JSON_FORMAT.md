# Emma Recipe Extractor JSON Format

This document describes the JSON written by `/emma_extract`.

The extractor output is a wrapper object with metadata plus a heterogeneous `entries` array.

## Top-Level Shape

```json
{
  "extractionMeta": {
    "mcVersion": "26.1",
    "timestamp": "2026-03-24T18:56:19.632053662Z",
    "extractorVersion": 3,
    "counts": {
      "crafting": 1070,
      "smelting": 116,
      "smithingTransform": 12,
      "smithingTrim": 18,
      "stonecutting": 275,
      "blockDrops": 1017,
      "mobDrops": 142,
      "brewing": 66,
      "enchantments": 43,
      "bannerPatterns": 43,
      "itemProperties": 319,
      "total": 3121
    }
  },
  "entries": [
    {}
  ]
}
```

## Top-Level Fields

| Field | Type | Meaning |
|------|------|---------|
| `extractionMeta.mcVersion` | string | Minecraft version reported by the running server |
| `extractionMeta.timestamp` | string | ISO-8601 extraction time |
| `extractionMeta.extractorVersion` | integer | Format version emitted by the extractor |
| `extractionMeta.counts` | object | Category counts plus `total` |
| `entries` | array | List of obtainability or metadata records |

## Common Entry Fields

Most entries contain these fields:

| Field | Type | Meaning |
|------|------|---------|
| `itemId` | string | Item or registry path, usually without namespace |
| `obtainMethod` | string | Entry family and acquisition path |
| `itemMatches` | array of string | Namespaced item identifiers, usually `minecraft:<itemId>` |

Notes:

- `itemId` is omitted for the special `BREW_CONTAINERS` entry because that record describes valid brewing containers globally rather than a single target item.
- `itemMatches` is intended as the easiest machine-readable exact-match field for downstream tools.
- Some fields are present only for specific `obtainMethod` values.

## Obtain Methods

### Crafting

Methods:

- `CRAFT_SHAPED_2x2`
- `CRAFT_SHAPED_3x3`
- `CRAFT_SHAPELESS`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `recipeId` | string | Recipe registry ID |
| `craftGrid` | array of array of string | Ingredient alternatives per slot |
| `craftYield` | integer | Output count |
| `group` | string | Optional recipe group |
| `craftingCategory` | string | Mojang crafting category |
| `shapedWidth` | integer | Present for shaped recipes |
| `shapedHeight` | integer | Present for shaped recipes |

Notes:

- On `extractorVersion >= 3`, shaped recipes preserve the full trimmed bounding box and use `null` entries for empty slots inside that box. This is required if you want to reconstruct exact layouts such as pickaxes, boats, axes, and doors.
- Older `extractorVersion = 2` files compacted shaped recipes and therefore lost some empty-slot positions. Use version 3 output when grid positions matter.

### Smelting and Cooking

Methods:

- `SMELT_FURNACE`
- `SMELT_BLAST`
- `SMELT_SMOKER`
- `SMELT_CAMPFIRE`
- `SMELT`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `recipeId` | string | Recipe registry ID |
| `smeltFrom` | array of string | Valid input items |
| `group` | string | Optional recipe group |
| `cookingCategory` | string | Mojang cooking category |
| `experience` | number | XP granted by the recipe |
| `cookingTime` | integer | Time in ticks |

### Smithing

Methods:

- `SMITH`
- `SMITH_TRIM`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `recipeId` | string | Recipe registry ID |
| `smithTemplate` | array of string | Optional smithing template inputs |
| `smithBase` | array of string | Base items |
| `smithMaterial` | array of string | Material items |
| `group` | string | Optional recipe group |

Notes:

- `SMITH_TRIM` uses the first base item as `itemId` because trimming does not create a new registry item.

### Stonecutting

Method:

- `STONECUTTER`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `recipeId` | string | Recipe registry ID |
| `stonecutterFrom` | array of string | Valid stonecutter inputs |
| `stonecutterYield` | integer | Output count |
| `group` | string | Optional recipe group |
| `smeltFrom` | array of string | Backward-compat alias for `stonecutterFrom` |
| `craftYield` | integer | Backward-compat alias for `stonecutterYield` |

### Mining

Method:

- `MINE`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `mineBlocks` | array of object | Blocks that can produce the item |
| `miningRequirement` | string | Best required mining tier when known |
| `dimension` | string | Optional coarse dimension hint |

`mineBlocks[*]` fields:

| Field | Type | Meaning |
|------|------|---------|
| `block` | string | Source block ID |
| `toolType` | string | Recommended tool family |
| `requirement` | string | Optional tier requirement |
| `conditions` | array | Optional loot-table conditions |
| `functions` | array | Optional loot-table functions |
| `weight` | integer | Loot table weight |
| `quality` | integer | Loot table quality |
| `rolls` | object | Optional loot roll description |
| `bonusRolls` | object | Optional bonus roll description |

### Mob Drops

Method:

- `MOB_DROP`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `mobClass` | string | Entity registry path |
| `lootConditions` | array | Optional loot-table conditions |
| `lootFunctions` | array | Optional loot-table functions |
| `spawnDimensions` | array of string | Dimensions where the mob can naturally spawn |

### Brewing

Methods:

- `BREW_POTION`
- `BREW_CONTAINER`
- `BREW_CONTAINERS`

Fields for `BREW_POTION` and `BREW_CONTAINER`:

| Field | Type | Meaning |
|------|------|---------|
| `brewFrom` | string | Input potion or item |
| `brewIngredient` | array of string | Valid brewing ingredients |
| `brewTo` | string | Output potion or item |

Fields for `BREW_CONTAINERS`:

| Field | Type | Meaning |
|------|------|---------|
| `containers` | array of string | Items accepted as brewing containers |

### Enchantments

Method:

- `ENCHANTMENT`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `enchantmentId` | string | Enchantment registry path |
| `weight` | integer | Selection weight |
| `anvilCost` | integer | Anvil cost multiplier |
| `minLevel` | integer | Minimum enchantment level |
| `maxLevel` | integer | Maximum enchantment level |
| `costPerLevel` | array of object | Min and max enchanting costs per level |
| `supportedItems` | array of string | Items that can support the enchantment |
| `primaryItems` | array of string | Items that can receive it from enchanting when present |
| `slots` | array of string | Equipment slot groups |
| `exclusiveWith` | array of string | Incompatible enchantments when present |
| `description` | string | Human-readable localized description |

### Banner Patterns

Method:

- `BANNER_PATTERN`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `patternId` | string | Banner pattern registry path |
| `assetId` | string | Asset identifier |
| `translationKey` | string | Translation key |

### Item Properties

Method:

- `ITEM_PROPERTIES`

Fields:

| Field | Type | Meaning |
|------|------|---------|
| `properties.repairsWith` | array of string | Repair materials when present |
| `properties.enchantability` | integer | Enchantability score when present |
| `properties.equippable` | object | Equippable data when present |
| `properties.maxStackSize` | integer | Non-default stack size when present |
| `properties.maxDurability` | integer | Durability when present |

`properties.equippable` fields:

| Field | Type | Meaning |
|------|------|---------|
| `slot` | string | Equipment slot name |
| `dispensable` | boolean | Whether dispensers can equip it |
| `swappable` | boolean | Whether it can be swapped while worn |
| `damageOnHurt` | boolean | Whether the item takes damage on hurt |
| `equipOnInteract` | boolean | Whether right-click can equip it |
| `assetId` | string | Optional asset key |
| `cameraOverlay` | string | Optional overlay asset |

## Open-Ended Nested Structures

Loot conditions, loot functions, roll objects, and bonus-roll objects are intentionally open-ended.

The extractor captures known subtypes with structured fields and falls back to objects like this when a subtype is unknown:

```json
{
  "type": "unknown",
  "class": "SomeMinecraftInternalClass"
}
```

Downstream tools should treat these nested objects as extensible and avoid assuming a closed world.

## Compatibility Guidance

- Prefer `extractorVersion` plus `obtainMethod` as your primary branching inputs.
- Treat missing optional fields as normal.
- Preserve unknown nested objects instead of discarding them.
- If you build GOAP, wiki, or mod-pack tooling on top of this file, code against entry families rather than a single flat schema.