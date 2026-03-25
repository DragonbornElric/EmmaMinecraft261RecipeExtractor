package live.crowdcontrol.cc4j.websocket.data;

import java.util.UUID;

public class CCTimedEffectResponse implements CCEffectResponse {
	private final UUID requestId;
	private final ResponseStatus status;
	private final long durationMillis;

	public CCTimedEffectResponse(UUID requestId, ResponseStatus status, long durationMillis) {
		this.requestId = requestId;
		this.status = status;
		this.durationMillis = durationMillis;
	}

	@Override
	public UUID getRequestId() { return requestId; }

	@Override
	public ResponseStatus getStatus() { return status; }

	public long getDurationMillis() { return durationMillis; }
}
