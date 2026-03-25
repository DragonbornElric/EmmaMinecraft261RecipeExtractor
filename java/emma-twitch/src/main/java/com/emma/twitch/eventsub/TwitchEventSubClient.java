package com.emma.twitch.eventsub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * WebSocket client for Twitch EventSub.
 * Connects to wss://eventsub.wss.twitch.tv/ws, receives events,
 * and dispatches them to the effect system.
 */
public class TwitchEventSubClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("emma-twitch");
	private static final String EVENTSUB_WS_URL = "wss://eventsub.wss.twitch.tv/ws";
	private static final String HELIX_URL = "https://api.twitch.tv/helix";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final TwitchConfig config;
	private final Consumer<EventSubNotification> eventHandler;
	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "emma-twitch-scheduler");
		t.setDaemon(true);
		return t;
	});

	private WebSocketClient wsClient;
	private String sessionId;
	private volatile boolean connected = false;
	private ScheduledFuture<?> keepaliveTimeout;
	private int reconnectAttempts = 0;

	public TwitchEventSubClient(TwitchConfig config, Consumer<EventSubNotification> eventHandler) {
		this.config = config;
		this.eventHandler = eventHandler;
	}

	public void connect() {
		if (!config.twitch.isConfigured()) {
			LOGGER.warn("[emma-twitch] Twitch not configured — skipping EventSub connection");
			return;
		}
		connectWebSocket(EVENTSUB_WS_URL);
	}

	private void connectWebSocket(String url) {
		try {
			wsClient = new WebSocketClient(new URI(url)) {
				@Override
				public void onOpen(ServerHandshake handshake) {
					LOGGER.info("[emma-twitch] EventSub WebSocket connected");
					reconnectAttempts = 0;
				}

				@Override
				public void onMessage(String message) {
					handleMessage(message);
				}

				@Override
				public void onClose(int code, String reason, boolean remote) {
					LOGGER.info("[emma-twitch] EventSub WebSocket closed: {} ({})", reason, code);
					connected = false;
					if (remote) scheduleReconnect();
				}

				@Override
				public void onError(Exception ex) {
					LOGGER.error("[emma-twitch] EventSub WebSocket error", ex);
				}
			};
			wsClient.connect();
		} catch (Exception e) {
			LOGGER.error("[emma-twitch] Failed to connect to EventSub", e);
			scheduleReconnect();
		}
	}

	private void handleMessage(String raw) {
		try {
			JsonNode root = MAPPER.readTree(raw);
			JsonNode metadata = root.get("metadata");
			if (metadata == null) return;

			String messageType = metadata.get("message_type").asText();

			switch (messageType) {
				case "session_welcome" -> {
					sessionId = root.get("payload").get("session").get("id").asText();
					int keepaliveSeconds = root.get("payload").get("session").get("keepalive_timeout_seconds").asInt(10);
					LOGGER.info("[emma-twitch] EventSub session established: {}", sessionId);
					connected = true;
					resetKeepaliveTimeout(keepaliveSeconds + 5);
					subscribeToEvents();
				}
				case "session_keepalive" -> {
					resetKeepaliveTimeout(15);
				}
				case "session_reconnect" -> {
					String reconnectUrl = root.get("payload").get("session").get("reconnect_url").asText();
					LOGGER.info("[emma-twitch] EventSub reconnect requested → {}", reconnectUrl);
					connectWebSocket(reconnectUrl);
				}
				case "notification" -> {
					JsonNode payload = root.get("payload");
					String subscriptionType = payload.get("subscription").get("type").asText();
					JsonNode event = payload.get("event");
					handleNotification(subscriptionType, event);
				}
				case "revocation" -> {
					String subType = root.get("payload").get("subscription").get("type").asText();
					String reason = root.get("payload").get("subscription").get("status").asText();
					LOGGER.warn("[emma-twitch] Subscription revoked: {} ({})", subType, reason);
				}
			}
		} catch (Exception e) {
			LOGGER.error("[emma-twitch] Error handling EventSub message", e);
		}
	}

	private void handleNotification(String type, JsonNode event) {
		try {
			EventSubNotification notification = new EventSubNotification();
			notification.type = type;

			switch (type) {
				case "channel.channel_points_custom_reward_redemption.add" -> {
					notification.userName = event.get("user_name").asText();
					notification.userId = event.get("user_id").asText();
					notification.rewardId = event.get("reward").get("id").asText();
					notification.rewardTitle = event.get("reward").get("title").asText();
					notification.redemptionId = event.get("id").asText();
					LOGGER.info("[emma-twitch] Channel point redemption: {} redeemed '{}'",
						notification.userName, notification.rewardTitle);
				}
				case "channel.cheer" -> {
					notification.userName = event.has("user_name") ? event.get("user_name").asText() : "Anonymous";
					notification.userId = event.has("user_id") ? event.get("user_id").asText() : "";
					notification.bits = event.get("bits").asInt();
					notification.isAnonymous = event.get("is_anonymous").asBoolean();
					LOGGER.info("[emma-twitch] Cheer: {} sent {} bits",
						notification.userName, notification.bits);
				}
				default -> {
					LOGGER.debug("[emma-twitch] Unhandled event type: {}", type);
					return;
				}
			}

			eventHandler.accept(notification);
		} catch (Exception e) {
			LOGGER.error("[emma-twitch] Error processing notification", e);
		}
	}

	private void subscribeToEvents() {
		subscribeToEvent("channel.channel_points_custom_reward_redemption.add", "1");
		subscribeToEvent("channel.cheer", "1");
	}

	private void subscribeToEvent(String type, String version) {
		try {
			String body = MAPPER.writeValueAsString(new java.util.LinkedHashMap<>() {{
				put("type", type);
				put("version", version);
				put("condition", new java.util.LinkedHashMap<>() {{
					put("broadcaster_user_id", config.twitch.channelId);
				}});
				put("transport", new java.util.LinkedHashMap<>() {{
					put("method", "websocket");
					put("session_id", sessionId);
				}});
			}});

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(HELIX_URL + "/eventsub/subscriptions"))
				.header("Authorization", "Bearer " + config.twitch.accessToken)
				.header("Client-Id", config.twitch.clientId)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

			httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					if (response.statusCode() == 202) {
						LOGGER.info("[emma-twitch] Subscribed to {}", type);
					} else {
						LOGGER.error("[emma-twitch] Failed to subscribe to {}: {} {}",
							type, response.statusCode(), response.body());
					}
				})
				.exceptionally(e -> {
					LOGGER.error("[emma-twitch] Error subscribing to {}", type, e);
					return null;
				});
		} catch (Exception e) {
			LOGGER.error("[emma-twitch] Error creating subscription for {}", type, e);
		}
	}

	private void resetKeepaliveTimeout(int seconds) {
		if (keepaliveTimeout != null) keepaliveTimeout.cancel(false);
		keepaliveTimeout = scheduler.schedule(() -> {
			LOGGER.warn("[emma-twitch] Keepalive timeout — reconnecting");
			disconnect();
			connect();
		}, seconds, TimeUnit.SECONDS);
	}

	private void scheduleReconnect() {
		int delay = Math.min(30, (int) Math.pow(2, reconnectAttempts));
		reconnectAttempts++;
		LOGGER.info("[emma-twitch] Reconnecting in {} seconds (attempt {})", delay, reconnectAttempts);
		scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
	}

	public void disconnect() {
		connected = false;
		if (keepaliveTimeout != null) keepaliveTimeout.cancel(false);
		if (wsClient != null) {
			try { wsClient.closeBlocking(); } catch (Exception ignored) {}
		}
	}

	public boolean isConnected() { return connected; }
	public String getSessionId() { return sessionId; }

	public void shutdown() {
		disconnect();
		scheduler.shutdownNow();
	}

	/**
	 * Refresh the OAuth token using the refresh token.
	 * Returns true if the token was refreshed successfully.
	 */
	public CompletableFuture<Boolean> refreshToken() {
		if (config.twitch.refreshToken.isEmpty()) {
			return CompletableFuture.completedFuture(false);
		}

		String body = "grant_type=refresh_token"
			+ "&refresh_token=" + config.twitch.refreshToken
			+ "&client_id=" + config.twitch.clientId
			+ "&client_secret=" + config.twitch.clientSecret;

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("https://id.twitch.tv/oauth2/token"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();

		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(response -> {
				if (response.statusCode() == 200) {
					try {
						JsonNode json = MAPPER.readTree(response.body());
						config.twitch.accessToken = json.get("access_token").asText();
						config.twitch.refreshToken = json.get("refresh_token").asText();
						LOGGER.info("[emma-twitch] Token refreshed successfully");
						return true;
					} catch (Exception e) {
						LOGGER.error("[emma-twitch] Error parsing token refresh response", e);
					}
				} else {
					LOGGER.error("[emma-twitch] Token refresh failed: {}", response.body());
				}
				return false;
			});
	}
}
