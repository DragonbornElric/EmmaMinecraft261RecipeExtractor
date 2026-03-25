package adris.altoclef.tasks.movement;

import adris.altoclef.tasksystem.Task;

/**
 * Stand ground and defend — MobDefenseChain + FoodChain run in the
 * background automatically. Unlike IdleTask, this does NOT sleep
 * through the night or pick up beds.
 */
public class DefenseTask extends Task {

    @Override
    protected void onStart() {
    }

    @Override
    protected Task onTick() {
        setDebugState("Defense mode — standing guard");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    public boolean isFinished() {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof DefenseTask;
    }

    @Override
    protected String toDebugString() {
        return "Defense";
    }
}
