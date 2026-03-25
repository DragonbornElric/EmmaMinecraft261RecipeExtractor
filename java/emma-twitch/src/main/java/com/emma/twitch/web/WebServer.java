package com.emma.twitch.web;

import com.emma.twitch.eventsub.TwitchConfig;
import com.emma.twitch.eventsub.TwitchEffectDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import live.crowdcontrol.cc4j.CrowdControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server for the emma-twitch web configuration GUI.
 * Serves static files from JAR resources and provides REST API endpoints.
 */
public class WebServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("emma-twitch");
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private final TwitchConfig config;
	private final Path configPath;
	private final TwitchEffectDispatcher dispatcher;
	private final CrowdControl crowdControl;
	private HttpServer server;

	public WebServer(TwitchConfig config, Path configPath, TwitchEffectDispatcher dispatcher, CrowdControl crowdControl) {
		this.config = config;
		this.configPath = configPath;
		this.dispatcher = dispatcher;
		this.crowdControl = crowdControl;
	}

	public void start(int port) throws IOException {
		server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
		server.setExecutor(Executors.newFixedThreadPool(4));

		// API endpoints
		server.createContext("/api/effects", this::handleEffects);
		server.createContext("/api/config", this::handleConfig);
		server.createContext("/api/twitch/status", this::handleTwitchStatus);
		server.createContext("/api/twitch/connect", this::handleTwitchConnect);
		server.createContext("/api/twitch/rewards", this::handleTwitchRewards);
		server.createContext("/api/test/", this::handleTestEffect);
		server.createContext("/api/mappings", this::handleMappings);
		server.createContext("/oauth/callback", this::handleOAuthCallback);

		// Static files (SPA)
		server.createContext("/", this::handleStaticFile);

		server.start();
		LOGGER.info("[emma-twitch] Web GUI started on port {}", port);
	}

	public void stop() {
		if (server != null) {
			server.stop(1);
			LOGGER.info("[emma-twitch] Web GUI stopped");
		}
	}

	// --- API Handlers ---

	private void handleEffects(HttpExchange exchange) throws IOException {
		addCorsHeaders(exchange);
		if (handlePreflight(exchange)) return;

		if ("GET".equals(exchange.getRequestMethod())) {
			Set<String> effectIds = dispatcher.getRegisteredEffects();
			List<Map<String, Object>> effects = new ArrayList<>();
			for (String id : effectIds) {
				Map<String, Object> effect = new LinkedHashMap<>();
				effect.put("id", id);
				effect.put("enabled", !config.disabledEffects.contains(id));
				effect.put("cooldown", config.effectCooldowns.getOrDefault(id, config.globalCooldownSeconds));
				effects.add(effect);
			}
			sendJson(exchange, 200, effects);
		} else if ("POST".equals(exchange.getRequestMethod())) {
			var body = MAPPER.readTree(exchange.getRequestBody());
			String effectId = body.get("id").asText();
			if (body.has("enabled")) {
				if (body.get("enabled").asBoolean()) {
					config.disabledEffects.remove(effectId);
				} else {
					config.disabledEffects.add(effectId);
				}
			}
			if (body.has("cooldown")) {
				config.effectCooldowns.put(effectId, body.get("cooldown").asInt());
			}
			saveConfig();
			sendJson(exchange, 200, Map.of("ok", true));
		} else {
			exchange.sendResponseHeaders(405, -1);
		}
	}

	private void handleConfig(HttpExchange exchange) throws IOException {
		addCorsHeaders(exchange);
		if (handlePreflight(exchange)) return;

		if ("GET".equals(exchange.getRequestMethod())) {
			Map<String, Object> cfg = new LinkedHashMap<>();
			cfg.put("global_cooldown_seconds", config.globalCooldownSeconds);
			cfg.put("web_port", config.webPort);
			cfg.put("twitch_configured", config.twitch.isConfigured());
			cfg.put("channel_name", config.twitch.channelName);
			cfg.put("reward_mappings", config.rewardMappings);
			cfg.put("bit_tiers", config.bitTiers);
			cfg.put("disabled_effects_count", config.disabledEffects.size());
			sendJson(exchange, 200, cfg);
		} else if ("POST".equals(exchange.getRequestMethod())) {
			var body = MAPPER.readTree(exchange.getRequestBody());
			if (body.has("global_cooldown_seconds"))
				config.globalCooldownSeconds = body.get("global_cooldown_seconds").asInt();
			saveConfig();
			sendJson(exchange, 200, Map.of("ok", true));
		} else {
			exchange.sendResponseHeaders(405, -1);
		}
	}

	private void handleTwitchStatus(HttpExchange exchange) throws IOException {
		addCorsHeaders(exchange);
		if (handlePreflight(exchange)) return;

		var mod = com.emma.twitch.EmmaTwitchMod.getInstance();
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("configured", config.twitch.isConfigured());
		status.put("connected", mod.getEventSubClient() != null && mod.getEventSubClient().isConnected());
		status.put("channel_name", config.twitch.channelName);
		status.put("effects_registered", dispatcher.getRegisteredEffects().size());
		sendJson(exchange, 200, status);
	}

	private void handleTestEffect(HttpExchange exchange) throws IOException {
		addCorsHeaders(exchange);
		if (handlePreflight(exchange)) return;

		if ("POST".equals(exchange.getRequestMethod())) {
			String path = exchange.getRequestURI().getPath();
			String effectId = path.substring("/api/test/".length());
			dispatcher.testEffect(effectId, "WebGUI-Test");
			sendJson(exchange, 200, Map.of("ok", true, "effect", effectId));
		} else {
			exchange.sendResponseHeaders(405, -1);
		}
	}

	private void handleMappings(HttpExchange exchange) throws IOException {
		addCorsHeaders(exchange);
		if (handlePreflight(exchange)) return;

		if ("POST".equals(exchange.getRequestMethod())) {
			var body = MAPPER.readTree(exchange.getRequestBody());
			if (body.has("reward_mappings")) {
				config.rewardMappings.clear();
				body.get("reward_mappings").fields().forEachRemaining(entry -> {
					TwitchConfig.RewardMapping mapping = new TwitchConfig.RewardMapping();
					mapping.effectId = entry.getValue().get("effect_id").asText();
					mapping.enabled = entry.getValue().path("enabled").asBoolean(true);
					config.rewardMappings.put(entry.getKey(), mapping);
				});
			}
			if (body.has("bit_tiers")) {
				config.bitTiers.clear();
				body.get("bit_tiers").forEach(tierNode -> {
					TwitchConfig.BitTier tier = new TwitchConfig.BitTier();
					tier.minBits = tierNode.get("min_bits").asInt();
					tier.effectId = tierNode.get("effect_id").asText();
					tier.enabled = tierNode.path("enabled").asBoolean(true);
					config.bitTiers.add(tier);
				});
			}
			saveConfig();
			sendJson(exchange, 200, Map.of("ok", true));
		} else {
			exchange.sendResponseHeaders(405, -1);
		}
	}

	// --- OAuth & Rewards Handlers ---

	private static final String REQUIRED_SCOPES = "channel:read:redemptions+channel:manage:redemptions+bits:read+channel:read:subscriptions";

	private void handleTwitchConnect(HttpExchange exchange) throws IOException {
		addCorsHeaders(exchange);
		if (handlePreflight(exchange)) return;

		if ("GET".equals(exchange.getRequestMethod())) {
			// Return the OAuth URL for the user to visit
			String redirectUri = "http://localhost:" + config.webPort + "/oauth/callback";
			String authUrl = "https://id.twitch.tv/oauth2/authorize"
				+ "?client_id=" + config.twitch.clientId
				+ "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8)
				+ "&response_type=code"
				+ "&scope=" + REQUIRED_SCOPES
				+ "&force_verify=true";
			sendJson(exchange, 200, Map.of("auth_url", authUrl));
		} else {
			exchange.sendResponseHeaders(405, -1);
		}
	}

	private void handleOAuthCallback(HttpExchange exchange) throws IOException {
		// Parse the authorization code from the query string
		String query = exchange.getRequestURI().getQuery();
		if (query == null || !query.contains("code=")) {
			String html = "<html><body><h2>Authorization failed</h2><p>No code received. Check the Twitch authorization.</p></body></html>";
			exchange.getResponseHeaders().set("Content-Type", "text/html");
			exchange.sendResponseHeaders(400, html.length());
			exchange.getResponseBody().write(html.getBytes());
			exchange.getResponseBody().close();
			return;
		}

		String code = null;
		for (String param : query.split("&")) {
			if (param.startsWith("code=")) {
				code = param.substring(5);
				break;
			}
		}

		if (code == null) {
			String html = "<html><body><h2>Authorization failed</h2></body></html>";
			exchange.getResponseHeaders().set("Content-Type", "text/html");
			exchange.sendResponseHeaders(400, html.length());
			exchange.getResponseBody().write(html.getBytes());
			exchange.getResponseBody().close();
			return;
		}

		// Exchange code for tokens
		String redirectUri = "http://localhost:" + config.webPort + "/oauth/callback";
		String body = "client_id=" + config.twitch.clientId
			+ "&client_secret=" + config.twitch.clientSecret
			+ "&code=" + code
			+ "&grant_type=authorization_code"
			+ "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8);

		try {
			var httpClient = java.net.http.HttpClient.newHttpClient();
			var request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://id.twitch.tv/oauth2/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
				.build();

			var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 200) {
				var json = MAPPER.readTree(response.body());
				config.twitch.accessToken = json.get("access_token").asText();
				if (json.has("refresh_token")) {
					config.twitch.refreshToken = json.get("refresh_token").asText();
				}

				// Get user info to confirm channel
				var userReq = java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create("https://api.twitch.tv/helix/users"))
					.header("Authorization", "Bearer " + config.twitch.accessToken)
					.header("Client-Id", config.twitch.clientId)
					.GET().build();
				var userResp = httpClient.send(userReq, java.net.http.HttpResponse.BodyHandlers.ofString());
				if (userResp.statusCode() == 200) {
					var userData = MAPPER.readTree(userResp.body()).get("data").get(0);
					config.twitch.channelId = userData.get("id").asText();
					config.twitch.channelName = userData.get("display_name").asText();
				}

				saveConfig();
				LOGGER.info("[emma-twitch] OAuth completed for channel: {}", config.twitch.channelName);

				// Reconnect EventSub with new token
				var mod = com.emma.twitch.EmmaTwitchMod.getInstance();
				if (mod.getEventSubClient() != null) {
					mod.getEventSubClient().disconnect();
					mod.getEventSubClient().connect();
				}

				String html = "<html><body style='background:#1a1a2e;color:#e0e0e0;font-family:sans-serif;text-align:center;padding-top:100px'>"
					+ "<h2 style='color:#9146ff'>Connected to Twitch!</h2>"
					+ "<p>Channel: " + config.twitch.channelName + "</p>"
					+ "<p>You can close this tab and return to the control panel.</p></body></html>";
				exchange.getResponseHeaders().set("Content-Type", "text/html");
				exchange.sendResponseHeaders(200, html.length());
				exchange.getResponseBody().write(html.getBytes());
				exchange.getResponseBody().close();
			} else {
				LOGGER.error("[emma-twitch] Token exchange failed: {}", response.body());
				String html = "<html><body><h2>Token exchange failed</h2><pre>" + response.body() + "</pre></body></html>";
				exchange.getResponseHeaders().set("Content-Type", "text/html");
				exchange.sendResponseHeaders(500, html.length());
				exchange.getResponseBody().write(html.getBytes());
				exchange.getResponseBody().close();
			}
		} catch (Exception e) {
			LOGGER.error("[emma-twitch] OAuth callback error", e);
			String html = "<html><body><h2>Error</h2><pre>" + e.getMessage() + "</pre></body></html>";
			exchange.getResponseHeaders().set("Content-Type", "text/html");
			exchange.sendResponseHeaders(500, html.length());
			exchange.getResponseBody().write(html.getBytes());
			exchange.getResponseBody().close();
		}
	}

	private void handleTwitchRewards(HttpExchange exchange) throws IOException {
		addCorsHeaders(exchange);
		if (handlePreflight(exchange)) return;

		if (!config.twitch.isConfigured()) {
			sendJson(exchange, 200, Map.of("rewards", List.of(), "error", "Twitch not configured"));
			return;
		}

		try {
			var httpClient = java.net.http.HttpClient.newHttpClient();
			var request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("https://api.twitch.tv/helix/channel_points/custom_rewards?broadcaster_id=" + config.twitch.channelId))
				.header("Authorization", "Bearer " + config.twitch.accessToken)
				.header("Client-Id", config.twitch.clientId)
				.GET().build();

			var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 200) {
				var json = MAPPER.readTree(response.body());
				List<Map<String, Object>> rewards = new ArrayList<>();
				json.get("data").forEach(r -> {
					Map<String, Object> reward = new LinkedHashMap<>();
					reward.put("id", r.get("id").asText());
					reward.put("title", r.get("title").asText());
					reward.put("cost", r.get("cost").asInt());
					reward.put("is_enabled", r.get("is_enabled").asBoolean());
					reward.put("prompt", r.has("prompt") ? r.get("prompt").asText() : "");
					// Check if already mapped
					String rewardId = r.get("id").asText();
					if (config.rewardMappings.containsKey(rewardId)) {
						reward.put("mapped_effect", config.rewardMappings.get(rewardId).effectId);
					}
					rewards.add(reward);
				});
				sendJson(exchange, 200, Map.of("rewards", rewards));
			} else {
				sendJson(exchange, 200, Map.of("rewards", List.of(), "error", "Twitch API returned " + response.statusCode()));
			}
		} catch (Exception e) {
			sendJson(exchange, 200, Map.of("rewards", List.of(), "error", e.getMessage()));
		}
	}

	// --- Static File Handler ---

	private void handleStaticFile(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		if ("/".equals(path)) path = "/index.html";

		// Serve from JAR resources
		String resourcePath = "/web" + path;
		try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
			if (is == null) {
				// SPA fallback: serve index.html for any unknown path
				try (InputStream fallback = getClass().getResourceAsStream("/web/index.html")) {
					if (fallback == null) {
						String msg = "Not Found";
						exchange.sendResponseHeaders(404, msg.length());
						exchange.getResponseBody().write(msg.getBytes());
						exchange.getResponseBody().close();
						return;
					}
					byte[] data = fallback.readAllBytes();
					exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
					exchange.sendResponseHeaders(200, data.length);
					exchange.getResponseBody().write(data);
					exchange.getResponseBody().close();
				}
				return;
			}
			byte[] data = is.readAllBytes();
			exchange.getResponseHeaders().set("Content-Type", getMimeType(path));
			exchange.sendResponseHeaders(200, data.length);
			exchange.getResponseBody().write(data);
			exchange.getResponseBody().close();
		}
	}

	// --- Helpers ---

	private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] json = MAPPER.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, json.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(json);
		}
	}

	private void addCorsHeaders(HttpExchange exchange) {
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
	}

	private boolean handlePreflight(HttpExchange exchange) throws IOException {
		if ("OPTIONS".equals(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(204, -1);
			return true;
		}
		return false;
	}

	private void saveConfig() {
		try {
			config.save(configPath);
		} catch (IOException e) {
			LOGGER.error("[emma-twitch] Failed to save config", e);
		}
	}

	private String getMimeType(String path) {
		if (path.endsWith(".html")) return "text/html; charset=utf-8";
		if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
		if (path.endsWith(".css")) return "text/css; charset=utf-8";
		if (path.endsWith(".json")) return "application/json";
		if (path.endsWith(".png")) return "image/png";
		if (path.endsWith(".svg")) return "image/svg+xml";
		if (path.endsWith(".ico")) return "image/x-icon";
		return "application/octet-stream";
	}
}
