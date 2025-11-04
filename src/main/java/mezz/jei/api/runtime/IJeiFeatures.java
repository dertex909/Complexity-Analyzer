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

import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IAdvancedRegistration;

/**
 * Provides access for mod plugins to disable various JEI features.
 * This may be needed by mods that substantially change hard-coded vanilla behaviors.
 *
 * Get an instance from {@link IAdvancedRegistration#getJeiFeatures()}
 *
 * @since 17.3.0
 */
public interface IJeiFeatures {
	/**
	 * Disable JEI's Inventory Effect Renderer {@link IGuiContainerHandler}.
	 * This is used by JEI in order to move out of the way of potion effects shown next to the inventory.
	 * It can be disabled by mods that remove this behavior or substitute their own.
	 *
	 * @since 17.3.0
	 */
	void disableInventoryEffectRendererGuiHandler();
}
