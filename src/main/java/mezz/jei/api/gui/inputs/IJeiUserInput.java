/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2015 mezz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package mezz.jei.api.gui.inputs;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.runtime.IJeiKeyMapping;
import mezz.jei.api.runtime.IJeiKeyMappings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * Represents a click or key press.
 *
 * @since 19.6.0
 */
public interface IJeiUserInput {
	/**
	 * Vanilla information about a click or key press.
	 *
	 * @since 19.6.0
	 */
	InputConstants.Key getKey();

	/**
	 * Modifiers passed into methods like {@link GuiEventListener#mouseClicked}
	 *
	 * @since 19.6.0
	 */
	int getModifiers();

	/**
	 * True on mouse down, used to check if a click could be handled.
	 *
	 * False on mouse up and key down: when the input should execute an action.
	 *
	 * Key up is ignored because JEI handles key down immediately.
	 *
	 * @since 19.6.0
	 */
	boolean isSimulate();

	/**
	 * Check if the input matches a given vanilla {@link KeyMapping}.
	 *
	 * @return true if this input and modifiers match the given key mapping.
	 *
	 * @since 19.6.0
	 */
	boolean is(KeyMapping keyMapping);

	/**
	 * Check if the input matches a given {@link IJeiKeyMapping}.
	 * See all the mappings in {@link IJeiKeyMappings}
	 *
	 * @return true if this input and modifiers match the given key mapping.
	 *
	 * @since 19.6.0
	 */
	boolean is(IJeiKeyMapping keyMapping);
}
