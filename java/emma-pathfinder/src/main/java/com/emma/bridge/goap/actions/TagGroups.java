package com.emma.bridge.goap.actions;

import com.emma.bridge.goap.WorldState;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unified tag group registry for item and block variant equivalence.
 * E.g., "any planks variant counts as planks" for both crafting and mining.
 */
public final class TagGroups {

    private TagGroups() {}

    /**
     * Canonical group definitions. Keys are bare names (no namespace prefix).
     * Used for both item and block lookups after namespace stripping.
     */
    private static final Map<String, List<String>> GROUPS = Map.of(
            "planks", List.of("oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
                    "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
                    "pale_oak_planks", "crimson_planks", "warped_planks", "bamboo_planks"),
            "logs", List.of("oak_log", "spruce_log", "birch_log", "jungle_log",
                    "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log",
                    "pale_oak_log", "crimson_stem", "warped_stem"),
            "stone_materials", List.of("cobblestone", "cobbled_deepslate", "blackstone"),
            "stone", List.of("stone", "cobblestone", "deepslate", "cobbled_deepslate", "blackstone")
    );

    /** Strip "minecraft:" (or any namespace) prefix from an ID. */
    public static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /**
     * Get the tag group name for an item ID (with or without namespace).
     * @return group name, or null if not in any group
     */
    public static String getItemTagGroup(String itemId) {
        String bare = stripNamespace(itemId);
        for (var entry : GROUPS.entrySet()) {
            if (entry.getValue().contains(bare)) return entry.getKey();
        }
        return null;
    }

    /**
     * Get the tag group name for a block ID (with or without namespace).
     * @return group name, or null if not in any group
     */
    public static String getBlockTagGroup(String blockId) {
        String bare = stripNamespace(blockId);
        for (var entry : GROUPS.entrySet()) {
            if (entry.getValue().contains(bare)) return entry.getKey();
        }
        return null;
    }

    /**
     * Get all members of a group, with "minecraft:" namespace prefix.
     * @return list of namespaced IDs, or empty list if group not found
     */
    public static List<String> getGroupMembersNamespaced(String groupName) {
        List<String> members = GROUPS.get(groupName);
        if (members == null) return List.of();
        return members.stream().map(m -> "minecraft:" + m).toList();
    }

    /**
     * Get all members of a group, bare (no namespace).
     * @return list of bare IDs, or empty list if group not found
     */
    public static List<String> getGroupMembers(String groupName) {
        List<String> members = GROUPS.get(groupName);
        return members != null ? members : List.of();
    }

    // ── WorldState block lookups (moved from MineBlockAction) ───

    /**
     * Find all nearby block positions matching blockType or any tag equivalent.
     * E.g., "minecraft:oak_log" also returns positions of spruce_log, birch_log, etc.
     */
    public static List<BlockPos> findNearbyWithTagEquivalents(WorldState state, String blockType) {
        // Direct match first
        List<BlockPos> direct = state.nearbyBlocks.get(blockType);
        if (direct != null && !direct.isEmpty()) return direct;

        // Check tag equivalents
        String group = getBlockTagGroup(blockType);
        if (group == null) return List.of();

        List<BlockPos> all = new ArrayList<>();
        for (String variant : getGroupMembersNamespaced(group)) {
            List<BlockPos> positions = state.nearbyBlocks.get(variant);
            if (positions != null) all.addAll(positions);
        }
        return all;
    }

    /**
     * Find which variant of a block type is actually present nearby.
     * Returns the variant with the most positions, for Emmatone mining.
     */
    public static String findBestNearbyVariant(WorldState state, String blockType) {
        List<BlockPos> direct = state.nearbyBlocks.get(blockType);
        if (direct != null && !direct.isEmpty()) return blockType;

        String group = getBlockTagGroup(blockType);
        if (group == null) return blockType;

        String best = blockType;
        int bestCount = 0;
        for (String variant : getGroupMembersNamespaced(group)) {
            List<BlockPos> positions = state.nearbyBlocks.get(variant);
            if (positions != null && positions.size() > bestCount) {
                bestCount = positions.size();
                best = variant;
            }
        }
        return best;
    }
}
