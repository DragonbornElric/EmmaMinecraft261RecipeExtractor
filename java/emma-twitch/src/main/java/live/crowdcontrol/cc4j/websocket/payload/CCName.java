package live.crowdcontrol.cc4j.websocket.payload;

public class CCName {
	private final String displayName;

	public CCName(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() { return displayName; }

	public String computeSortValue() {
		return displayName != null ? displayName.toLowerCase() : "";
	}
}
