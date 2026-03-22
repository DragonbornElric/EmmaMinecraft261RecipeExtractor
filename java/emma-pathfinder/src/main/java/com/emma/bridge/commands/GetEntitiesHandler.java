package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.core.Holder;

import java.util.Comparator;
import java.util.List;

/**
 * Handles "get_entities" command — on-demand query of nearby entities with full data.
 * Returns type, position, distance, health, equipment, status effects,
 * and for villagers: profession, level, and trade offers.
 *
 * Params: { "radius": int (default 16), "type": string (optional filter),
 *           "limit": int (optional, default 32) }
 * Returns: { "entities": [...], "count": int }
 */
public class GetEntitiesHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "get_entities";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = Minecraft.getInstance().player;
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("count", 0);
            result.addProperty("reason", "no_player");
            return result;
        }

        int radius = params.has("radius") ? params.get("radius").getAsInt() : 16;
        int limit = params.has("limit") ? params.get("limit").getAsInt() : 32;
        String typeFilter = params.has("type") ? params.get("type").getAsString().toLowerCase() : null;

        AABB scanBox = player.getBoundingBox().inflate(radius);
        List<Entity> entities = player.level().getEntities(player, scanBox);

        JsonArray entitiesArray = new JsonArray();
        // Sort by distance
        entities.sort(Comparator.comparingDouble(player::distanceTo));

        int count = 0;
        for (Entity entity : entities) {
            if (!entity.isAlive() && !(entity instanceof net.minecraft.world.entity.item.ItemEntity)) continue;

            String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            String shortType = entityType.replace("minecraft:", "");

            if (typeFilter != null && !shortType.contains(typeFilter) && !entityType.contains(typeFilter)) {
                continue;
            }

            if (count >= limit) break;

            JsonObject entityObj = new JsonObject();
            entityObj.addProperty("type", entityType);
            entityObj.addProperty("x", Math.round(entity.getX() * 10.0) / 10.0);
            entityObj.addProperty("y", Math.round(entity.getY() * 10.0) / 10.0);
            entityObj.addProperty("z", Math.round(entity.getZ() * 10.0) / 10.0);
            entityObj.addProperty("distance", Math.round(player.distanceTo(entity) * 10.0) / 10.0);

            // Living entity data
            if (entity instanceof LivingEntity living) {
                entityObj.addProperty("health", Math.round(living.getHealth() * 10.0) / 10.0);
                entityObj.addProperty("max_health", living.getMaxHealth());

                // Equipment
                JsonObject equipment = serializeEquipment(living);
                if (equipment.size() > 0) {
                    entityObj.add("equipment", equipment);
                }

                // Status effects
                if (!living.getActiveEffectsMap().isEmpty()) {
                    JsonArray effects = new JsonArray();
                    for (var entry : living.getActiveEffectsMap().entrySet()) {
                        MobEffectInstance effect = entry.getValue();
                        JsonObject effectObj = new JsonObject();
                        effectObj.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(entry.getKey().value()).toString());
                        effectObj.addProperty("duration", effect.getDuration());
                        effectObj.addProperty("amplifier", effect.getAmplifier());
                        effects.add(effectObj);
                    }
                    entityObj.add("effects", effects);
                }

                // Baby status
                if (living.isBaby()) {
                    entityObj.addProperty("baby", true);
                }
            }

            // Item entity
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                entityObj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                entityObj.addProperty("count", stack.getCount());
            }

            // Villager-specific
            if (entity instanceof Villager villager) {
                Holder<VillagerProfession> profession = villager.getVillagerData().profession();
                String profName = profession.unwrapKey()
                        .map(k -> k.identifier().getPath())
                        .orElse("none");
                entityObj.addProperty("profession", profName);
                entityObj.addProperty("level", villager.getVillagerData().level());

                // Trade offers
                MerchantOffers offers = villager.getOffers();
                if (offers != null && !offers.isEmpty()) {
                    JsonArray trades = new JsonArray();
                    for (int i = 0; i < offers.size(); i++) {
                        MerchantOffer offer = offers.get(i);
                        JsonObject trade = new JsonObject();
                        trade.addProperty("index", i);

                        ItemCost traded1 = offer.getItemCostA();
                        ItemStack input1 = traded1.itemStack();
                        trade.addProperty("input1", BuiltInRegistries.ITEM.getKey(input1.getItem()).toString());
                        trade.addProperty("input1_count", input1.getCount());

                        offer.getItemCostB().ifPresent(traded2 -> {
                            ItemStack input2 = traded2.itemStack();
                            trade.addProperty("input2", BuiltInRegistries.ITEM.getKey(input2.getItem()).toString());
                            trade.addProperty("input2_count", input2.getCount());
                        });

                        ItemStack output = offer.getResult();
                        trade.addProperty("output", BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
                        trade.addProperty("output_count", output.getCount());

                        trade.addProperty("uses", offer.getUses());
                        trade.addProperty("max_uses", offer.getMaxUses());
                        trade.addProperty("disabled", offer.isOutOfStock());
                        trades.add(trade);
                    }
                    entityObj.add("trades", trades);
                }
            }

            // Player name
            if (entity instanceof net.minecraft.world.entity.player.Player pe) {
                entityObj.addProperty("name", pe.getName().getString());
            }

            entitiesArray.add(entityObj);
            count++;
        }

        result.add("entities", entitiesArray);
        result.addProperty("count", count);

        EmmaBridgeMod.LOGGER.debug("[Emma Bridge] get_entities: found {} (radius={}, filter={})",
                count, radius, typeFilter);
        return result;
    }

    private JsonObject serializeEquipment(LivingEntity entity) {
        JsonObject eq = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                eq.addProperty(slot.getName(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return eq;
    }
}
