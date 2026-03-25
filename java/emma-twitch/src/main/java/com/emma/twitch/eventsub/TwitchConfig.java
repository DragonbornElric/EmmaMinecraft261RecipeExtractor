package com.emma.twitch.eventsub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * JSON config for Twitch integration. Stored at config/emma-twitch.json on the server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TwitchConfig {
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	@JsonProperty("twitch")
	public TwitchAuth twitch = new TwitchAuth();

	@JsonProperty("reward_mappings")
	public Map<String, RewardMapping> rewardMappings = new LinkedHashMap<>();

	@JsonProperty("bit_tiers")
	public List<BitTier> bitTiers = new ArrayList<>();

	@JsonProperty("global_cooldown_seconds")
	public int globalCooldownSeconds = 30;

	@JsonProperty("effect_cooldowns")
	public Map<String, Integer> effectCooldowns = new LinkedHashMap<>();

	@JsonProperty("disabled_effects")
	public Set<String> disabledEffects = new LinkedHashSet<>();

	@JsonProperty("web_port")
	public int webPort = 8780;

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class TwitchAuth {
		@JsonProperty("client_id")
		public String clientId = "";

		@JsonProperty("client_secret")
		public String clientSecret = "";

		@JsonProperty("access_token")
		public String accessToken = "";

		@JsonProperty("refresh_token")
		public String refreshToken = "";

		@JsonProperty("channel_id")
		public String channelId = "";

		@JsonProperty("channel_name")
		public String channelName = "";

		public boolean isConfigured() {
			return !clientId.isEmpty() && !accessToken.isEmpty() && !channelId.isEmpty();
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class RewardMapping {
		@JsonProperty("effect_id")
		public String effectId = "";

		@JsonProperty("enabled")
		public boolean enabled = true;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class BitTier implements Comparable<BitTier> {
		@JsonProperty("min_bits")
		public int minBits = 100;

		@JsonProperty("effect_id")
		public String effectId = "";

		@JsonProperty("enabled")
		public boolean enabled = true;

		@Override
		public int compareTo(BitTier other) {
			return Integer.compare(other.minBits, this.minBits); // descending
		}
	}

	public static TwitchConfig load(Path path) throws IOException {
		if (Files.exists(path)) {
			return MAPPER.readValue(path.toFile(), TwitchConfig.class);
		}
		return new TwitchConfig();
	}

	public void save(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		MAPPER.writeValue(path.toFile(), this);
	}
}
