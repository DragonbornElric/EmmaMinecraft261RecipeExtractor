# Twitch Viewer Interaction: Fork Crowd Control for MC 26.1

## Context

Emma streams on a dedicated Fabric server (MC 26.1-pre2). We want Twitch viewers to interact in-game via channel points and bits. Crowd Control (`github.com/qixils/minecraft-crowdcontrol`, MPL-2.0) has ~100 built-in effects and a solid Fabric implementation — but it routes through their cloud service with a coin economy, only supports up to 1.21.5, and has no configuration GUI.

**Goal:** Fork Crowd Control, strip to Fabric-only, replace their coin/cloud layer with direct Twitch EventSub (channel points + bits), upgrade to 26.1-pre2, and build a GUI for configuring effects/prices/sessions. Deploy as a standalone server mod alongside our existing 3 mods.

---

## Phase 1: Fork & Strip Down

1. Fork `qixils/minecraft-crowdcontrol`
2. Delete non-Fabric modules: `paper/`, `neoforge/`, `oneclick/`, `website/`
3. Keep `common/` (shared effect logic) + `fabric/` + `mojmap/`
4. Remove all references to Crowd Control cloud service (`pubsub.crowdcontrol.live`)
5. Remove their coin/economy system — we'll map effects directly to Twitch events
6. Flatten build to single Fabric mod output JAR
7. Location: `EmmaMinecraft261/java/emma-twitch/`

## Phase 2: Upgrade to MC 26.1-pre2

1. Update `gradle.properties`: MC version, Fabric API, Fabric Loader, Java 25
2. Use `loom.officialMojangMappings()` (identity on 26.1, same as our other mods)
3. Fix compilation errors (expect 400-800 based on our prior migration experience)
4. Key API areas to watch: entity spawning, command registration, networking, inventory manipulation
5. Reference `memory/mc26-api-changes.md` for known breakages
6. Match our build toolchain: Gradle 9.2.1, Fabric Loom 1.15.5, Java 25

## Phase 3: Twitch EventSub Integration

Replace Crowd Control's cloud-service connector with direct Twitch EventSub:

```
Twitch EventSub WebSocket (wss://eventsub.wss.twitch.tv/ws)
    ↓ channel.points_custom_reward_redemption.add
    ↓ channel.cheer
    ↓
TwitchEventSubClient (Java, in mod)
    ├── Authenticates with User Access Token
    ├── Subscribes to redemption + cheer events
    ├── Maps reward ID / bit amount → effect ID
    ↓
Existing Crowd Control effect execution code
    ├── Executes effect on server
    ├── Broadcasts custom chat: "[Twitch] ViewerX triggered ChickenRain!"
    └── Reports success/failure (for potential refund logic)
```

- Use **Twitch4J** library or raw EventSub WebSocket client in Java
- Store OAuth token in server config file (not in mod JAR)
- Map each Twitch custom reward → a Crowd Control effect ID
- Map bit tiers → effect IDs (e.g., 100 bits = zombie spawn, 500 bits = anvil drop)

## Phase 4: Configuration GUI

Need a GUI to:
- Enable/disable individual effects
- Set channel point cost or bit threshold per effect
- Map Twitch custom rewards to effects
- Manage Twitch auth session (login, token refresh)
- View active effects / cooldown status
- Preview what viewers will see

**Approach: Web UI** served by the mod on a local port (e.g., `localhost:8780`). Accessible from any browser/device on the network. Similar to Crafty Controller's web panel.

## Phase 5: Deploy & Test

1. Build JAR, deploy to server mods directory alongside emma-pathfinder, emma-overflow, emma-gameplay-logger
2. Verify no conflicts with existing mods (standalone, no shared state)
3. Test with Twitch CLI mock events: `twitch event trigger channel.channel_points_custom_reward_redemption.add`
4. Set up real Twitch custom rewards matching configured effects
5. Live test on stream

---

## Key Decisions

- **Fabric-only** (strip Paper/NeoForge)
- **Direct Twitch EventSub** (no Crowd Control cloud service)
- **Channel points + bits** (both trigger types)
- **Default effects first** (~100 built-in, no customization yet)
- **Server mod** on dedicated Fabric server
- **Standalone** — must not break existing mods
- **MPL-2.0 license** compliant
- **GUI:** Web UI on localhost (e.g., port 8780)
- **Repo location:** `java/emma-twitch/` subfolder in EmmaMinecraft261
- **Open:** Twitch4J vs raw EventSub WebSocket client (can decide during implementation)

## Risk Assessment

- **MC 26.1 upgrade:** 1-3 weeks, largest risk — but we've done it before with our other 3 mods
- **Twitch EventSub wiring:** Straightforward, well-documented API
- **Effect compatibility:** Some effects may use deprecated MC APIs on 26.1
- **GUI:** Significant additional work on top of the core fork
