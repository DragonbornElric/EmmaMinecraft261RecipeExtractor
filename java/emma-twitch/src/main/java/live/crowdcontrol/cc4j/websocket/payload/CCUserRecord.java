package live.crowdcontrol.cc4j.websocket.payload;

public class CCUserRecord implements IUserRecord, live.crowdcontrol.cc4j.IUserRecord {
	private final String id;
	private final String name;
	private final ProfileType profileType;
	private final String originId;
	private final String image;

	public CCUserRecord(String id, String name, ProfileType profileType, String originId, String image) {
		this.id = id;
		this.name = name;
		this.profileType = profileType;
		this.originId = originId;
		this.image = image;
	}

	@Override
	public String getId() { return id; }
	public String getName() { return name; }
	public ProfileType getProfileType() { return profileType; }
	public String getOriginId() { return originId; }
	public String getImage() { return image; }
}
