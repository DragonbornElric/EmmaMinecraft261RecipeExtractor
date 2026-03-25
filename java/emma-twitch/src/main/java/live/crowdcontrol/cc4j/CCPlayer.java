package live.crowdcontrol.cc4j;

import live.crowdcontrol.cc4j.websocket.UserToken;
import live.crowdcontrol.cc4j.websocket.data.CCEffectReport;
import live.crowdcontrol.cc4j.websocket.data.CCEffectResponse;
import live.crowdcontrol.cc4j.websocket.http.CustomEffectsOperation;

import java.util.List;
import java.util.UUID;

/**
 * Stub for cc4j CCPlayer. Represents a connected player session.
 * In our fork, this is backed by the server player + Twitch viewer context.
 */
public class CCPlayer {
	private final UUID uuid;
	private UserToken userToken;
	private String gameSessionId;
	private String authUrl;
	private final EventManager eventManager = new EventManager();

	public CCPlayer(UUID uuid) {
		this.uuid = uuid;
	}

	public UUID getUuid() { return uuid; }

	public UserToken getUserToken() { return userToken; }
	public void setUserToken(UserToken userToken) { this.userToken = userToken; }

	public String getGameSessionId() { return gameSessionId; }
	public void setGameSessionId(String gameSessionId) { this.gameSessionId = gameSessionId; }

	public String getAuthUrl() { return authUrl; }
	public void setAuthUrl(String authUrl) { this.authUrl = authUrl; }

	public EventManager getEventManager() { return eventManager; }

	public void sendResponse(CCEffectResponse response) {
		// In our fork, this is a no-op or logs the response.
		// The original CC sends this back to the cloud service.
	}

	public void sendReport(CCEffectReport[] reports) {
		// No-op in our fork
	}

	public void setCustomEffects(List<CustomEffectsOperation> ops) {
		// No-op in our fork
	}

	public void startSession(CCEffectReport[] reports) {
		// No-op in our fork
	}

	public static class EventManager {
		public <T> void registerEventConsumer(CCEventType type, java.util.function.Consumer<T> handler) {
			// No-op in our fork
		}
		public void registerEventRunnable(CCEventType type, Runnable handler) {
			// No-op in our fork
		}
	}
}
