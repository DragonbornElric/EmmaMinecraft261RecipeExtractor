package live.crowdcontrol.cc4j.websocket.http;

import live.crowdcontrol.cc4j.websocket.payload.CCName;
import java.util.List;

public class CustomEffectBuilder {
	private CCName name;
	private int price;
	private String description;
	private List<String> category;
	private String image;
	private boolean inactive;
	private CustomEffectDuration duration;

	private CustomEffectBuilder() {}

	public static CustomEffectBuilder builder() { return new CustomEffectBuilder(); }

	public CustomEffectBuilder name(CCName name) { this.name = name; return this; }
	public CustomEffectBuilder price(int price) { this.price = price; return this; }
	public CustomEffectBuilder description(String description) { this.description = description; return this; }
	public CustomEffectBuilder category(List<String> category) { this.category = category; return this; }
	public CustomEffectBuilder image(String image) { this.image = image; return this; }
	public CustomEffectBuilder inactive(boolean inactive) { this.inactive = inactive; return this; }
	public CustomEffectBuilder duration(CustomEffectDuration duration) { this.duration = duration; return this; }

	public CustomEffect build() {
		return new CustomEffect(name, price, description, category, image, inactive, duration);
	}
}
