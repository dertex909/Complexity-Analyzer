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

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import org.jetbrains.annotations.ApiStatus;

import java.util.Optional;

/**
 * The current search focus.
 * Set by the player when they look up the recipe. The ingredient being looked up is the focus.
 * This class is immutable, the value and mode do not change.
 *
 * Create a focus with {@link IFocusFactory#createFocus(RecipeIngredientRole, IIngredientType, Object)}.
 *
 * Use a null IFocus to signify no focus, like in the case of looking up categories of recipes.
 */
@ApiStatus.NonExtendable
public interface IFocus<V> {
	/**
	 * The ingredient that is being focused on.
	 *
	 * @since 9.3.0
	 */
	ITypedIngredient<V> getTypedValue();

	/**
	 * The focused recipe ingredient role.
	 *
	 * @since 9.3.0
	 */
	RecipeIngredientRole getRole();

	/**
	 * @return this focus if it matches the given ingredient type.
	 * This is useful when handling a wildcard generic instance of `IFocus<?>`.
	 *
	 * @since 9.4.0
	 */
	<T> Optional<IFocus<T>> checkedCast(IIngredientType<T> ingredientType);
}
