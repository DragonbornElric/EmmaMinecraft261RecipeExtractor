/*
 * This file is part of Emmatone.
 *
 * Emmatone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Emmatone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Emmatone.  If not, see <https://www.gnu.org/licenses/>.
 */

package emmatone.command.defaults;

import emmatone.api.IEmmatone;
import emmatone.api.command.ICommand;

import java.util.*;

public final class DefaultCommands {

    private DefaultCommands() {
    }

    public static List<ICommand> createAll(IEmmatone emmatone) {
        Objects.requireNonNull(emmatone);
        List<ICommand> commands = new ArrayList<>(Arrays.asList(
                new HelpCommand(emmatone),
                new SetCommand(emmatone),
                new CommandAlias(emmatone, Arrays.asList("modified", "mod", "emmatone", "modifiedsettings"), "List modified settings", "set modified"),
                new CommandAlias(emmatone, "reset", "Reset all settings or just one", "set reset"),
                new GoalCommand(emmatone),
                new GotoCommand(emmatone),
                new PathCommand(emmatone),
                new ProcCommand(emmatone),
                new ETACommand(emmatone),
                new VersionCommand(emmatone),
                new RepackCommand(emmatone),
                new BuildCommand(emmatone),
                //new SchematicaCommand(emmatone),
                new LitematicaCommand(emmatone),
                new ComeCommand(emmatone),
                new AxisCommand(emmatone),
                new ForceCancelCommand(emmatone),
                new GcCommand(emmatone),
                new InvertCommand(emmatone),
                new TunnelCommand(emmatone),
                new RenderCommand(emmatone),
                new FarmCommand(emmatone),
                new FollowCommand(emmatone),
                new PickupCommand(emmatone),
                new ExploreFilterCommand(emmatone),
                new ReloadAllCommand(emmatone),
                new SaveAllCommand(emmatone),
                new ExploreCommand(emmatone),
                new BlacklistCommand(emmatone),
                new FindCommand(emmatone),
                new MineCommand(emmatone),
                new ClickCommand(emmatone),
                new SurfaceCommand(emmatone),
                new ThisWayCommand(emmatone),
                new WaypointsCommand(emmatone),
                new CommandAlias(emmatone, "sethome", "Sets your home waypoint", "waypoints save home"),
                new CommandAlias(emmatone, "home", "Path to your home waypoint", "waypoints goto home"),
                new SelCommand(emmatone),
                new ElytraCommand(emmatone)
        ));
        ExecutionControlCommands prc = new ExecutionControlCommands(emmatone);
        commands.add(prc.pauseCommand);
        commands.add(prc.resumeCommand);
        commands.add(prc.pausedCommand);
        commands.add(prc.cancelCommand);
        return Collections.unmodifiableList(commands);
    }
}
