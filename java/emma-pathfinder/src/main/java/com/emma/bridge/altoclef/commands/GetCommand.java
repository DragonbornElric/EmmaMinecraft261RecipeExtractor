package adris.altoclef.commands;


import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.ItemList;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;

import java.util.ArrayList;
import java.util.List;

public class GetCommand extends Command {

    public GetCommand() throws CommandException {
        super("get", "Get a resource or Craft an item in Minecraft. You can craft item even if you don't have ingredients in inventory already. Examples: `get log 20` gets 20 logs, `get diamond_chestplate 1` gets 1 diamond chestplate. For equipments you have to specify the type of equipments like wooden, stone, iron, golden and diamond.", new Arg<>(ItemList.class, "items"));
    }

    private void getItems(AltoClef mod, ItemTarget... items) {
        Task targetTask;

        // Add present inventory count to targets so "get 10 logs" means "end up with 10 total"
        items = addPresentItemsToTargets(mod, items);

        if (items == null || items.length == 0) {
            mod.log("You must specify at least one item!");
            finish();
            return;
        }
        if (items.length == 1) {
            targetTask = TaskCatalogue.getItemTask(items[0]);
        } else {
            targetTask = TaskCatalogue.getSquashedItemTask(items);
        }
        if (targetTask != null) {
            mod.runUserTask(targetTask, this::finish);
        } else {
            finish();
        }
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        ItemList items = parser.get(ItemList.class);
        getItems(mod, items.items);
    }

    private static ItemTarget[] addPresentItemsToTargets(AltoClef mod, ItemTarget[] items) {
        List<ItemTarget> result = new ArrayList<>();
        for (ItemTarget target : items) {
            int count = target.getTargetCount();
            count += mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            result.add(new ItemTarget(target, count));
        }
        return result.toArray(new ItemTarget[0]);
    }
}
