package live.crowdcontrol.cc4j.websocket.http;

import live.crowdcontrol.cc4j.websocket.payload.CCName;

public class CustomEffect {
	private final CCName name;
	private final int price;
	private final String description;
	private final java.util.List<String> category;
	private final String image;
	private final boolean inactive;
	private final CustomEffectDuration duration;

	CustomEffect(CCName name, int price, String description, java.util.List<String> category,
			String image, boolean inactive, CustomEffectDuration duration) {
		this.name = name;
		this.price = price;
		this.description = description;
		this.category = category;
		this.image = image;
		this.inactive = inactive;
		this.duration = duration;
	}

	public CCName name() { return name; }
	public int price() { return price; }
	public String description() { return description; }
	public java.util.List<String> category() { return category; }
	public String image() { return image; }
	public boolean inactive() { return inactive; }
	public CustomEffectDuration duration() { return duration; }
}
