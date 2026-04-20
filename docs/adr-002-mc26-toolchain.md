# ADR-002: MC 26.1 Toolchain Requirements

## Status

Accepted

## Date

2026-03-16

## Note

This ADR records the MC 26.1 migration decision made while
`emma-gameplay-logger` was still part of this monorepo. The logger now lives
in a separate repository: `https://github.com/DragonbornElric/EmmaMinecraft261Logger`

## Context

MC 26.1 Pre-Release 2 (2026-03-13) ships with unobfuscated source — Minecraft
classes use real method and field names with no obfuscation step. The Fabric blog
(2026-03-14) confirmed three breaking toolchain changes:

1. **Yarn mappings discontinued.** With Mojang shipping unobfuscated source, the
   community mapping project has no purpose. Fabric will not publish Yarn builds
   for MC 26.x+.
2. **Loom 1.15 required.** First Loom version to support the new unobfuscated
   artifact format. Older Loom cannot resolve MC 26.x dependencies.
3. **Java 25 minimum.** MC 26.1 targets Java 25; Fabric Loader enforces this at
   startup.

Our project currently targets MC 1.21.8 with:
- Java 21 (source/target)
- Fabric Loom 1.14-SNAPSHOT
- Mixed mapping strategy: emma-pathfinder uses Mojang official mappings,
  emma-overflow and the then-local emma-gameplay-logger use Yarn mappings
- Fabric Loader 0.18.4, Fabric API 0.136.1+1.21.8

## Decision

### Toolchain upgrades

| Component | Old | New | Notes |
|-----------|-----|-----|-------|
| Java | 21 | 25 | Install JDK 25; update Gradle toolchain |
| Fabric Loom | 1.14-SNAPSHOT | 1.15 | Required for MC 26.x |
| Fabric Loader | 0.18.4 | ≥0.18.4 (latest) | Query meta.fabricmc.net for 26.1 |
| Fabric API | 0.136.1+1.21.8 | TBD+26.1 | Query fabricmc.net/develop |
| Minecraft | 1.21.8 | 26.1 | Major version jump |

### Mapping strategy

- **emma-pathfinder:** Keep `loom.officialMojangMappings()`. On MC 26.1 this
  resolves to identity (source is already unobfuscated). No code changes needed
  for the mapping layer itself.
- **emma-overflow & the then-local emma-gameplay-logger:** Switch from Yarn to
  `loom.officialMojangMappings()`. This requires rewriting all MC API imports
  and method names from Yarn conventions to Mojang conventions. The Mojang names
  are now the actual source names.
- **Plugin change:** Both overflow and logger switch from `fabric-loom-remap`
  (Yarn remapping plugin) to standard `fabric-loom`.

### Mixin compatibility

All mixin configs update `compatibilityLevel` from `JAVA_21` to `JAVA_25`.

## Consequences

- **Yarn→Mojang migration:** emma-overflow (4 source files, ~42 MC API usages in
  heaviest file) and the then-local emma-gameplay-logger (15 source files,
  5 mixins) need full import/method renaming.
- **Unified naming:** After migration, all three mods use identical MC API names.
  No more Yarn/Mojang translation when reading across modules.
- **Emmatone mixins:** 20 mixins target precise bytecode positions. MC 26.1's
  class layout will differ from 1.21.8 — each mixin injection point must be
  re-verified against the unobfuscated source.
- **Java 25 features:** New language features (value types, pattern matching
  enhancements) become available but are not required.
- **nether-pathfinder JNI:** Must verify Java 25 compatibility for the native
  elytra pathfinding library.
