# Twitch Affiliate + Viewer Interaction Setup Guide

Channel: **Elric_Heart**

## Step 1: Reach Affiliate

Twitch Affiliate requirements (all within 30 days):
- 50 followers
- 7 unique broadcast days
- 8 hours total streamed
- Average of 3 viewers

Track progress: https://dashboard.twitch.tv/achievements

Once eligible, Twitch emails an invite. Accept it in the dashboard under Settings → Affiliate Onboarding.

## Step 2: Enable Channel Points

Channel Points unlock automatically with Affiliate.

1. Go to https://dashboard.twitch.tv/viewer-rewards/channel-points
2. Toggle **Enable Channel Points** on
3. Set a custom channel points icon if desired
4. Configure the earning rate (default is fine to start)

## Step 3: Create Custom Rewards

Go to: https://dashboard.twitch.tv/viewer-rewards/channel-points/rewards

Click **Add New Custom Reward** for each effect. Suggested starter set:

### Cheap & Fun (500–1500 pts)
| Title | Cost | Effect ID | Description for viewers |
|---|---|---|---|
| Spawn a Zombie | 500 | `summon_entity_zombie` | Spawns a zombie near the player |
| Random Potion | 1000 | `potion` | Applies a random potion effect |
| Drop Their Item | 1000 | `drop_item` | Forces the player to drop what they're holding |
| Plant a Tree | 500 | `plant_tree` | Plants a random tree nearby |
| Dinnerbone! | 750 | `dinnerbone` | Flips an entity upside down |
| Play a Sound | 500 | `sound` | Plays a random sound |
| Place Flowers | 500 | `flower` | Spawns flowers around the player |

### Mid-Tier (2000–4000 pts)
| Title | Cost | Effect ID | Description for viewers |
|---|---|---|---|
| YEET! | 2000 | `fling` | Launches the player into the air |
| Flip Gravity | 3000 | `gravity` | Reverses gravity temporarily |
| Steal an Item | 2500 | `delete_random_item` | Deletes a random item from inventory |
| Freeze! | 2500 | `freeze` | Freezes the player in place |
| Lootbox | 3000 | `lootbox` | Gives a random loot item |
| Enchant Something | 2000 | `enchantment` | Adds a random enchantment |
| Set Time to Night | 2000 | `set_time_night` | Changes time to night |
| Charged Creeper | 3500 | `charged_creeper` | Spawns a charged creeper nearby |

### Expensive & Chaotic (5000+ pts)
| Title | Cost | Effect ID | Description for viewers |
|---|---|---|---|
| EXPLODE | 5000 | `explode` | Explodes at the player's location |
| Lava Floor | 5000 | `lava` | Places lava around the player |
| Clear Inventory | 10000 | `clear_inventory` | Clears the player's entire inventory |
| Entity Chaos | 7500 | `entity_chaos` | Spawns a swarm of random mobs |
| Kill | 15000 | `kill` | Instantly kills the player |
| Gravel Rain | 5000 | `gravel` | Drops gravel from above |

### Helpful Rewards (let viewers be nice too)
| Title | Cost | Effect ID | Description for viewers |
|---|---|---|---|
| Full Heal | 2000 | `full_heal` | Heals the player to full health |
| Feed the Player | 1500 | `full_feed` | Fills the hunger bar |
| Give XP | 1000 | `exp_add` | Grants bonus XP |
| Flight Mode | 5000 | `flight` | Grants temporary flight |

### Reward Settings Tips
- **Require Viewer to Enter Text**: Off (not needed, effect is automatic)
- **Cooldown**: Set 2-5 min for dangerous effects, 30s for mild ones (the mod also has its own cooldown system)
- **Max Per Stream**: Consider limiting destructive ones (kill, clear inventory) to 3-5 per stream
- **Max Per User Per Stream**: 1-2 for expensive/destructive rewards
- **Skip Reward Requests Queue**: On (effects fire immediately, no manual approval needed)

## Step 4: Authorize the Mod

After the server is running with emma-twitch:

1. Open the web GUI: `http://<server-ip>:8780`
2. Go to **Settings** tab
3. Click **Connect to Twitch**
4. Authorize with your Elric_Heart account
5. This grants the mod `channel:read:redemptions`, `channel:manage:redemptions`, and `bits:read` scopes

## Step 5: Map Rewards to Effects

1. In the web GUI, go to the **Twitch Rewards** tab
2. It will show all your custom rewards from Step 3
3. Use the dropdown next to each reward to pick the matching effect ID
4. Click **Save**

## Step 6: Configure Bit Tiers

Bits also unlock with Affiliate. In the web GUI **Bit Tiers** tab, set up tiers:

| Min Bits | Effect | Rationale |
|---|---|---|
| 1 | `sound` | Any cheer plays a sound |
| 50 | `summon_entity_zombie` | Cheap fun |
| 100 | `fling` | Gets a laugh |
| 250 | `lava` | Starting to get serious |
| 500 | `explode` | Big moment |
| 1000 | `clear_inventory` | Maximum chaos |

The highest matching tier triggers. So 75 bits = zombie (50 tier), 300 bits = lava (250 tier).

## Step 7: Test

- Use the **Test** button next to any effect in the Effects tab to fire it manually
- Have a friend redeem a channel point reward to verify the full flow
- Twitch CLI can mock events: `twitch event trigger channel.channel_points_custom_reward_redemption.add`

## Step 8: Stream Overlay (Optional)

Consider adding an overlay showing recent triggers. The mod broadcasts to chat:
```
[Twitch] ViewerName triggered EffectName!
```
This shows up in the Minecraft chat on-screen automatically.

## Notes

- Channel Points are earned by viewers watching your stream (default ~10 pts/5 min)
- New channels start with low point balances, so keep initial costs reasonable
- You can adjust costs anytime based on how fast viewers earn points
- Bits cost real money ($1.40 per 100 bits), so viewers treat them more seriously
- The mod's global cooldown (default 30s) prevents spam regardless of reward settings
- Dangerous effects have extra cooldowns in the config (explode: 120s, kill: 300s)
