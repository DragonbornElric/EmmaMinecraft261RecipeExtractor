package com.emma.twitch.eventsub;

/**
 * Parsed notification from Twitch EventSub.
 */
public class EventSubNotification {
	/** Event type: "channel.channel_points_custom_reward_redemption.add" or "channel.cheer" */
	public String type;

	/** Twitch display name of the viewer */
	public String userName;

	/** Twitch user ID */
	public String userId;

	// Channel point redemption fields
	/** Custom reward ID (for channel point redemptions) */
	public String rewardId;

	/** Custom reward title */
	public String rewardTitle;

	/** Redemption ID (for fulfilling/cancelling) */
	public String redemptionId;

	// Cheer fields
	/** Number of bits cheered */
	public int bits;

	/** Whether the cheer was anonymous */
	public boolean isAnonymous;
}
