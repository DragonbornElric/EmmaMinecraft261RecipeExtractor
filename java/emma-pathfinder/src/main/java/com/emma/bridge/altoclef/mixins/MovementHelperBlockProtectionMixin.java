package adris.altoclef.mixins;

import adris.altoclef.AltoClef;
import baritone.altoclef.AltoClefSettings;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects AltoClef's block-break protection into Baritone's pathfinder.
 *
 * <p>Problem: AltoClefSettings is a standalone stub (the original ChatClef used a custom
 * Baritone fork where these settings lived inside Baritone). Standard Baritone doesn't
 * consult AltoClefSettings.shouldAvoidBreaking() when pathfinding, so build blocks get
 * broken when Baritone paths through the build zone (e.g. during MobDefenseChain).</p>
 *
 * <p>Solution: Inject into MovementHelper.avoidBreaking() — Baritone checks this before
 * breaking any block during pathfinding. If the block is in AltoClef's breakAvoiders
 * list (set by BuildSchematicTask to protect schematic positions), return true to force
 * Baritone to path around it.</p>
 */
@Mixin(MovementHelper.class)
public interface MovementHelperBlockProtectionMixin {

    /**
     * Before Baritone decides whether to avoid breaking a block during pathfinding,
     * check AltoClef's break-avoidance predicates. These are set by BuildSchematicTask
     * to protect blocks within the active schematic's build zone.
     *
     * If any predicate returns true for this position, force avoidBreaking to return true,
     * making Baritone path around the block instead of through it.
     */
    @Inject(method = "avoidBreaking", at = @At("HEAD"), cancellable = true, remap = false)
    private static void protectSchematicBlocks(
            BlockStateInterface bsi, int x, int y, int z, BlockState state,
            CallbackInfoReturnable<Boolean> cir) {
        try {
            AltoClef mod = AltoClef.getInstance();
            if (mod != null) {
                AltoClefSettings settings = mod.getExtraBaritoneSettings();
                if (settings != null && settings.shouldAvoidBreaking(new BlockPos(x, y, z))) {
                    cir.setReturnValue(true);
                }
            }
        } catch (Exception e) {
            // Never crash Baritone's pathfinder — silently allow breaking on error
        }
    }
}
