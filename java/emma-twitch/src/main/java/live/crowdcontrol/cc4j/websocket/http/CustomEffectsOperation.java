package live.crowdcontrol.cc4j.websocket.http;

import java.util.Map;

public class CustomEffectsOperation {
	private final String operation;
	private final Map<String, CustomEffect> effects;

	public CustomEffectsOperation(String operation, Map<String, CustomEffect> effects) {
		this.operation = operation;
		this.effects = effects;
	}

	public String getOperation() { return operation; }
	public Map<String, CustomEffect> getEffects() { return effects; }
}
