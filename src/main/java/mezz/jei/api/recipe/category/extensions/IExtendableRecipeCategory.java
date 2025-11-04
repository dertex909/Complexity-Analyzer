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

package mezz.jei.api.recipe.category.extensions;

import java.util.function.Function;
import java.util.function.Predicate;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.IExtendableCraftingRecipeCategory;

/**
 * @deprecated breaking change: replaced by a simpler interface {@link IExtendableCraftingRecipeCategory} to support RecipeHolder
 */
@Deprecated(since = "16.0.0", forRemoval = true)
public interface IExtendableRecipeCategory<T, W extends IRecipeCategoryExtension<T>> extends IRecipeCategory<T> {
	/**
	 * Add an extension that handles a subset of the recipes in the recipe category.
	 *
	 * @param recipeClass      the class of recipes to handle
	 * @param extensionFactory a factory that can turn recipes into recipe extensions
	 * @deprecated extensions can be singletons now, use {@link IExtendableCraftingRecipeCategory#addExtension(Class, ICraftingCategoryExtension)}
	 */
	@Deprecated(since = "16.0.0", forRemoval = true)
	<R extends T> void addCategoryExtension(Class<? extends R> recipeClass, Function<R, ? extends W> extensionFactory);

	/**
	 * Add an extension that handles a subset of the recipes in the recipe category.
	 *
	 * @param recipeClass      the class of recipes to handle
	 * @param extensionFilter  a filter that returns true for instances of the recipe that can be handled by the extensionFactory
	 * @param extensionFactory a factory that can turn recipes into recipe extensions
	 * @since 7.2.0
	 *
	 * @deprecated extensions can be singletons now,
	 * use {@link IExtendableCraftingRecipeCategory#addExtension(Class, ICraftingCategoryExtension)}
	 * and {@link IRecipeCategoryExtension#isHandled(Object)}
	 */
	@Deprecated(since = "16.0.0", forRemoval = true)
	<R extends T> void addCategoryExtension(Class<? extends R> recipeClass, Predicate<R> extensionFilter, Function<R, ? extends W> extensionFactory);
}
