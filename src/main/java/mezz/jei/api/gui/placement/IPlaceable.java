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

package mezz.jei.api.gui.placement;

/**
 * Interface for things that can have their position set, and be aligned vertically and horizontally in an area.
 *
 * @since 19.19.1
 */
public interface IPlaceable<THIS extends IPlaceable<THIS>> {
	/**
	 * Place this element at the given position.
	 * @since 19.19.1
	 */
	THIS setPosition(int xPos, int yPos);

	/**
	 * Place this element inside the given area, with the given alignment.
	 *
	 * @since 19.19.1
	 */
	default THIS setPosition(int areaX, int areaY, int areaWidth, int areaHeight, HorizontalAlignment horizontalAlignment, VerticalAlignment verticalAlignment) {
		int x = areaX + horizontalAlignment.getXPos(areaWidth, getWidth());
		int y = areaY + verticalAlignment.getYPos(areaHeight, getHeight());
		return setPosition(x, y);
	}

	/**
	 * Get the width of this element.
	 * @since 19.19.1
	 */
	int getWidth();

	/**
	 * Get the height of this element.
	 * @since 19.19.1
	 */
	int getHeight();
}
