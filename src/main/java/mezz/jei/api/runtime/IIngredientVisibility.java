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

import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link IIngredientVisibility} allows mod plugins to do advanced filtering of
 * ingredients based on what is visible in JEI.
 *
 * An instance available from {@link IJeiHelpers#getIngredientVisibility()}.
 *
 * @since JEI 9.3.0
 */
@ApiStatus.NonExtendable
public interface IIngredientVisibility {
	/**
	 * Returns true if the given ingredient is visible in JEI's ingredient list.
	 *
	 * Returns false if the given ingredient is invalid, removed by the server,
	 * hidden by a mod, or hidden by the player.
	 *
	 * @since 9.3.0
	 */
	<V> boolean isIngredientVisible(IIngredientType<V> ingredientType, V ingredient);

	/**
	 * Returns true if the given ingredient is visible in JEI's ingredient list.
	 *
	 * Returns false if the given ingredient is invalid, removed by the server,
	 * hidden by a mod, or hidden by the player.
	 *
	 * @since 10.0.0
	 */
	<V> boolean isIngredientVisible(ITypedIngredient<V> typedIngredient);

	/**
	 * Register a listener that receives updates when ingredient visibility changes.
	 *
	 * @since 11.5.0
	 */
	void registerListener(IListener listener);

	/**
	 * A listener that receives updates when ingredients are made visible or invisible.
	 *
	 * @since 11.5.0
	 */
	interface IListener {
		/**
		 * Called when ingredients are made visible or invisible.
		 * @since 11.5.0
		 */
		<V> void onIngredientVisibilityChanged(ITypedIngredient<V> ingredient, boolean visible);
	}
}
