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

package emmatone.api.behavior;

import emmatone.api.event.listener.AbstractGameEventListener;
import emmatone.api.event.listener.IGameEventListener;

/**
 * A behavior is simply a type that is able to listen to events.
 *
 * @author Brady
 * @see IGameEventListener
 * @since 9/23/2018
 */
public interface IBehavior extends AbstractGameEventListener {}
