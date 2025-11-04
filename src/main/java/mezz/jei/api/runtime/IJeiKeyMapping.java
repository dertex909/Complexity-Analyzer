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

package mezz.jei.api.runtime;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;

/**
 * A key mapping used by JEI.
 * This can be used by mods that want to use the same keys that players bind for JEI.
 *
 * Get instances from {@link IJeiKeyMappings}.
 *
 * @since 11.0.1
 */
public interface IJeiKeyMapping {
	/**
	 * Returns true if the key mapping matches the key,
	 * and the current key modifiers match any pressed key modifiers.
	 *
	 * This works for a mouse click or for a keyboard key, depending on what is bound.
	 *
	 * @since 11.0.1
	 */
	boolean isActiveAndMatches(InputConstants.Key key);

	/**
	 * @return true if there is no key bound to this mapping.
	 *
	 * @since 11.0.1
	 */
	boolean isUnbound();

	/**
	 * @return the name of the key that is bound.
	 *
	 * @since 11.0.1
	 */
	Component getTranslatedKeyMessage();
}
