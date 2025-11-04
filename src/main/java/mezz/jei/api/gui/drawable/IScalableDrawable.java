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

package mezz.jei.api.gui.drawable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

/**
 * Similar to {@link IDrawable} but it can scale to any size.
 * Used for backgrounds of UIs and other things that have dynamic size.
 *
 * @since 19.4.0
 */
public interface IScalableDrawable {
	/**
	 * Draw in the given area.
	 *
	 * @since 19.4.0
	 */
	void draw(GuiGraphics guiGraphics, int x, int y, int width, int height);

	/**
	 * Draw in the given area.
	 *
	 * @since 19.4.0
	 */
	default void draw(GuiGraphics guiGraphics, Rect2i area) {
		draw(guiGraphics, area.getX(), area.getY(), area.getWidth(), area.getHeight());
	}
}
