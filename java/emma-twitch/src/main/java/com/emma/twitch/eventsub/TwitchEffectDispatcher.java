package com.emma.twitch.eventsub;

import dev.qixils.crowdcontrol.common.command.Command;
import live.crowdcontrol.cc4j.CCPlayer;
import live.crowdcontrol.cc4j.CrowdControl;
import live.crowdcontrol.cc4j.websocket.payload.CCUserRecord;
import live.crowdcontrol.cc4j.websocket.payload.ProfileType;
import live.crowdcontrol.cc4j.websocket.payload.PublicEffectPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dispatches Twitch EventSub notifications to Crowd Control effects.
 * Maps reward IDs and bit tiers to effect IDs, then executes them.
 */
public class TwitchEffectDispatcher {
	private static final Logger LOGGER = LoggerFactory.getLogger("emma-twitch");

	private final TwitchConfig config;
	private final CrowdControl crowdControl;
	private final MinecraftServer server;
	private final Map<String, Long> effectCooldowns = new ConcurrentHashMap<>();

	public TwitchEffectDispatcher(TwitchConfig config, CrowdControl crowdControl, MinecraftServer server) {
		this.config = config;
		this.crowdControl = crowdControl;
		this.server = server;
	}

	/**
	 * Handle a Twitch event notification and dispatch the corresponding effect.
	 */
	public void dispatch(EventSubNotification notification) {
		String effectId = resolveEffectId(notification);
		if (effectId == null) {
			LOGGER.debug("[emma-twitch] No effect mapped for event: type={}, rewardId={}, bits={}",
				notification.type, notification.rewardId, notification.bits);
			return;
		}

		if (config.disabledEffects.contains(effectId)) {
			LOGGER.debug("[emma-twitch] Effect '{}' is disabled", effectId);
			return;
		}

		if (isOnCooldown(effectId)) {
			LOGGER.debug("[emma-twitch] Effect '{}' is on cooldown", effectId);
			return;
		}

		var effect = crowdControl.getEffect(effectId);
		if (effect == null) {
			LOGGER.warn("[emma-twitch] Unknown effect ID: '{}'", effectId);
			return;
		}

		// Execute on the server thread
		server.execute(() -> executeEffect(effect, effectId, notification));
	}

	private String resolveEffectId(EventSubNotification notification) {
		return switch (notification.type) {
			case "channel.channel_points_custom_reward_redemption.add" -> {
				if (notification.rewardId == null) yield null;
				TwitchConfig.RewardMapping mapping = config.rewardMappings.get(notification.rewardId);
				yield (mapping != null && mapping.enabled) ? mapping.effectId : null;
			}
			case "channel.cheer" -> {
				if (notification.bits <= 0) yield null;
				// Sort tiers descending by minBits, pick the highest that the cheer meets
				yield config.bitTiers.stream()
					.filter(t -> t.enabled && notification.bits >= t.minBits)
					.sorted()
					.map(t -> t.effectId)
					.findFirst()
					.orElse(null);
			}
			default -> null;
		};
	}

	private boolean isOnCooldown(String effectId) {
		Long cooldownEnd = effectCooldowns.get(effectId);
		if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
			return true;
		}
		// Apply cooldown
		int cooldownSeconds = config.effectCooldowns.getOrDefault(effectId, config.globalCooldownSeconds);
		effectCooldowns.put(effectId, System.currentTimeMillis() + (cooldownSeconds * 1000L));
		return false;
	}

	@SuppressWarnings("unchecked")
	private void executeEffect(Object effect, String effectId, EventSubNotification notification) {
		try {
			// Build the payload
			CCUserRecord requester = new CCUserRecord(
				"twitch-" + notification.userId,
				notification.userName,
				ProfileType.TWITCH,
				notification.userId,
				null
			);

			PublicEffectPayload payload = new PublicEffectPayload(
				UUID.randomUUID(),  // request ID
				effectId,
				1,                  // quantity
				30000L,             // default 30s duration for timed effects
				notification.isAnonymous,
				requester,
				requester           // target = requester for Twitch
			);

			// Get a random online player to apply the effect to
			List<ServerPlayer> players = server.getPlayerList().getPlayers();
			if (players.isEmpty()) {
				LOGGER.debug("[emma-twitch] No players online, skipping effect '{}'", effectId);
				return;
			}

			// Create a CCPlayer stub for the first online player
			ServerPlayer targetPlayer = players.getFirst();
			CCPlayer ccPlayer = crowdControl.addPlayer(targetPlayer.getUUID());

			// Trigger the effect
			if (effect instanceof Command<?> command) {
				command.onTrigger(payload, ccPlayer);
				LOGGER.info("[emma-twitch] Effect '{}' triggered by {} → applied to {}",
					effectId, notification.userName, targetPlayer.getName().getString());

				// Broadcast to chat
				broadcastEffect(effectId, notification.userName);
			}
		} catch (Exception e) {
			LOGGER.error("[emma-twitch] Error executing effect '{}'", effectId, e);
		}
	}

	private void broadcastEffect(String effectId, String viewerName) {
		String message = String.format("§e[Twitch]§r %s triggered §b%s§r!", viewerName, effectId);
		server.getPlayerList().getPlayers().forEach(player ->
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message))
		);
	}

	/**
	 * Get all registered effect IDs from the CrowdControl instance.
	 */
	public Set<String> getRegisteredEffects() {
		return crowdControl.getEffects().keySet();
	}

	/**
	 * Manually trigger an effect (for testing from the web GUI).
	 */
	public void testEffect(String effectId, String testerName) {
		EventSubNotification notification = new EventSubNotification();
		notification.type = "test";
		notification.userName = testerName;
		notification.userId = "test-user";

		var effect = crowdControl.getEffect(effectId);
		if (effect != null) {
			server.execute(() -> executeEffect(effect, effectId, notification));
		}
	}
}
