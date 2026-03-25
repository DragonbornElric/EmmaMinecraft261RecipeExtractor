package live.crowdcontrol.cc4j.websocket.payload;

import java.util.UUID;

public class PublicEffectPayload {
	private final UUID requestId;
	private final int quantity;
	private final boolean anonymous;
	private final CCUserRecord requester;
	private final live.crowdcontrol.cc4j.IUserRecord target;
	private final EffectInfo effect;

	public PublicEffectPayload(UUID requestId, String effectId, int quantity, long durationMillis,
			boolean anonymous, CCUserRecord requester, live.crowdcontrol.cc4j.IUserRecord target) {
		this.requestId = requestId;
		this.quantity = quantity;
		this.anonymous = anonymous;
		this.requester = requester;
		this.target = target;
		this.effect = new EffectInfo(effectId, durationMillis);
	}

	public UUID getRequestId() { return requestId; }
	public int getQuantity() { return quantity; }
	public boolean isAnonymous() { return anonymous; }
	public CCUserRecord getRequester() { return requester; }
	public live.crowdcontrol.cc4j.IUserRecord getTarget() { return target; }
	public EffectInfo getEffect() { return effect; }

	public static class EffectInfo {
		private final String effectId;
		private final long durationMillis;

		public EffectInfo(String effectId, long durationMillis) {
			this.effectId = effectId;
			this.durationMillis = durationMillis;
		}

		public String getEffectId() { return effectId; }
		public long getDurationMillis() { return durationMillis; }
	}
}
