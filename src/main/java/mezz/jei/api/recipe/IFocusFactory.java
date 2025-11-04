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

package mezz.jei.api.recipe;

import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;

import java.util.Collection;

/**
 * {@link IFocusFactory} helps with creating {@link IFocus} for JEI.
 * Get an instance from {@link IJeiHelpers#getFocusFactory()}.
 */
public interface IFocusFactory {
	/**
	 * Returns a new focus.
	 *
	 * @since 9.3.0
	 */
	<V> IFocus<V> createFocus(RecipeIngredientRole role, IIngredientType<V> ingredientType, V ingredient);

	/**
	 * Returns a new focus.
	 *
	 * @since 11.5.0
	 */
	<V> IFocus<V> createFocus(RecipeIngredientRole role, ITypedIngredient<V> typedIngredient);

	/**
	 * Returns a new focus group.
	 *
	 * @since 11.5.0
	 */
	IFocusGroup createFocusGroup(Collection<? extends IFocus<?>> focuses);

	/**
	 * Returns an empty focus group.
	 *
	 * @since 11.5.0
	 */
	IFocusGroup getEmptyFocusGroup();
}
