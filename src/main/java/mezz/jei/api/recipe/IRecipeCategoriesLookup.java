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

import mezz.jei.api.recipe.category.IRecipeCategory;

import java.util.Collection;
import java.util.stream.Stream;

/**
 * This is a helper class for looking up recipe categories.
 * Create one with {@link IRecipeManager#createRecipeCategoryLookup()},
 * then set its properties and call {@link #get()} to get the results.
 *
 * @since 9.5.0
 */
public interface IRecipeCategoriesLookup {
	/**
	 * Limit the results to only recipe categories for the given types.
	 *
	 * @since 9.5.0
	 */
	IRecipeCategoriesLookup limitTypes(Collection<RecipeType<?>> recipeTypes);

	/**
	 * Limit the results to only recipe categories matching the given focuses.
	 *
	 * @since 9.5.0
	 */
	IRecipeCategoriesLookup limitFocus(Collection<? extends IFocus<?>> focuses);

	/**
	 * By default, hidden results are not returned.
	 * Calling this will make this lookup include hidden recipe categories.
	 *
	 * @since 9.5.0
	 */
	IRecipeCategoriesLookup includeHidden();

	/**
	 * Get the recipe category results for this lookup.
	 *
	 * @since 9.5.0
	 */
	Stream<IRecipeCategory<?>> get();
}
