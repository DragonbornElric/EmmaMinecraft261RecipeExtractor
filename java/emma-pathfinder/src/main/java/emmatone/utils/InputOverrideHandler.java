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

package emmatone.utils;

import emmatone.Emmatone;
import emmatone.api.utils.IInputOverrideHandler;
import emmatone.api.utils.input.Input;
import emmatone.behavior.Behavior;

/**
 * Gutted shell — kept as a {@link Behavior} so the registration lifecycle
 * doesn't break, and so {@link IInputOverrideHandler} callers compile.
 * <p>
 * All virtual-input state is gone. Movement goes through {@link ControlledInput},
 * block breaking through {@link ProcessBreakHelper} or {@code Movement.executeBreak()},
 * block placing through direct {@code processRightClickBlock()} calls.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    public InputOverrideHandler(Emmatone emmatone) {
        super(emmatone);
    }

    @Override
    public final boolean isInputForcedDown(Input input) {
        return false; // no-op
    }

    @Override
    public final void setInputForceState(Input input, boolean forced) {
        // no-op
    }

    @Override
    public final void clearAllKeys() {
        // no-op
    }
}
