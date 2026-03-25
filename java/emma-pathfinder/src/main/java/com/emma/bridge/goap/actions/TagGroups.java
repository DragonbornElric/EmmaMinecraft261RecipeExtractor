package com.emma.bridge.goap.actions;

import com.emma.bridge.goap.WorldState;

import java.util.*;

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
    private static final Map<String, List<String>> GROUPS;
    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("planks", List.of("oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
                "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
                "pale_oak_planks", "crimson_planks", "warped_planks", "bamboo_planks"));
        m.put("logs", List.of("oak_log", "spruce_log", "birch_log", "jungle_log",
                "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log",
                "pale_oak_log", "crimson_stem", "warped_stem"));
        m.put("wood", List.of("oak_wood", "spruce_wood", "birch_wood", "jungle_wood",
                "acacia_wood", "dark_oak_wood", "mangrove_wood", "cherry_wood",
                "pale_oak_wood", "crimson_hyphae", "warped_hyphae"));
        m.put("stripped_logs", List.of("stripped_oak_log", "stripped_spruce_log", "stripped_birch_log",
                "stripped_jungle_log", "stripped_acacia_log", "stripped_dark_oak_log",
                "stripped_mangrove_log", "stripped_cherry_log", "stripped_pale_oak_log",
                "stripped_crimson_stem", "stripped_warped_stem"));
        m.put("stripped_wood", List.of("stripped_oak_wood", "stripped_spruce_wood", "stripped_birch_wood",
                "stripped_jungle_wood", "stripped_acacia_wood", "stripped_dark_oak_wood",
                "stripped_mangrove_wood", "stripped_cherry_wood", "stripped_pale_oak_wood",
                "stripped_crimson_hyphae", "stripped_warped_hyphae"));
        m.put("stone_materials", List.of("cobblestone", "cobbled_deepslate", "blackstone"));
        m.put("stone", List.of("stone", "cobblestone", "deepslate", "cobbled_deepslate", "blackstone"));
        GROUPS = Collections.unmodifiableMap(m);
    }

    /**
     * Super groups: groups whose members are interchangeable for crafting purposes.
     * E.g., any log, wood, stripped_log, or stripped_wood can ultimately produce planks,
     * so having one means we don't need to derive goals for the others.
     * Used only by GoalDecomposer for goal suppression — NOT for block mining lookups.
     */
    private static final Map<String, List<String>> SUPER_GROUPS = Map.of(
            "wood_sources", List.of("logs", "wood", "stripped_logs", "stripped_wood", "planks"),
            "stone_sources", List.of("stone", "stone_materials")
    );

    /** Map from group name → super group name (for fast lookup). */
    private static final Map<String, String> GROUP_TO_SUPER;
    static {
        Map<String, String> gs = new HashMap<>();
        for (var entry : SUPER_GROUPS.entrySet()) {
            for (String group : entry.getValue()) {
                gs.put(group, entry.getKey());
            }
        }
        GROUP_TO_SUPER = Collections.unmodifiableMap(gs);
    }

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

    /**
     * Check if any item in the same super group is available.
     * E.g., if itemId is "minecraft:spruce_log" (group "logs", super group "wood_sources"),
     * returns true if ANY log, wood, stripped_log, stripped_wood, or planks variant is available.
     * Used by GoalDecomposer to suppress redundant alternative-path goals.
     *
     * @return the matching item ID if found, or null
     */
    public static String findSuperGroupMatch(String itemId, WorldState state) {
        String tagGroup = getItemTagGroup(itemId);
        if (tagGroup == null) return null;

        String superGroup = GROUP_TO_SUPER.get(tagGroup);
        if (superGroup == null) {
            // No super group — just check within the single tag group
            for (String member : getGroupMembersNamespaced(tagGroup)) {
                if (state.hasItem(member, 1)) return member;
            }
            return null;
        }

        // Check all groups in the super group
        for (String relatedGroup : SUPER_GROUPS.get(superGroup)) {
            for (String member : getGroupMembersNamespaced(relatedGroup)) {
                if (state.hasItem(member, 1)) return member;
            }
        }
        return null;
    }

}
