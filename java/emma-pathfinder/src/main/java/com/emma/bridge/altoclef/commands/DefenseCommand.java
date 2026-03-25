package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.movement.DefenseTask;

public class DefenseCommand extends Command {
    public DefenseCommand() {
        super("defense", "Enter active defense mode — fight hostiles, eat when hungry, no sleep");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.runUserTask(new DefenseTask(), this::finish);
    }
}
