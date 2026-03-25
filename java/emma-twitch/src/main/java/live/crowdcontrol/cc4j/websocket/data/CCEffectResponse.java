package live.crowdcontrol.cc4j.websocket.data;

import java.util.UUID;

public interface CCEffectResponse {
	UUID getRequestId();
	ResponseStatus getStatus();
	default String getMessage() { return getStatus().name(); }
}
