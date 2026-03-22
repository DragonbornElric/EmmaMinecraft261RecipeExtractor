package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/**
 * Handles "world_scan" command — full 3D block scanning with two modes.
 *
 * Modes:
 *   "raw"     — every non-air block in the 3D volume
 *   "surface" — only blocks with at least one air/transparent neighbor (visible blocks)
 *
 * Params:
 *   cx, cz        — center position (defaults to player pos)
 *   radius         — horizontal radius (1-128, default 20)
 *   y_min, y_max   — Y range (defaults: auto from heightmap ±margins)
 *   mode           — "raw" or "surface" (default "surface")
 *   detail         — "positions" or "full" (default "positions")
 *   include_entities — boolean (default false)
 *
 * Returns:
 *   { blocks: [{b, x, y, z, s?}, ...], block_count, entity_count?,
 *     entities?: [...], center_x, center_z, y_range: [min, max], radius, mode }
 */
public class WorldScanHandler implements ICommandHandler {

    private static final int MAX_RADIUS = 128;

    @Override
    public String getCommand() {
        return "world_scan";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        Level world = client.level;
        if (world == null) {
            throw new RuntimeException("No world loaded");
        }

        // Parse center coordinates
        int cx, cz;
        if (params.has("cx") && params.has("cz")) {
            cx = params.get("cx").getAsInt();
            cz = params.get("cz").getAsInt();
        } else if (client.player != null) {
            cx = client.player.blockPosition().getX();
            cz = client.player.blockPosition().getZ();
        } else {
            throw new RuntimeException("No cx/cz provided and no player available");
        }

        int radius = params.has("radius")
                ? Math.min(Math.max(1, params.get("radius").getAsInt()), MAX_RADIUS)
                : 20;

        String mode = params.has("mode") ? params.get("mode").getAsString() : "surface";
        boolean fullDetail = params.has("detail") && "full".equals(params.get("detail").getAsString());
        boolean includeEntities = params.has("include_entities") && params.get("include_entities").getAsBoolean();
        boolean includeLight = params.has("include_light") && params.get("include_light").getAsBoolean();
        boolean includeBiome = params.has("include_biome") && params.get("include_biome").getAsBoolean();

        // Determine Y range
        int yMin, yMax;
        if (params.has("y_min") && params.has("y_max")) {
            yMin = params.get("y_min").getAsInt();
            yMax = params.get("y_max").getAsInt();
        } else {
            // Auto-detect from heightmap: find min/max surface Y in the area
            int surfaceMin = Integer.MAX_VALUE;
            int surfaceMax = Integer.MIN_VALUE;
            // Sample every 4 blocks for speed on large radii
            int sampleStep = Math.max(1, radius / 16);
            for (int sz = cz - radius; sz <= cz + radius; sz += sampleStep) {
                for (int sx = cx - radius; sx <= cx + radius; sx += sampleStep) {
                    int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz) - 1;
                    if (sy < surfaceMin) surfaceMin = sy;
                    if (sy > surfaceMax) surfaceMax = sy;
                }
            }
            if (surfaceMin == Integer.MAX_VALUE) {
                surfaceMin = 60;
                surfaceMax = 80;
            }
            int margin = "raw".equals(mode) ? 20 : 10;
            yMin = Math.max(-64, surfaceMin - margin);
            yMax = Math.min(319, surfaceMax + margin);
        }

        // Scan blocks
        JsonArray blocks = new JsonArray();
        int blockCount = 0;

        int minX = cx - radius;
        int maxX = cx + radius;
        int minZ = cz - radius;
        int maxZ = cz + radius;

        for (int y = yMin; y <= yMax; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    // Only scan loaded chunks
                    if (!world.isLoaded(pos)) continue;

                    BlockState state = world.getBlockState(pos);

                    // Skip air blocks
                    if (state.isAir()) continue;

                    // Surface mode: only include blocks with at least one air neighbor
                    if ("surface".equals(mode)) {
                        if (!hasAirNeighbor(world, pos)) continue;
                    }

                    JsonObject entry = new JsonObject();
                    entry.addProperty("b", stripMinecraftPrefix(
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
                    entry.addProperty("x", x);
                    entry.addProperty("y", y);
                    entry.addProperty("z", z);

                    // Full detail: include block state properties
                    if (fullDetail && !state.getProperties().isEmpty()) {
                        entry.addProperty("s", ScanHandler.stateToString(state));
                    }

                    // Opt-in: per-block light levels
                    if (includeLight) {
                        entry.addProperty("l", world.getMaxLocalRawBrightness(pos));
                        entry.addProperty("sl", world.getBrightness(LightLayer.SKY, pos));
                        entry.addProperty("bl", world.getBrightness(LightLayer.BLOCK, pos));
                    }

                    // Opt-in: biome per block
                    if (includeBiome) {
                        Holder<Biome> biome = world.getBiome(pos);
                        String biomeName = biome.unwrapKey().map(k -> k.identifier().toString())
                                .orElse("unknown");
                        entry.addProperty("biome", biomeName);
                    }

                    // Sign text: extract readable text from sign block entities
                    if (state.getBlock() instanceof SignBlock) {
                        String signText = HandlerUtils.extractSignText(world, pos);
                        if (signText != null) {
                            entry.addProperty("text", signText);
                        }
                    }

                    blocks.add(entry);
                    blockCount++;
                }
            }
        }

        EmmaBridgeMod.LOGGER.info(
                "[Emma Bridge] WorldScan: mode={} radius={} y=[{},{}] blocks={} at ({},{})",
                mode, radius, yMin, yMax, blockCount, cx, cz);

        // Build response
        JsonObject result = new JsonObject();
        result.add("blocks", blocks);
        result.addProperty("block_count", blockCount);
        result.addProperty("center_x", cx);
        result.addProperty("center_z", cz);

        JsonArray yRange = new JsonArray();
        yRange.add(yMin);
        yRange.add(yMax);
        result.add("y_range", yRange);

        result.addProperty("radius", radius);
        result.addProperty("mode", mode);

        // Entity scan
        if (includeEntities) {
            JsonArray entities = scanEntities(world, cx, cz, yMin, yMax, radius);
            result.add("entities", entities);
            result.addProperty("entity_count", entities.size());
        }

        return result;
    }

    /**
     * Check if any of the 6 neighbors is air (or a non-opaque block).
     */
    private boolean hasAirNeighbor(Level world, BlockPos pos) {
        return world.getBlockState(pos.above()).isAir()
                || world.getBlockState(pos.below()).isAir()
                || world.getBlockState(pos.north()).isAir()
                || world.getBlockState(pos.south()).isAir()
                || world.getBlockState(pos.east()).isAir()
                || world.getBlockState(pos.west()).isAir();
    }

    /**
     * Scan entities in the AABB and return as JSON array.
     */
    private JsonArray scanEntities(Level world, int cx, int cz, int yMin, int yMax, int radius) {
        AABB box = new AABB(
                cx - radius, yMin, cz - radius,
                cx + radius + 1, yMax + 1, cz + radius + 1
        );

        List<Entity> entityList = world.getEntities(null, box);
        JsonArray entities = new JsonArray();

        for (Entity entity : entityList) {
            JsonObject entry = new JsonObject();

            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            entry.addProperty("type", stripMinecraftPrefix(typeId));
            entry.addProperty("x", Math.round(entity.getX() * 10.0) / 10.0);
            entry.addProperty("y", Math.round(entity.getY() * 10.0) / 10.0);
            entry.addProperty("z", Math.round(entity.getZ() * 10.0) / 10.0);

            // Living entity data: health, equipment, status effects
            if (entity instanceof LivingEntity living) {
                entry.addProperty("health", Math.round(living.getHealth() * 10.0) / 10.0);
                entry.addProperty("max_health", living.getMaxHealth());

                if (living.isBaby()) {
                    entry.addProperty("baby", true);
                }

                // Equipment (all living entities, not just armor stands)
                StringBuilder eqSb = new StringBuilder();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack stack = living.getItemBySlot(slot);
                    if (!stack.isEmpty()) {
                        if (eqSb.length() > 0) eqSb.append(",");
                        eqSb.append(slot.getName()).append("=")
                             .append(stripMinecraftPrefix(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
                    }
                }
                if (eqSb.length() > 0) {
                    entry.addProperty("equipment", eqSb.toString());
                }

                // Active status effects
                if (!living.getActiveEffectsMap().isEmpty()) {
                    JsonArray effects = new JsonArray();
                    for (var effectEntry : living.getActiveEffectsMap().entrySet()) {
                        MobEffectInstance effect = effectEntry.getValue();
                        JsonObject effectObj = new JsonObject();
                        effectObj.addProperty("id",
                                BuiltInRegistries.MOB_EFFECT.getKey(effectEntry.getKey().value()).toString());
                        effectObj.addProperty("duration", effect.getDuration());
                        effectObj.addProperty("amplifier", effect.getAmplifier());
                        effects.add(effectObj);
                    }
                    entry.add("effects", effects);
                }
            }

            // Villager-specific: profession, level, trades
            if (entity instanceof Villager villager) {
                Holder<VillagerProfession> profession = villager.getVillagerData().profession();
                String profName = profession.unwrapKey()
                        .map(k -> k.identifier().getPath())
                        .orElse("none");
                entry.addProperty("profession", profName);
                entry.addProperty("level", villager.getVillagerData().level());

                MerchantOffers offers = villager.getOffers();
                if (offers != null && !offers.isEmpty()) {
                    JsonArray trades = new JsonArray();
                    for (int i = 0; i < offers.size(); i++) {
                        MerchantOffer offer = offers.get(i);
                        JsonObject trade = new JsonObject();
                        ItemCost traded1 = offer.getItemCostA();
                        ItemStack input1 = traded1.itemStack();
                        trade.addProperty("buy", stripMinecraftPrefix(
                                BuiltInRegistries.ITEM.getKey(input1.getItem()).toString()) + "x" + input1.getCount());
                        offer.getItemCostB().ifPresent(traded2 -> {
                            ItemStack input2x = traded2.itemStack();
                            trade.addProperty("buy2", stripMinecraftPrefix(
                                    BuiltInRegistries.ITEM.getKey(input2x.getItem()).toString()) + "x" + input2x.getCount());
                        });
                        ItemStack output = offer.getResult();
                        trade.addProperty("sell", stripMinecraftPrefix(
                                BuiltInRegistries.ITEM.getKey(output.getItem()).toString()) + "x" + output.getCount());
                        trade.addProperty("uses", offer.getUses() + "/" + offer.getMaxUses());
                        trades.add(trade);
                    }
                    entry.add("trades", trades);
                }
            } else if (entity instanceof ItemFrame itemFrame) {
                ItemStack stack = itemFrame.getItem();
                if (!stack.isEmpty()) {
                    entry.addProperty("extra", stripMinecraftPrefix(
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
                }
            } else if (entity instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                entry.addProperty("item", stripMinecraftPrefix(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
                entry.addProperty("count", stack.getCount());
            }

            // Player name
            if (entity instanceof net.minecraft.world.entity.player.Player pe) {
                entry.addProperty("name", pe.getName().getString());
            }

            entities.add(entry);
        }

        return entities;
    }

    /**
     * Strip "minecraft:" prefix from identifiers. Keeps modded namespaces intact.
     */
    private static String stripMinecraftPrefix(String id) {
        if (id.startsWith("minecraft:")) {
            return id.substring(10);
        }
        return id;
    }
}
