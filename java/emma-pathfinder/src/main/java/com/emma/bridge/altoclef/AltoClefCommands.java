package adris.altoclef;

import adris.altoclef.commands.AttackPlayerOrMobCommand;
import adris.altoclef.commands.DefenseCommand;
import adris.altoclef.commands.DepositCommand;
import adris.altoclef.commands.EquipCommand;
import adris.altoclef.commands.FollowCommand;
import adris.altoclef.commands.FoodCommand;
import adris.altoclef.commands.GamerCommand;
import adris.altoclef.commands.GetCommand;
import adris.altoclef.commands.GiveCommand;
import adris.altoclef.commands.GotoCommand;
import adris.altoclef.commands.HeroCommand;
import adris.altoclef.commands.IdleCommand;
import adris.altoclef.commands.InventoryCommand;
import adris.altoclef.commands.ListCommand;
import adris.altoclef.commands.LocateStructureCommand;
import adris.altoclef.commands.MeatCommand;
import adris.altoclef.commands.PauseCommand;
import adris.altoclef.commands.ReloadSettingsCommand;
import adris.altoclef.commands.RespawnCommand;
import adris.altoclef.commands.SetAIBridgeEnabledCommand;
import adris.altoclef.commands.SetGammaCommand;
import adris.altoclef.commands.SleepCommand;
import adris.altoclef.commands.StashCommand;
import adris.altoclef.commands.StatusCommand;
import adris.altoclef.commands.StopCommand;
import adris.altoclef.commands.UnPauseCommand;
import adris.altoclef.commands.ResetMemoryCommand;
import adris.altoclef.commands.TorchCommand;
import adris.altoclef.commands.TorchLevelCommand;
import adris.altoclef.commands.FarmCommand;
import adris.altoclef.commands.CreateFarmCommand;
import adris.altoclef.commands.random.ScanCommand;
import adris.altoclef.commandsystem.CommandException;

/**
 * Initializes altoclef's built in commands.
 */
public class AltoClefCommands {

    public static void init() throws CommandException {
        // List commands here
        AltoClef.getCommandExecutor().registerNewCommand(
                new GetCommand(),
                new EquipCommand(),
                new DepositCommand(),
                new StashCommand(),
                new GotoCommand(),
                new IdleCommand(),
                new HeroCommand(),
                new LocateStructureCommand(),
                new StopCommand(),
                new SetGammaCommand(),
                new FoodCommand(),
                new MeatCommand(),
                new ReloadSettingsCommand(),
                new ResetMemoryCommand(),
                new GamerCommand(),
                new FollowCommand(),
                new GiveCommand(),
                new ScanCommand(),
                new AttackPlayerOrMobCommand(),
                new SetAIBridgeEnabledCommand(),
                new SleepCommand(),
                new DefenseCommand(),
                new ListCommand(),
                new PauseCommand(),
                new UnPauseCommand(),
                new StatusCommand(),
                new InventoryCommand(),
                new RespawnCommand(),
                new TorchCommand(),
                new TorchLevelCommand(),
                new FarmCommand(),
                new CreateFarmCommand()
        );
    }
}
