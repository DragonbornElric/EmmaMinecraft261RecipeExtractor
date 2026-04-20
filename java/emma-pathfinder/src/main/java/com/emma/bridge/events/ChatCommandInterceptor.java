package com.emma.bridge.events;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.commands.CommandRouter;
import com.emma.bridge.goap.actions.EstablishBaseAction;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Intercepts chat messages starting with '@' and routes them to the CommandRouter.
 *
 * Provides the same command interface available via WebSocket from the Python bridge,
 * but directly from the Minecraft chat window. Useful for testing GOAP behaviors
 * without Python running.
 *
 * Usage: type "@command args" in chat, e.g.:
 *   @stop              — cancel current task
 *   @status            — show player status
 *   @get diamond_pickaxe 1  — set goal to get item
 *   @goto 100 64 -50   — navigate to coordinates
 *   @mine diamond_ore 5 — mine N blocks
 *   @debug              — show GOAP scoring
 *   @goals              — show current goals
 *   @personality         — show personality weights
 *   @inventory           — show inventory
 *   @farm                — start farming
 *   @base establish       — find village and register as base
 *
 * Messages are intercepted BEFORE they reach the server, so nothing is sent to chat.
 * Responses appear as client-side system messages.
 */
public class ChatCommandInterceptor {

    private final CommandRouter router;

    public ChatCommandInterceptor(CommandRouter router) {
        this.router = router;
    }

    /**
     * Register the chat interceptor with Fabric's client send message events.
     * Must be called once during mod initialization.
     */
    public void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(this::onChatMessage);
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Chat command interceptor registered (@ prefix)");
    }

    /**
     * Called before a chat message is sent to the server.
     * Returns false to cancel sending if it's an @ command.
     */
    private boolean onChatMessage(String message) {
        if (!message.startsWith("@")) return true;  // not a command, send normally

        // Strip @ prefix and trim
        String raw = message.substring(1).trim();
        if (raw.isEmpty()) return true;  // just "@" with nothing else

        // Parse and dispatch on a safe thread
        Minecraft.getInstance().execute(() -> {
            try {
                dispatchCommand(raw);
            } catch (Exception e) {
                sendResponse("Error: " + e.getMessage());
                EmmaBridgeMod.LOGGER.warn("[ChatCmd] Error dispatching: {}", raw, e);
            }
        });

        return false;  // suppress the message from being sent to server
    }

    /**
     * Parse the raw command string and dispatch to the CommandRouter.
     */
    private void dispatchCommand(String raw) {
        String[] parts = raw.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String argStr = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            // ── Modes ──
            case "stop" -> {
                JsonObject modeParams = new JsonObject();
                modeParams.addProperty("mode", "stop");
                dispatch("set_mode", modeParams);
                sendResponse("Stop: GOAP disabled, goals cleared");
            }
            case "idle" -> {
                JsonObject modeParams = new JsonObject();
                modeParams.addProperty("mode", "idle");
                dispatch("set_mode", modeParams);
                sendResponse("Idle: survival only, GOAP enabled");
            }
            case "hero" -> {
                parseHero(argStr);
            }
            case "gamer" -> {
                JsonObject modeParams = new JsonObject();
                modeParams.addProperty("mode", "gamer");
                JsonObject modeResult = dispatch("set_mode", modeParams);
                if (modeResult != null && modeResult.has("goals_set")) {
                    sendResponse("Gamer: kill_dragon goal set, GOAP enabled");
                }
            }

            // ── Navigation ──
            case "cancel" -> {
                dispatch("cancel", new JsonObject());
            }
            case "goto" -> {
                parseGoto(argStr);
            }

            // ── Goals ──
            case "get" -> {
                parseGet(argStr);
            }

            // ── Mining ──
            case "mine" -> {
                parseMine(argStr);
            }

            // ── Info ──
            case "status" -> {
                JsonObject result = dispatch("status", new JsonObject());
                if (result != null) formatStatus(result);
            }
            case "inventory", "inv" -> {
                JsonObject result = dispatch("inventory", new JsonObject());
                if (result != null) formatInventory(result);
            }
            case "debug" -> {
                JsonObject params = new JsonObject();
                if (!argStr.isEmpty()) params.addProperty("action", argStr);
                else params.addProperty("action", "snapshot");
                JsonObject result = dispatch("agent_debug", params);
                if (result != null) formatDebug(result);
            }

            // ── GOAP Control ──
            case "goals" -> {
                JsonObject params = new JsonObject();
                params.addProperty("action", "snapshot");
                JsonObject result = dispatch("agent_debug", params);
                if (result != null) formatGoals(result);
            }
            case "personality" -> {
                if (argStr.isEmpty()) {
                    // Show current personality
                    JsonObject params = new JsonObject();
                    params.addProperty("action", "snapshot");
                    JsonObject result = dispatch("agent_debug", params);
                    if (result != null) formatPersonality(result);
                } else {
                    // Set personality: @personality aggression 0.9
                    parseSetPersonality(argStr);
                }
            }
            case "goap" -> {
                parseGoap(argStr);
            }

            // ── Base ──
            case "base" -> {
                parseBase(argStr);
            }

            // ── Farming ──
            case "farm" -> {
                JsonObject params = new JsonObject();
                if (!argStr.isEmpty()) params.addProperty("crop", argStr);
                dispatch("farm", params);
            }

            // ── Combat / Torch / Other ──
            case "torch" -> {
                dispatch("torch", new JsonObject());
            }
            case "respawn" -> {
                dispatch("respawn", new JsonObject());
            }

            // ── Fallthrough: try direct dispatch ──
            default -> {
                JsonObject params = new JsonObject();
                if (!argStr.isEmpty()) {
                    params.addProperty("args", argStr);
                }
                if (router.hasCommand(cmd)) {
                    JsonObject result = dispatch(cmd, params);
                    if (result != null) {
                        sendResponse(cmd + ": " + compactJson(result));
                    } else {
                        sendResponse(cmd + ": OK");
                    }
                } else {
                    sendResponse("Unknown command: " + cmd);
                }
            }
        }
    }

    // ── Parsers ──────────────────────────────────────────────────

    private void parseGoto(String argStr) {
        String[] args = argStr.split("\\s+");
        JsonObject params = new JsonObject();

        if (args.length >= 3) {
            try {
                params.addProperty("x", Integer.parseInt(args[0]));
                params.addProperty("y", Integer.parseInt(args[1]));
                params.addProperty("z", Integer.parseInt(args[2]));
                dispatch("goto", params);
                return;
            } catch (NumberFormatException ignored) {}
        }

        // Try as player name
        if (args.length >= 1 && !args[0].isEmpty()) {
            params.addProperty("player", args[0]);
            dispatch("goto", params);
            return;
        }

        sendResponse("Usage: @goto <x> <y> <z> or @goto <player>");
    }

    private void parseGet(String argStr) {
        if (argStr.isEmpty()) {
            sendResponse("Usage: @get <item> [count]");
            return;
        }

        String[] args = argStr.split("\\s+");
        String item = args[0];
        int count = 1;
        if (args.length >= 2) {
            try { count = Integer.parseInt(args[1]); }
            catch (NumberFormatException ignored) {}
        }

        // Build a set_goals command with a single have_item goal
        JsonObject params = new JsonObject();
        JsonArray goals = new JsonArray();
        JsonObject goal = new JsonObject();
        goal.addProperty("id", "have_item_" + item);
        goal.addProperty("type", "have_item");
        goal.addProperty("priority", 8);

        JsonObject target = new JsonObject();
        target.addProperty("item", item.contains(":") ? item : "minecraft:" + item);
        target.addProperty("count", count);
        goal.add("target", target);
        goals.add(goal);

        params.add("goals", goals);
        params.addProperty("mode", "add");

        // Auto-enable GOAP if not already running — no point setting goals
        // for a disabled planner
        JsonObject enableParams = new JsonObject();
        enableParams.addProperty("action", "enable");
        dispatch("agent_debug", enableParams);

        dispatch("set_goals", params);
        sendResponse("Goal: get " + count + "x " + item + " (GOAP enabled)");
    }

    private void parseMine(String argStr) {
        if (argStr.isEmpty()) {
            sendResponse("Usage: @mine <block> [count]");
            return;
        }

        String[] args = argStr.split("\\s+");
        JsonObject params = new JsonObject();
        params.addProperty("block_type", args[0]);
        if (args.length >= 2) {
            try { params.addProperty("quantity", Integer.parseInt(args[1])); }
            catch (NumberFormatException ignored) {}
        }
        dispatch("mine", params);
    }

    private void parseHero(String argStr) {
        String tier = argStr.isEmpty() ? "diamond" : argStr.trim().toLowerCase();
        JsonObject params = new JsonObject();
        params.addProperty("mode", "hero");
        params.addProperty("tier", tier);
        JsonObject result = dispatch("set_mode", params);
        if (result != null) {
            if (result.has("error")) {
                sendResponse("Hero: " + result.get("error").getAsString());
            } else {
                int goalCount = result.has("goals_set") ? result.get("goals_set").getAsInt() : 0;
                sendResponse("Hero mode: " + tier + " tier (" + goalCount + " goals, GOAP enabled)");
            }
        }
    }

    private void parseBase(String argStr) {
        switch (argStr.toLowerCase()) {
            case "establish" -> {
                EstablishBaseAction.triggerByCommand();
                // Enable GOAP so the action can run
                JsonObject enableParams = new JsonObject();
                enableParams.addProperty("action", "enable");
                dispatch("agent_debug", enableParams);
                sendResponse("Base: village search triggered (GOAP enabled)");
            }
            default -> {
                sendResponse("Usage: @base establish");
            }
        }
    }

    private void parseGoap(String argStr) {
        switch (argStr.toLowerCase()) {
            case "on", "enable" -> {
                JsonObject params = new JsonObject();
                params.addProperty("action", "enable");
                dispatch("agent_debug", params);
                sendResponse("GOAP enabled");
            }
            case "off", "disable" -> {
                JsonObject params = new JsonObject();
                params.addProperty("action", "disable");
                dispatch("agent_debug", params);
                sendResponse("GOAP disabled");
            }
            case "log on" -> {
                JsonObject params = new JsonObject();
                params.addProperty("action", "log_on");
                dispatch("agent_debug", params);
                sendResponse("GOAP logging enabled");
            }
            case "log off" -> {
                JsonObject params = new JsonObject();
                params.addProperty("action", "log_off");
                dispatch("agent_debug", params);
                sendResponse("GOAP logging disabled");
            }
            default -> {
                sendResponse("Usage: @goap on|off|log on|log off");
            }
        }
    }

    private void parseSetPersonality(String argStr) {
        String[] args = argStr.split("\\s+");
        if (args.length < 2) {
            sendResponse("Usage: @personality <trait> <value>");
            return;
        }

        JsonObject params = new JsonObject();
        JsonObject weights = new JsonObject();
        try {
            weights.addProperty(args[0], Float.parseFloat(args[1]));
        } catch (NumberFormatException e) {
            sendResponse("Invalid value: " + args[1]);
            return;
        }
        params.add("weights", weights);
        dispatch("set_personality", params);
        sendResponse("Set " + args[0] + " = " + args[1]);
    }

    // ── Dispatch ─────────────────────────────────────────────────

    private JsonObject dispatch(String command, JsonObject params) {
        try {
            JsonObject result = router.dispatch(command, params);
            EmmaBridgeMod.LOGGER.info("[ChatCmd] {} -> {}", command,
                    result != null ? "OK" : "null");
            return result;
        } catch (IllegalArgumentException e) {
            sendResponse("Error: " + e.getMessage());
            return null;
        } catch (Exception e) {
            sendResponse("Error: " + e.getMessage());
            EmmaBridgeMod.LOGGER.warn("[ChatCmd] Error dispatching {}", command, e);
            return null;
        }
    }

    // ── Formatters ───────────────────────────────────────────────

    private void formatStatus(JsonObject result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Status] ");
        if (result.has("health")) sb.append("HP:").append(result.get("health").getAsInt()).append(" ");
        if (result.has("food")) sb.append("Food:").append(result.get("food").getAsInt()).append(" ");
        if (result.has("armor")) sb.append("Armor:").append(result.get("armor").getAsInt()).append(" ");
        if (result.has("position")) {
            JsonObject pos = result.getAsJsonObject("position");
            sb.append("Pos:").append(pos.get("x").getAsInt())
              .append(",").append(pos.get("y").getAsInt())
              .append(",").append(pos.get("z").getAsInt()).append(" ");
        }
        if (result.has("dimension")) {
            String dim = result.get("dimension").getAsString();
            sb.append("Dim:").append(dim.replace("minecraft:", ""));
        }
        sendResponse(sb.toString().trim());
    }

    private void formatInventory(JsonObject result) {
        if (!result.has("items")) {
            sendResponse("[Inventory] Empty");
            return;
        }

        JsonArray items = result.getAsJsonArray("items");
        if (items.isEmpty()) {
            sendResponse("[Inventory] Empty");
            return;
        }

        // Show first 8 items, compact
        StringBuilder sb = new StringBuilder("[Inventory] ");
        int shown = 0;
        for (var elem : items) {
            if (shown >= 8) { sb.append("...+").append(items.size() - shown); break; }
            JsonObject item = elem.getAsJsonObject();
            String name = item.get("item").getAsString().replace("minecraft:", "");
            int count = item.get("count").getAsInt();
            if (shown > 0) sb.append(", ");
            sb.append(name);
            if (count > 1) sb.append("x").append(count);
            shown++;
        }
        sendResponse(sb.toString());
    }

    private void formatDebug(JsonObject result) {
        StringBuilder sb = new StringBuilder("[GOAP] ");

        if (result.has("active_action")) {
            sb.append("Active:").append(result.get("active_action").getAsString()).append(" ");
        }
        if (result.has("goap_enabled")) {
            sb.append("Enabled:").append(result.get("goap_enabled").getAsBoolean()).append(" ");
        }

        // Show top 3 scored actions if available
        if (result.has("auction")) {
            JsonArray scored = result.getAsJsonArray("auction");
            sb.append("| Scores: ");
            int shown = 0;
            for (var elem : scored) {
                if (shown >= 3) break;
                JsonObject sa = elem.getAsJsonObject();
                if (shown > 0) sb.append(", ");
                sb.append(sa.get("action").getAsString())
                  .append("(").append(String.format("%.1f", sa.get("score").getAsFloat())).append(")");
                shown++;
            }
        }
        sendResponse(sb.toString().trim());
    }

    private void formatGoals(JsonObject result) {
        if (!result.has("goals")) {
            sendResponse("[Goals] No active goals");
            return;
        }

        JsonArray goals = result.getAsJsonArray("goals");
        if (goals.isEmpty()) {
            sendResponse("[Goals] No active goals");
            return;
        }

        // Separate user goals from derived goals
        StringBuilder userSb = new StringBuilder("[Goals] ");
        StringBuilder derivedSb = new StringBuilder("[Derived] ");
        int derivedCount = 0;

        for (var elem : goals) {
            JsonObject g = elem.getAsJsonObject();
            String id = g.get("id").getAsString();
            boolean isDerived = g.has("is_derived") && g.get("is_derived").getAsBoolean();

            if (isDerived) {
                derivedCount++;
                derivedSb.append(id);
                if (g.has("priority")) derivedSb.append("(p").append(String.format("%.1f", g.get("priority").getAsFloat())).append(")");
                derivedSb.append(" ");
            } else {
                userSb.append(id);
                if (g.has("priority")) userSb.append("(p").append(g.get("priority").getAsInt()).append(")");
                userSb.append(" ");
            }
        }

        sendResponse(userSb.toString().trim());
        if (derivedCount > 0) {
            sendResponse(derivedSb.toString().trim());
        }
    }

    private void formatPersonality(JsonObject result) {
        if (!result.has("personality")) {
            sendResponse("[Personality] No data");
            return;
        }

        JsonObject p = result.getAsJsonObject("personality");
        StringBuilder sb = new StringBuilder("[Personality] ");
        for (String key : p.keySet()) {
            sb.append(key).append("=")
              .append(String.format("%.2f", p.get(key).getAsFloat())).append(" ");
        }
        sendResponse(sb.toString().trim());
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static void sendResponse(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[Emma] " + message));
        }
    }

    private static String compactJson(JsonObject json) {
        String s = json.toString();
        if (s.length() > 120) return s.substring(0, 117) + "...";
        return s;
    }
}
