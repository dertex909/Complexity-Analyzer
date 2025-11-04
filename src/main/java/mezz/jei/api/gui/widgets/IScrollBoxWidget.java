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

package mezz.jei.api.gui.widgets;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import net.minecraft.network.chat.FormattedText;

import java.util.List;

/**
 * A smooth-scrolling area with a scrollbar.
 *
 * Create one with {@link IRecipeExtrasBuilder#addScrollBoxWidget}.
 *
 * @since 19.8.0
 */
public interface IScrollBoxWidget extends IRecipeWidget, IJeiInputHandler {
	/**
	 * Get the width available for displaying contents in the scroll box.
	 * The scroll bar takes up some of the space, so this can be used in order to create accurately-sized contents.
	 *
	 * @since 19.18.9
	 */
	int getContentAreaWidth();

	/**
	 * Get the visible height for displaying contents in the scroll box.
	 * The actual height of the contents can be taller, because the box can scroll to show more.
	 *
	 * @since 19.18.9
	 */
	int getContentAreaHeight();

	/**
	 * Set the contents to display inside the scroll box.
	 * The drawable width should match {@link #getContentAreaWidth()}, and the height can be any height.
	 *
	 * @since 19.18.9
	 */
	IScrollBoxWidget setContents(IDrawable contents);

	/**
	 * Display text in the scroll box.
	 * Text will be automatically wrapped in order to fit inside of {@link #getContentAreaWidth()}.
	 *
	 * @since 19.18.9
	 */
	IScrollBoxWidget setContents(List<FormattedText> text);
}
