package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;

public class SetAIBridgeEnabledCommand extends Command {

    public SetAIBridgeEnabledCommand() throws CommandException {
        super("chatclef", "Turns chatclef on or off, can ONLY be run by the user (NOT the agent).",
                new Arg<>(ToggleState.class, "onOrOff"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        ToggleState toggle = parser.get(ToggleState.class);
        // AI bridge is now managed by Emma's Python orchestrator
        Debug.logMessage("ChatClef toggle requested: " + toggle + " (managed by Emma)");
        finish();
    }

    public enum ToggleState {
        ON,
        OFF
    }
}
