package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

/**
 * @torchlevel <n> — set auto torch light threshold
 */
public class TorchLevelCommand extends Command {
    public TorchLevelCommand() {
        super("torchlevel", "Set auto torch light threshold <n>");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        String[] args = parser.getArgUnits();
        if (args.length > 0) {
            try {
                int level = Integer.parseInt(args[0]);
                mod.getTorchPlacer().setLightThreshold(level);
                mod.log("Torch light threshold set to " + level);
            } catch (NumberFormatException e) {
                mod.log("Usage: @torchlevel <number>");
            }
        } else {
            mod.log("Current torch threshold: " + mod.getTorchPlacer().getLightThreshold());
        }
    }
}
