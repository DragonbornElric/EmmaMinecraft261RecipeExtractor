package live.crowdcontrol.cc4j;

/**
 * Re-export of IUserRecord at the cc4j root level.
 * Some CC code imports it from here rather than websocket.payload.
 */
public interface IUserRecord {
	String getId();
}
