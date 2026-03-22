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

package emmatone;

import emmatone.api.IEmmatone;
import emmatone.api.IEmmatoneProvider;
import emmatone.api.cache.IWorldScanner;
import emmatone.api.command.ICommandSystem;
import emmatone.api.schematic.ISchematicSystem;
import emmatone.cache.FasterWorldScanner;
import emmatone.command.CommandSystem;

import emmatone.utils.schematic.SchematicSystem;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Brady
 * @since 9/29/2018
 */
public final class EmmatoneProvider implements IEmmatoneProvider {

    private final List<IEmmatone> all;
    private final List<IEmmatone> allView;

    public EmmatoneProvider() {
        this.all = new CopyOnWriteArrayList<>();
        this.allView = Collections.unmodifiableList(this.all);

        this.createEmmatone(Minecraft.getInstance());
    }

    @Override
    public IEmmatone getPrimaryEmmatone() {
        return this.all.get(0);
    }

    @Override
    public List<IEmmatone> getAllEmmatones() {
        return this.allView;
    }

    @Override
    public synchronized IEmmatone createEmmatone(Minecraft minecraft) {
        IEmmatone emmatone = this.getEmmatoneForMinecraft(minecraft);
        if (emmatone == null) {
            this.all.add(emmatone = new Emmatone(minecraft));
        }
        return emmatone;
    }

    @Override
    public synchronized boolean destroyEmmatone(IEmmatone emmatone) {
        return emmatone != this.getPrimaryEmmatone() && this.all.remove(emmatone);
    }

    @Override
    public IWorldScanner getWorldScanner() {
        return FasterWorldScanner.INSTANCE;
    }

    @Override
    public ICommandSystem getCommandSystem() {
        return CommandSystem.INSTANCE;
    }

    @Override
    public ISchematicSystem getSchematicSystem() {
        return SchematicSystem.INSTANCE;
    }
}
