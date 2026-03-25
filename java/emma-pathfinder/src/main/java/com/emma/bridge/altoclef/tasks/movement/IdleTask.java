package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Playground;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;

/**
 * Do nothing — but sleep through the night if possible.
 * After waking, picks up the placed bed before returning to idle.
 */
public class IdleTask extends Task {

    private final Task sleepTask = new SleepThroughNightTask();
    private boolean sleptLastCycle = false;
    private Task pickupBedTask = null;

    @Override
    protected void onStart() {
        sleptLastCycle = false;
        pickupBedTask = null;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Priority: sleep through the night (must come FIRST so bed pickup
        // never interrupts an in-progress sleep task)
        if (WorldHelper.canSleep()) {
            sleptLastCycle = true;
            setDebugState("Sleeping through the night");
            return sleepTask;
        }

        // Daytime: if we just finished sleeping, pick up the bed we placed
        if (sleptLastCycle) {
            sleptLastCycle = false; // only trigger once per sleep cycle
            if (!mod.getItemStorage().hasItem(ItemHelper.BED)) {
                pickupBedTask = TaskCatalogue.getItemTask("bed", 1);
            }
        }

        if (pickupBedTask != null && !pickupBedTask.isFinished()) {
            setDebugState("Picking up bed");
            return pickupBedTask;
        }
        pickupBedTask = null;

        // Do nothing except maybe test code
        setDebugState("Idle");
        Playground.IDLE_TEST_TICK_FUNCTION(mod);
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    public boolean isFinished() {
        // Never finish
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof IdleTask;
    }

    @Override
    protected String toDebugString() {
        return "Idle";
    }
}
