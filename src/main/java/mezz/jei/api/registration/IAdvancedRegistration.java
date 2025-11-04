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

package mezz.jei.api.registration;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.advanced.IRecipeManagerPlugin;
import mezz.jei.api.recipe.advanced.IRecipeManagerPluginHelper;
import mezz.jei.api.recipe.advanced.ISimpleRecipeManagerPlugin;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;
import mezz.jei.api.runtime.IJeiFeatures;

/**
 * The IAdvancedRegistration instance is passed to your mod plugin in {@link IModPlugin#registerAdvanced(IAdvancedRegistration)}.
 */
public interface IAdvancedRegistration {
	/**
	 * {@link IJeiHelpers} provides helpers and tools for addon mods.
	 */
	IJeiHelpers getJeiHelpers();

	/**
	 * Helpers for implementing {@link IRecipeManagerPlugin}s.
	 *
	 * @since 19.15.1
	 */
	IRecipeManagerPluginHelper getRecipeManagerPluginHelper();

	/**
	 * Register your own {@link IRecipeManagerPlugin} here.
	 */
	void addRecipeManagerPlugin(IRecipeManagerPlugin recipeManagerPlugin);

	/**
	 * Register your own {@link ISimpleRecipeManagerPlugin} here.
	 *
	 * @since 19.16.0
	 */
	<T> void addTypedRecipeManagerPlugin(RecipeType<T> recipeType, ISimpleRecipeManagerPlugin<T> recipeManagerPlugin);

	/**
	 * Register a {@link IRecipeCategoryDecorator} for a recipe type.
	 *
	 * @since 15.1.0
	 */
	<T> void addRecipeCategoryDecorator(RecipeType<T> recipeType, IRecipeCategoryDecorator<T> decorator);

	/**
	 * Get access to disable various JEI features.
	 * This may be needed by mods that substantially change hard-coded vanilla behaviors.
	 *
	 * @since 17.3.0
	 */
	IJeiFeatures getJeiFeatures();
}
