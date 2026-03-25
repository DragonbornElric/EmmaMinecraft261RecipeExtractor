package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

/**
 * @torch [on|off] — toggle auto torch placement master switch
 */
public class TorchCommand extends Command {
    public TorchCommand() {
        super("torch", "Toggle auto torch placement [on|off]");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        var tp = mod.getTorchPlacer();
        String[] args = parser.getArgUnits();

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "on" -> { tp.setEnabled(true); tp.setActive(true); }
                case "off" -> { tp.setEnabled(false); tp.setActive(false); }
                default -> { tp.toggleEnabled(); tp.setActive(tp.isEnabled()); }
            }
        } else {
            tp.toggleEnabled();
            tp.setActive(tp.isEnabled());
        }

        mod.log("AutoTorch " + (tp.isEnabled() ? "enabled" : "disabled"));
    }
}
