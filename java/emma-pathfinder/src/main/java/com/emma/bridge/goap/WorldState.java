package com.emma.bridge.goap;

import com.emma.bridge.util.InventoryScanner;
import com.emma.bridge.util.ItemClassifier;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.LightLayer;


import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tick snapshot of the game world relevant to GOAP decision-making.
 * Updated by polling (not events) — called once per tick by GoapTicker.
 *
 * Three-scope inventory:
 *   playerInventory  — items in player hotbar + main inv
 *   overflowInventory — items in overflow chest (Phase 58c)
 *   knownStorage     — items in tracked containers (Phase 58c DB)
 *
 * Equipped armor per slot with enchantments + durability for ArmorScorer.
 * Available armor in inventory for upgrade decisions.
 * Nearby blocks for opportunistic resource pickup.
 *
 * Fields are public for fast read access from action scoring functions.
 */
public class WorldState {

    // ── Player vitals ────────────────────────────────────────────
    public float health;
    public float maxHealth;
    public int hunger;
    public float saturation;
    public boolean onFire;
    public boolean inWater;
    public int airSupply;
    public int maxAir;

    // ── Position ─────────────────────────────────────────────────
    public double posX, posY, posZ;
    public String dimension = "minecraft:overworld";

    // ── Equipped armor per slot (with enchantments + durability) ─
    public final Map<EquipmentSlot, ArmorState> equippedArmor = new EnumMap<>(EquipmentSlot.class);
    public int armorValue;  // total defense value (0-20)

    // ── Available armor in inventory (candidates for EquipBestArmor) ─
    public final List<ArmorCandidate> availableArmor = new ArrayList<>();

    // ── Inventory — three scopes ─────────────────────────────────
    public final Map<String, Integer> playerInventory = new HashMap<>();
    public final Map<String, Integer> overflowInventory = new HashMap<>();
    public final Map<String, Integer> knownStorage = new HashMap<>();
    public int freeSlots;

    // ── Nearby blocks (goal-relevant types from BlockScanner) ────
    public final Map<String, List<BlockPos>> nearbyBlocks = new HashMap<>();

    // ── Unreachable blocks (shared from StrategyKnowledge via UtilityScorer) ──
    /** Positions that timed out during navigation. Set by UtilityScorer before scoring. */
    public Map<BlockPos, Long> unreachableBlocks = java.util.Collections.emptyMap();

    /** Check if a block position is currently marked as unreachable. */
    public boolean isUnreachable(BlockPos pos) {
        return unreachableBlocks.containsKey(pos);
    }

    // ── Threats (nearby hostiles, sorted by distance) ────────────
    public final List<ThreatInfo> threats = new ArrayList<>();
    /** Distance to nearest fusing creeper, or Float.MAX_VALUE if none. Fuse threshold > 0.25. */
    public float fusingCreeperDistance = Float.MAX_VALUE;

    // ── Environment ──────────────────────────────────────────────
    public int lightLevel;
    public int skyLight;
    public long timeOfDay;
    public boolean isThundering;
    public boolean isRaining;

    // ── Hazard states (for EnvironmentalHazardAction + reflexes) ──
    public boolean inLava;
    public boolean inPowderSnow;
    public boolean touchingDragonBreath;  // populated externally

    // ── Equipment (for CombatHelper scoring + ShieldBlockReflex) ──
    public boolean hasShield;
    public double bestWeaponDamage;

    // ── Movement (for MLGBucketReflex) ────────────────────────────
    public double velocityY;

    // ── Projectiles (for ShieldBlockReflex + ProjectileDodgeAction) ──
    public final List<ProjectileInfo> incomingProjectiles = new ArrayList<>();

    // ── Status effects (for combat/survival scoring) ──────────────
    public final Map<String, StatusEffectData> activeEffects = new HashMap<>();

    // ── Food tracking (for CollectFoodAction) ─────────────────────
    public int foodItemCount;  // total food items in player inventory

    // ── Death context (from DeathContext singleton) ──────────────
    public DeathContext.DeathSnapshot lastDeath;

    // ── Portal access (populated from PortalRegistry) ──────────
    public boolean hasNetherPortal;
    public boolean hasEndPortal;

    // ── Stronghold (populated by LocateStrongholdAction) ───────
    public boolean strongholdKnown;
    public int strongholdX, strongholdZ;

    // ── End dimension state (populated when in the_end) ────────
    public boolean dragonAlive;
    public int endCrystalCount;
    public double dragonHealth;
    public double dragonPosX, dragonPosY, dragonPosZ;
    public String dragonPhase = "unknown";

    // ── Tick counter ─────────────────────────────────────────────
    public long tick;

    /**
     * Update all fields from current game state. Called once per tick.
     */
    public void update(LocalPlayer player, ClientLevel world) {
        // Vitals
        health = player.getHealth();
        maxHealth = player.getMaxHealth();
        hunger = player.getFoodData().getFoodLevel();
        saturation = player.getFoodData().getSaturationLevel();
        armorValue = player.getArmorValue();
        onFire = player.isOnFire();
        inWater = player.isInWater();
        airSupply = player.getAirSupply();
        maxAir = player.getMaxAirSupply();

        // Position
        posX = player.getX();
        posY = player.getY();
        posZ = player.getZ();
        dimension = player.level().dimension().identifier().toString();

        // Environment
        BlockPos blockPos = player.blockPosition();
        lightLevel = world.getMaxLocalRawBrightness(blockPos);  // combined sky + block light
        skyLight = world.getBrightness(LightLayer.SKY, blockPos);
        timeOfDay = world.getDefaultClockTime() % 24000;
        isThundering = world.isThundering();
        isRaining = world.isRaining();

        // Equipped armor per slot
        updateEquippedArmor(player);

        // Player inventory (hotbar + main)
        updatePlayerInventory(player);

        // overflowInventory + knownStorage: populated externally via setters
        // (wired from Phase 58c StorageHandler when available)

        // nearbyBlocks: populated externally via setNearbyBlocks
        // (wired from BlockScanner when goal-relevant blocks are configured)

        // Hazard states
        inLava = player.isInLava();
        inPowderSnow = player.isInPowderSnow;
        velocityY = player.getDeltaMovement().y;

        // Equipment
        hasShield = false;
        bestWeaponDamage = 1.0;
        for (var ss : InventoryScanner.findAll(player.getInventory(), stack -> true)) {
            String id = ItemClassifier.itemId(ss.stack());
            if (id.equals("minecraft:shield")) hasShield = true;
            double dmg = com.emma.bridge.util.CombatHelper.getWeaponDamage(ss.stack());
            if (dmg > bestWeaponDamage) bestWeaponDamage = dmg;
        }
        // Check offhand for shield
        ItemStack offhandStack = player.getInventory().getItem(40);
        if (!offhandStack.isEmpty()) {
            String offId = BuiltInRegistries.ITEM.getKey(offhandStack.getItem()).toString();
            if (offId.equals("minecraft:shield")) hasShield = true;
        }

        // Food count
        foodItemCount = 0;
        for (var entry : playerInventory.entrySet()) {
            if (isFood(entry.getKey())) foodItemCount += entry.getValue();
        }

        // Incoming projectiles
        updateProjectiles(player, world);

        // Status effects
        updateStatusEffects(player);

        // Threats
        updateThreats(player, world);

        // Death context (for DeathRecoveryAction scoring)
        lastDeath = DeathContext.getInstance().getLatestDeath();

        // End dimension state (dragon + crystals)
        updateEndState(player, world);

        tick++;
    }

    private void updateEquippedArmor(LocalPlayer player) {
        equippedArmor.clear();
        availableArmor.clear();

        // Read equipped armor — MC 1.21.8: getArmorStack() removed, use getEquippedStack()
        EquipmentSlot[] armorSlots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        for (EquipmentSlot slot : armorSlots) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                equippedArmor.put(slot, ArmorState.fromStack(stack, slot));
            }
        }

        // Scan player inventory for armor candidates (upgrade opportunities)
        for (var ss : InventoryScanner.findAll(player.getInventory(), WorldState::isArmorItem)) {
            availableArmor.add(ArmorCandidate.fromStack(ss.stack(), ss.slot()));
        }
    }

    private void updatePlayerInventory(LocalPlayer player) {
        playerInventory.clear();
        var inv = player.getInventory();
        freeSlots = InventoryScanner.emptySlots(inv);
        for (var ss : InventoryScanner.findAll(inv, stack -> true)) {
            String id = ItemClassifier.itemId(ss.stack());
            playerInventory.merge(id, ss.stack().getCount(), Integer::sum);
        }
        // Offhand
        ItemStack offhand = inv.getItem(40);
        if (!offhand.isEmpty()) {
            String id = BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString();
            playerInventory.merge(id, offhand.getCount(), Integer::sum);
        }
    }

    private void updateProjectiles(LocalPlayer player, ClientLevel world) {
        incomingProjectiles.clear();
        AABB scanBox = player.getBoundingBox().inflate(16);
        for (Entity entity : world.getEntities(player, scanBox)) {
            if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                double dist = player.distanceTo(projectile);
                // Only track projectiles within 12 blocks and moving toward player
                if (dist < 12.0) {
                    net.minecraft.world.phys.Vec3 velocity = projectile.getDeltaMovement();
                    net.minecraft.world.phys.Vec3 toPlayer = player.position().subtract(projectile.position()).normalize();
                    double dot = velocity.normalize().dot(toPlayer);
                    if (dot > 0.3) { // moving generally toward player
                        incomingProjectiles.add(new ProjectileInfo(
                                BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString(),
                                (float) dist,
                                projectile.getX(), projectile.getY(), projectile.getZ(),
                                velocity.x, velocity.y, velocity.z
                        ));
                    }
                }
            }
        }
        incomingProjectiles.sort((a, b) -> Float.compare(a.distance, b.distance));
    }

    private void updateThreats(LocalPlayer player, ClientLevel world) {
        threats.clear();
        fusingCreeperDistance = Float.MAX_VALUE;
        AABB scanBox = player.getBoundingBox().inflate(16);
        for (Entity entity : world.getEntities(player, scanBox)) {
            if (entity instanceof Monster hostile) {
                // Skip neutral endermen — they're not a threat unless provoked
                if (hostile instanceof EnderMan enderMan && !enderMan.isCreepy()) continue;
                float dist = player.distanceTo(hostile);
                threats.add(new ThreatInfo(
                        BuiltInRegistries.ENTITY_TYPE.getKey(hostile.getType()).toString(),
                        hostile.getHealth(),
                        dist,
                        hostile.getX(), hostile.getY(), hostile.getZ()
                ));
                // Track fusing creepers for flee urgency
                if (hostile instanceof Creeper creeper && creeper.getSwelling(1.0f) > 0.25f) {
                    fusingCreeperDistance = Math.min(fusingCreeperDistance, dist);
                }
            }
        }
        threats.sort((a, b) -> Float.compare(a.distance, b.distance));
    }

    private void updateStatusEffects(LocalPlayer player) {
        activeEffects.clear();
        for (var entry : player.getActiveEffectsMap().entrySet()) {
            MobEffectInstance effect = entry.getValue();
            String id = BuiltInRegistries.MOB_EFFECT.getKey(entry.getKey().value()).toString();
            activeEffects.put(id, new StatusEffectData(
                    id,
                    effect.getDuration(),
                    effect.getAmplifier(),
                    effect.isAmbient()
            ));
        }
    }

    public void updateEndState(LocalPlayer player, ClientLevel world) {
        dragonAlive = false;
        endCrystalCount = 0;
        dragonHealth = 0;
        dragonPhase = "unknown";

        if (!dimension.contains("the_end")) return;

        AABB scanBox = new AABB(-200, 0, -200, 200, 256, 200); // End island area
        for (Entity entity : world.getEntities(player, scanBox)) {
            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
                dragonAlive = true;
                dragonHealth = dragon.getHealth();
                dragonPosX = dragon.getX();
                dragonPosY = dragon.getY();
                dragonPosZ = dragon.getZ();
                // Phase detection from dragon phase manager
                var phaseInstance = dragon.getPhaseManager().getCurrentPhase();
                dragonPhase = phaseInstance != null ? phaseInstance.getPhase().toString() : "unknown";
            }
            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) {
                endCrystalCount++;
            }
        }
    }

    private static boolean isArmorItem(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.contains("helmet") || id.contains("chestplate")
                || id.contains("leggings") || id.contains("boots");
    }

    // ── External data setters (wired from StorageHandler / BlockScanner) ──

    /** Set overflow inventory counts (from Phase 58c overflow chest). */
    public void setOverflowInventory(Map<String, Integer> counts) {
        overflowInventory.clear();
        overflowInventory.putAll(counts);
    }

    /** Set known storage counts (from Phase 58c container DB). */
    public void setKnownStorage(Map<String, Integer> counts) {
        knownStorage.clear();
        knownStorage.putAll(counts);
    }

    /** Set nearby blocks (from BlockScanner for goal-relevant block types). */
    public void setNearbyBlocks(Map<String, List<BlockPos>> blocks) {
        nearbyBlocks.clear();
        nearbyBlocks.putAll(blocks);
    }

    // ── Convenience methods ──────────────────────────────────────

    /**
     * Check if player has at least `count` of item across all three scopes.
     */
    public boolean hasItem(String itemId, int count) {
        return totalItemCount(itemId) >= count;
    }

    /**
     * Check if player has item in personal inventory only.
     */
    public boolean hasItemInInventory(String itemId, int count) {
        return playerInventory.getOrDefault(itemId, 0) >= count;
    }

    /**
     * Total count of an item across all three scopes.
     */
    public int totalItemCount(String itemId) {
        return playerInventory.getOrDefault(itemId, 0)
                + overflowInventory.getOrDefault(itemId, 0)
                + knownStorage.getOrDefault(itemId, 0);
    }

    /**
     * Check if the player has any food item in personal inventory.
     */
    public boolean hasFood() {
        for (var entry : playerInventory.entrySet()) {
            if (isFood(entry.getKey()) && entry.getValue() > 0) return true;
        }
        return false;
    }

    /** Check if player has a specific status effect. */
    public boolean hasEffect(String effectId) {
        return activeEffects.containsKey(effectId);
    }

    /** Check if player has any harmful effect that degrades combat/survival. */
    public boolean hasHarmfulEffect() {
        for (String id : activeEffects.keySet()) {
            if (id.contains("wither") || id.contains("poison")
                    || id.contains("weakness") || id.contains("blindness")
                    || id.contains("slowness") || id.contains("mining_fatigue")
                    || id.contains("nausea") || id.contains("hunger")) {
                return true;
            }
        }
        return false;
    }

    /** Check if player has any damage-over-time effect (wither, poison). */
    public boolean hasDamageOverTimeEffect() {
        return hasEffect("minecraft:wither") || hasEffect("minecraft:poison");
    }

    /** Get amplifier of a specific effect, or -1 if not active. */
    public int getEffectAmplifier(String effectId) {
        StatusEffectData data = activeEffects.get(effectId);
        return data != null ? data.amplifier : -1;
    }

    private static boolean isFood(String itemId) {
        return com.emma.bridge.util.ItemClassifier.isFood(itemId);
    }

    /**
     * Build a diff between this state and a previous state.
     * Returns only fields that changed.
     */
    public JsonObject diff(WorldState prev) {
        JsonObject d = new JsonObject();
        if (prev == null) return d;

        if (health != prev.health)
            d.addProperty("health", prev.health + " -> " + health);
        if (hunger != prev.hunger)
            d.addProperty("hunger", prev.hunger + " -> " + hunger);
        if (armorValue != prev.armorValue)
            d.addProperty("armor", prev.armorValue + " -> " + armorValue);
        if (onFire != prev.onFire)
            d.addProperty("on_fire", prev.onFire + " -> " + onFire);
        if (freeSlots != prev.freeSlots)
            d.addProperty("free_slots", prev.freeSlots + " -> " + freeSlots);
        if (threats.size() != prev.threats.size())
            d.addProperty("threat_count", prev.threats.size() + " -> " + threats.size());
        if (activeEffects.size() != prev.activeEffects.size())
            d.addProperty("active_effects", prev.activeEffects.size() + " -> " + activeEffects.size());

        return d;
    }

    /**
     * Serialize to JSON for debug output.
     */
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("health", health);
        j.addProperty("max_health", maxHealth);
        j.addProperty("hunger", hunger);
        j.addProperty("armor", armorValue);
        j.addProperty("on_fire", onFire);
        j.addProperty("in_water", inWater);
        j.addProperty("x", (int) posX);
        j.addProperty("y", (int) posY);
        j.addProperty("z", (int) posZ);
        j.addProperty("dimension", dimension);
        j.addProperty("light_level", lightLevel);
        j.addProperty("time_of_day", timeOfDay);
        j.addProperty("free_slots", freeSlots);
        j.addProperty("threat_count", threats.size());
        j.addProperty("equipped_armor_slots", equippedArmor.size());
        j.addProperty("available_armor", availableArmor.size());
        j.addProperty("nearby_block_types", nearbyBlocks.size());
        j.addProperty("active_effects", activeEffects.size());
        j.addProperty("tick", tick);
        return j;
    }

    /**
     * Create a shallow copy for diff tracking.
     */
    public WorldState snapshot() {
        WorldState copy = new WorldState();
        copy.health = health;
        copy.maxHealth = maxHealth;
        copy.hunger = hunger;
        copy.saturation = saturation;
        copy.armorValue = armorValue;
        copy.onFire = onFire;
        copy.inWater = inWater;
        copy.airSupply = airSupply;
        copy.maxAir = maxAir;
        copy.freeSlots = freeSlots;
        copy.posX = posX;
        copy.posY = posY;
        copy.posZ = posZ;
        copy.dimension = dimension;
        copy.lightLevel = lightLevel;
        copy.timeOfDay = timeOfDay;
        copy.inLava = inLava;
        copy.inPowderSnow = inPowderSnow;
        copy.touchingDragonBreath = touchingDragonBreath;
        copy.hasShield = hasShield;
        copy.bestWeaponDamage = bestWeaponDamage;
        copy.velocityY = velocityY;
        copy.foodItemCount = foodItemCount;
        copy.lastDeath = lastDeath;
        copy.hasNetherPortal = hasNetherPortal;
        copy.hasEndPortal = hasEndPortal;
        copy.strongholdKnown = strongholdKnown;
        copy.strongholdX = strongholdX;
        copy.strongholdZ = strongholdZ;
        copy.dragonAlive = dragonAlive;
        copy.endCrystalCount = endCrystalCount;
        copy.dragonHealth = dragonHealth;
        copy.dragonPosX = dragonPosX;
        copy.dragonPosY = dragonPosY;
        copy.dragonPosZ = dragonPosZ;
        copy.dragonPhase = dragonPhase;
        copy.tick = tick;
        copy.threats.addAll(threats);
        copy.incomingProjectiles.addAll(incomingProjectiles);
        copy.activeEffects.putAll(activeEffects);
        copy.playerInventory.putAll(playerInventory);
        copy.overflowInventory.putAll(overflowInventory);
        copy.knownStorage.putAll(knownStorage);
        copy.equippedArmor.putAll(equippedArmor);
        copy.availableArmor.addAll(availableArmor);
        for (var entry : nearbyBlocks.entrySet()) {
            copy.nearbyBlocks.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    // ── Inner types ──────────────────────────────────────────────

    /**
     * Armor piece state: item ID, enchantments, durability, curse status.
     */
    public static class ArmorState {
        public final String item;
        public final EquipmentSlot slot;
        public final Map<String, Integer> enchantments;
        public final float durabilityPercent;
        public final boolean hasCurseOfBinding;

        public ArmorState(String item, EquipmentSlot slot, Map<String, Integer> enchantments,
                          float durabilityPercent, boolean hasCurseOfBinding) {
            this.item = item;
            this.slot = slot;
            this.enchantments = enchantments;
            this.durabilityPercent = durabilityPercent;
            this.hasCurseOfBinding = hasCurseOfBinding;
        }

        /**
         * Build ArmorState from an equipped ItemStack.
         * MC 1.21.8: enchantments via DataComponentTypes, binding via EnchantmentHelper.
         */
        public static ArmorState fromStack(ItemStack stack) {
            return fromStack(stack, inferSlot(stack));
        }

        public static ArmorState fromStack(ItemStack stack, EquipmentSlot slot) {
            String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Map<String, Integer> enchants = readEnchantments(stack);
            float durability = stack.getMaxDamage() > 0
                    ? 1.0f - ((float) stack.getDamageValue() / stack.getMaxDamage())
                    : 1.0f;
            boolean binding = EnchantmentHelper.has(stack,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
            return new ArmorState(item, slot, enchants, durability, binding);
        }

        /** Infer equipment slot from item ID (fallback when slot not known). */
        private static EquipmentSlot inferSlot(ItemStack stack) {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (id.contains("helmet") || id.contains("cap")) return EquipmentSlot.HEAD;
            if (id.contains("chestplate") || id.contains("tunic")) return EquipmentSlot.CHEST;
            if (id.contains("leggings") || id.contains("pants")) return EquipmentSlot.LEGS;
            if (id.contains("boots")) return EquipmentSlot.FEET;
            return EquipmentSlot.HEAD; // fallback
        }

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("item", item);
            j.addProperty("slot", slot != null ? slot.getName() : "unknown");
            j.addProperty("durability_pct", durabilityPercent);
            j.addProperty("curse_of_binding", hasCurseOfBinding);
            j.addProperty("enchantment_count", enchantments.size());
            JsonObject enc = new JsonObject();
            for (var e : enchantments.entrySet()) {
                enc.addProperty(e.getKey(), e.getValue());
            }
            j.add("enchantments", enc);
            return j;
        }
    }

    /**
     * Armor candidate in inventory: same as ArmorState but tracks inventory slot
     * for the equip action.
     */
    public static class ArmorCandidate extends ArmorState {
        public final int inventorySlot;

        public ArmorCandidate(String item, EquipmentSlot equipSlot, Map<String, Integer> enchantments,
                              float durabilityPercent, boolean hasCurseOfBinding, int inventorySlot) {
            super(item, equipSlot, enchantments, durabilityPercent, hasCurseOfBinding);
            this.inventorySlot = inventorySlot;
        }

        public static ArmorCandidate fromStack(ItemStack stack, int invSlot) {
            ArmorState base = ArmorState.fromStack(stack);
            return new ArmorCandidate(base.item, base.slot, base.enchantments,
                    base.durabilityPercent, base.hasCurseOfBinding, invSlot);
        }
    }

    /**
     * Read all enchantments from an ItemStack as name → level map.
     * MC 1.21.8: uses ItemEnchantments via DataComponents.
     */
    private static Map<String, Integer> readEnchantments(ItemStack stack) {
        Map<String, Integer> result = new HashMap<>();
        if (!stack.isEnchanted()) return result;
        try {
            ItemEnchantments enchants = stack.getOrDefault(
                    DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            for (var entry : enchants.entrySet()) {
                Holder<Enchantment> enchantment = entry.getKey();
                int level = entry.getIntValue();
                String name = enchantment.unwrapKey().map(key -> key.identifier().getPath())
                        .orElse("unknown");
                result.put(name, level);
            }
        } catch (Exception e) {
            // Enchantment API mismatch — will be refined in Stage 13 (ArmorScorer)
        }
        return result;
    }

    /**
     * Snapshot of one active status effect on the player.
     * Populated from player.getActiveEffectsMap() each tick.
     */
    public static class StatusEffectData {
        public final String id;        // e.g. "minecraft:poison"
        public final int duration;     // ticks remaining
        public final int amplifier;    // 0-based (0 = level I)
        public final boolean ambient;  // from beacon, etc.

        public StatusEffectData(String id, int duration, int amplifier, boolean ambient) {
            this.id = id;
            this.duration = duration;
            this.amplifier = amplifier;
            this.ambient = ambient;
        }
    }

    /**
     * Nearby hostile entity info, sorted by distance.
     */
    public static class ThreatInfo {
        public final String type;
        public final float health;
        public final float distance;
        public final double x, y, z;

        public ThreatInfo(String type, float health, float distance, double x, double y, double z) {
            this.type = type;
            this.health = health;
            this.distance = distance;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Incoming projectile info, sorted by distance.
     * Only includes projectiles moving toward the player (dot product > 0.3).
     */
    public static class ProjectileInfo {
        public final String type;
        public final float distance;
        public final double x, y, z;
        public final double velX, velY, velZ;

        public ProjectileInfo(String type, float distance, double x, double y, double z,
                              double velX, double velY, double velZ) {
            this.type = type;
            this.distance = distance;
            this.x = x;
            this.y = y;
            this.z = z;
            this.velX = velX;
            this.velY = velY;
            this.velZ = velZ;
        }
    }
}
