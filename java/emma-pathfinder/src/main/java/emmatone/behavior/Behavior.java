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

package emmatone.behavior;

import emmatone.Emmatone;
import emmatone.api.behavior.IBehavior;
import emmatone.api.utils.IPlayerContext;

/**
 * A type of game event listener that is given {@link Emmatone} instance context.
 *
 * @author Brady
 * @since 8/1/2018
 */
public class Behavior implements IBehavior {

    public final Emmatone emmatone;
    public final IPlayerContext ctx;

    protected Behavior(Emmatone emmatone) {
        this.emmatone = emmatone;
        this.ctx = emmatone.getPlayerContext();
    }
}
