package live.crowdcontrol.cc4j.websocket.data;

import java.util.UUID;

public class CCInstantEffectResponse implements CCEffectResponse {
	private final UUID requestId;
	private final ResponseStatus status;
	private final String message;

	public CCInstantEffectResponse(UUID requestId, ResponseStatus status) {
		this(requestId, status, null);
	}

	public CCInstantEffectResponse(UUID requestId, ResponseStatus status, String message) {
		this.requestId = requestId;
		this.status = status;
		this.message = message;
	}

	@Override
	public UUID getRequestId() { return requestId; }

	@Override
	public ResponseStatus getStatus() { return status; }

	public String getMessage() { return message; }
}
