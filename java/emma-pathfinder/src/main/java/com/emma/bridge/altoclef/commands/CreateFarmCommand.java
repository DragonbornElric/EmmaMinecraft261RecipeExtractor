package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.CreateFarmTask;
import net.minecraft.util.math.BlockPos;

/**
 * @create_farm <radius>         — create farm centered on player
 * @create_farm <x> <y> <z> <radius> — create farm at specific location
 */
public class CreateFarmCommand extends Command {
    public CreateFarmCommand() {
        super("create_farm", "Till and plant a farm <radius> or <x> <y> <z> <radius>");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        String[] args = parser.getArgUnits();
        if (args.length == 0) {
            mod.log("Usage: @create_farm <radius> or @create_farm <x> <y> <z> <radius>");
            return;
        }
        try {
            if (args.length == 1) {
                // @create_farm 5 — radius only, center on player
                int radius = Integer.parseInt(args[0]);
                BlockPos playerPos = mod.getPlayer().getBlockPos().down();
                mod.runUserTask(new CreateFarmTask(playerPos, radius));
                mod.log("Creating farm at player position, radius=" + radius);
            } else if (args.length >= 4) {
                // @create_farm x y z radius
                int x = Integer.parseInt(args[0]);
                int y = Integer.parseInt(args[1]);
                int z = Integer.parseInt(args[2]);
                int radius = Integer.parseInt(args[3]);
                mod.runUserTask(new CreateFarmTask(new BlockPos(x, y, z), radius));
                mod.log("Creating farm at " + x + ", " + y + ", " + z + " radius=" + radius);
            } else {
                mod.log("Usage: @create_farm <radius> or @create_farm <x> <y> <z> <radius>");
            }
        } catch (NumberFormatException e) {
            mod.log("Usage: @create_farm <radius> or @create_farm <x> <y> <z> <radius>");
        }
    }
}
