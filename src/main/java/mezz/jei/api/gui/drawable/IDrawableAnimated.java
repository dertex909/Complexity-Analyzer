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

import mezz.jei.api.gui.ITickTimer;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;

/**
 * An animated {@link IDrawable}, useful for showing a gui animation like furnace flames or progress arrows.
 *
 * Useful for drawing miscellaneous things in
 * {@link IRecipeCategory#draw(Object, IRecipeSlotsView, GuiGraphics, double, double)}.
 *
 * To create an instance, call
 * {@link IGuiHelper#createAnimatedDrawable(IDrawableStatic, int, StartDirection, boolean)}
 *
 * Internally, these use an {@link ITickTimer} to simulate tick-driven animations.
 */
public interface IDrawableAnimated extends IDrawable {
	/**
	 * The direction that the animation starts from.
	 *
	 * @see IGuiHelper#createAnimatedDrawable(IDrawableStatic, int, StartDirection, boolean)
	 */
	enum StartDirection {
		TOP, BOTTOM, LEFT, RIGHT
	}
}
