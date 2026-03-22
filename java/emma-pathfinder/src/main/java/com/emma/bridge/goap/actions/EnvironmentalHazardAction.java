package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalRunAway;
import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Escape environmental hazards: lava, fire, dragon breath, powder snow,
 * magma blocks, wither roses, sweet berry bushes, drowning, portal stuck.
 *
 * Score: Very high (survive × 1.0) when actively taking damage from environment.
 * Category: safety
 */
public class EnvironmentalHazardAction extends GoapAction {

    private boolean active = false;
    private String hazardType = "none";

    @Override
    public String getName() {
        return "EnvironmentalHazard";
    }

    @Override
    public String personalityCategory() {
        return "safety";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return isInHazard(state);
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (!isInHazard(state)) return 0f;

        // Base urgency from survive goal
        float survive = goals.getGoalPriority("survive");
        if (survive <= 0) survive = 8.0f; // default high priority

        float urgency = 1.0f;

        // Scale urgency by danger level
        if (state.inLava) {
            urgency = 1.5f; // lava = instant death risk
            hazardType = "lava";
        } else if (state.onFire) {
            urgency = 1.2f;
            hazardType = "fire";
        } else if (state.touchingDragonBreath) {
            urgency = 1.3f;
            hazardType = "dragon_breath";
        } else if (state.airSupply < state.maxAir / 3) {
            urgency = 1.4f; // almost drowning
            hazardType = "drowning";
        } else if (state.inPowderSnow) {
            urgency = 0.8f;
            hazardType = "powder_snow";
        } else if (state.hasDamageOverTimeEffect()) {
            // Poison/Wither are continuous environmental damage
            urgency = 0.6f;
            if (state.getEffectAmplifier("minecraft:wither") > 0
                    || state.getEffectAmplifier("minecraft:poison") > 0) {
                urgency = 0.9f; // level II+ is much more dangerous
            }
            hazardType = "status_effect_dot";
        } else {
            // Check for block hazards (magma, wither rose, berry bush)
            hazardType = checkBlockHazards(state);
            if (hazardType.equals("none")) return 0f;
            urgency = 0.7f;
        }

        // Health-scaled urgency: low health + hazard is far more urgent
        // Multiplier: 1.0 at full health, 2.0 at zero health
        float healthScaler = 1.0f + (1.0f - state.health / state.maxHealth);
        urgency *= healthScaler;

        return survive * urgency;
    }

    @Override
    public void execute(Minecraft client) {
        active = true;
        escape();
    }

    @Override
    public void tick(Minecraft client) {
        if (active) {
            escape();
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        active = false;
        DirectInput.setForward(false);
        DirectInput.setJumping(false);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    private boolean isInHazard(WorldState state) {
        if (state.inLava || state.onFire || state.inPowderSnow || state.touchingDragonBreath) {
            return true;
        }
        if (state.airSupply < state.maxAir / 3 && state.inWater) {
            return true;
        }
        if (state.hasDamageOverTimeEffect()) {
            return true;
        }
        return !checkBlockHazards(state).equals("none");
    }

    private String checkBlockHazards(WorldState state) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "none";

        BlockPos feet = client.player.blockPosition();
        BlockPos[] checkPositions = {
                feet, feet.below(), feet.north(), feet.south(), feet.east(), feet.west()
        };

        for (BlockPos pos : checkPositions) {
            BlockState block = client.level.getBlockState(pos);
            if (block.is(Blocks.FIRE) || block.is(Blocks.SOUL_FIRE)) return "fire";
            if (block.is(Blocks.MAGMA_BLOCK)) return "magma";
            if (block.is(Blocks.WITHER_ROSE)) return "wither_rose";
            if (block.is(Blocks.SWEET_BERRY_BUSH)) return "sweet_berry";
        }
        return "none";
    }

    private void escape() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        // For status effects: no movement escape possible
        // Score signals danger to other actions via debug state.
        // Future: milk bucket or golden apple consumption.
        if (hazardType.equals("status_effect_dot")) {
            return;
        }

        // For drowning: jump to surface
        if (hazardType.equals("drowning")) {
            DirectInput.setJumping(true);
            DirectInput.setForward(true);
            return;
        }

        // For lava/fire/hazards: use Emmatone GoalRunAway from current position
        BlockPos currentPos = player.blockPosition();
        EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                .setGoalAndPath(new GoalRunAway(10.0, currentPos));
    }
}
