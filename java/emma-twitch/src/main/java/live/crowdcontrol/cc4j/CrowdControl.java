package live.crowdcontrol.cc4j;

import java.nio.file.Path;
import java.util.*;

/**
 * Stub for the cc4j CrowdControl class. In the original CC, this connects to
 * pubsub.crowdcontrol.live. In our fork, this is a local effect registry that
 * our Twitch EventSub client dispatches to.
 */
public class CrowdControl {
	public static final long QUEUE_DURATION = 30000L;

	private final Map<String, CCEffect> effects = new LinkedHashMap<>();
	private final Map<UUID, CCPlayer> players = new HashMap<>();
	private boolean closed = false;

	public CrowdControl(String gameName, String gameDisplayName, String appId, String appSecret, Path dataFolder) {
		// No cloud connection — this is our local stub
	}

	public boolean addEffect(String name, CCEffect command) {
		effects.put(name, command);
		return true;
	}

	public CCEffect getEffect(String name) {
		return effects.get(name);
	}

	public Map<String, CCEffect> getEffects() {
		return Collections.unmodifiableMap(effects);
	}

	public CCPlayer addPlayer(UUID uuid) {
		return players.computeIfAbsent(uuid, CCPlayer::new);
	}

	public CCPlayer getPlayer(UUID uuid) {
		return players.get(uuid);
	}

	public Collection<CCPlayer> getPlayers() {
		return Collections.unmodifiableCollection(players.values());
	}

	public Collection<UUID> getPlayerIds(String userId) {
		// In our fork, we don't map CC user IDs to UUIDs
		return Collections.emptyList();
	}

	public void removePlayer(UUID uuid) {
		players.remove(uuid);
	}

	public boolean isPlayerEffectActive(String effectId, UUID playerUuid) {
		return false; // Simplified — can be enhanced later
	}

	public GamePack getGamePack() {
		return new GamePack(effects);
	}

	public void close() {
		closed = true;
	}

	public boolean isClosed() {
		return closed;
	}

	public static class GamePack {
		private final Map<String, CCEffect> effects;
		GamePack(Map<String, CCEffect> effects) { this.effects = effects; }

		public Effects getEffects() { return new Effects(effects); }

		public static class Effects {
			private final Map<String, CCEffect> game;
			Effects(Map<String, CCEffect> game) { this.game = game; }
			public Map<String, ?> getGame() { return game; }
		}
	}
}
