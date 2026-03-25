package live.crowdcontrol.cc4j.websocket.data;

import java.util.List;

public class CCEffectReport {
	private final IdentifierType type;
	private final ReportStatus status;
	private final List<String> effectIds;

	public CCEffectReport(IdentifierType type, ReportStatus status, List<String> effectIds) {
		this.type = type;
		this.status = status;
		this.effectIds = effectIds;
	}

	public IdentifierType getType() { return type; }
	public ReportStatus getStatus() { return status; }
	public List<String> getEffectIds() { return effectIds; }
}
