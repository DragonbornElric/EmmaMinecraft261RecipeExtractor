package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import baritone.api.BaritoneAPI;

/**
 * @farm [range] — start Baritone's FarmProcess to harvest/replant crops.
 */
public class FarmCommand extends Command {
    public FarmCommand() {
        super("farm", "Harvest and replant crops [range]");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        String[] args = parser.getArgUnits();
        int range = 100;
        if (args.length > 0) {
            try {
                range = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                mod.log("Usage: @farm [range]");
                return;
            }
        }
        BaritoneAPI.getProvider().getPrimaryBaritone()
                .getFarmProcess().farm(range);
        mod.log("Farm started, range=" + range);
    }
}
