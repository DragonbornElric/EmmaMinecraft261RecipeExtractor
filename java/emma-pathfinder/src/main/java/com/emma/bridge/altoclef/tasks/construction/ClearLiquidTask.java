package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;

/**
 * Removes a liquid source block at a position.
 */
public class ClearLiquidTask extends Task {

    private final BlockPos _liquidPos;

    public ClearLiquidTask(BlockPos liquidPos) {
        this._liquidPos = liquidPos;
    }

    @Override
    protected void onStart() {

    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getItemStorage().hasItem(Items.BUCKET)) {
            mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.SOURCE_ONLY);
            if (BlockInteraction.isInReach(mod, _liquidPos)) {
                BlockPos obstruction = BlockInteraction.fluidLOSObstruction(mod, _liquidPos);
                if (obstruction != null) return new DestroyBlockTask(obstruction);
                BlockInteraction.tryCollectFluid(mod, _liquidPos);
                return null;
            }
            return new GetCloseToBlockTask(_liquidPos);
        }
        return new PlaceStructureBlockTask(_liquidPos);
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    public boolean isFinished() {
        if (AltoClef.getInstance().getChunkTracker().isChunkLoaded(_liquidPos)) {
            return AltoClef.getInstance().getWorld().getBlockState(_liquidPos).getFluidState().isEmpty();
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ClearLiquidTask task) {
            return task._liquidPos.equals(_liquidPos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Clear liquid at " + _liquidPos;
    }
}
