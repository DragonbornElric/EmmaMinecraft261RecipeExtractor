package live.crowdcontrol.cc4j.websocket;

public class UserToken implements live.crowdcontrol.cc4j.IUserRecord {
	private final String name;
	private final String id;
	private final String originId;
	private final Profile profile;

	public UserToken(String name, String id, String originId, String profileValue) {
		this.name = name;
		this.id = id;
		this.originId = originId;
		this.profile = new Profile(profileValue);
	}

	public String getName() { return name; }
	public String getId() { return id; }
	public String getOriginId() { return originId; }
	public Profile getProfile() { return profile; }

	public static class Profile {
		private final String value;
		public Profile(String value) { this.value = value; }
		public String getValue() { return value; }
	}
}
