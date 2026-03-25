package live.crowdcontrol.cc4j;

public class CCMessage {
	private final String message;
	private final Level level;

	public CCMessage(String message, Level level) {
		this.message = message;
		this.level = level;
	}

	public String message() { return message; }
	public Level level() { return level; }

	public enum Level {
		ERROR,
		WARN,
		INFO
	}
}
