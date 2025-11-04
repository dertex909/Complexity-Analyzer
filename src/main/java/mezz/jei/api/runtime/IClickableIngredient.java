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

import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;

/**
 * A clickable ingredient drawn on the screen, that JEI can use for recipe lookups.
 *
 * This can be an ingredient drawn in a GUI container slot, a fluid tank,
 * or anything else that holds ingredients.
 *
 * Create one with {@link IIngredientManager#getClickableIngredientFactory()}
 * @see IClickableIngredientFactory
 *
 * @since 11.5.0
 */
public interface IClickableIngredient<T> {
	/**
	 * Get the typed ingredient that can be looked up by JEI for recipes.
	 *
	 * @since 11.5.0
	 */
	ITypedIngredient<T> getTypedIngredient();

	/**
	 * @since 19.12.0
	 *
	 * @deprecated use {@link #getTypedIngredient()}
	 */
	@Deprecated(since = "19.23.0", forRemoval = true)
	default IIngredientType<T> getIngredientType() {
		return getTypedIngredient().getType();
	}

	/**
	 * @since 19.12.0
	 *
	 * @deprecated use {@link #getTypedIngredient()}
	 */
	@Deprecated(since = "19.23.0", forRemoval = true)
	default T getIngredient() {
		ITypedIngredient<T> typedIngredient = getTypedIngredient();
		return typedIngredient.getIngredient();
	}

	/**
	 * Get the area that this clickable ingredient is drawn in, in absolute screen coordinates.
	 * This is used for click handling, to ensure the mouse-down and mouse-up are on the same ingredient.
	 *
	 * @since 11.5.0
	 */
	Rect2i getArea();
}
