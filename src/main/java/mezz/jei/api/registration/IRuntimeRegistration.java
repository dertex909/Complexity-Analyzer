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

import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IEditModeConfig;
import mezz.jei.api.runtime.IIngredientFilter;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.api.runtime.IRecipesGui;
import mezz.jei.api.runtime.IScreenHelper;

/**
 * Allows mods to override the runtime classes for JEI with their own implementation.
 *
 * @since 12.0.2
 */
public interface IRuntimeRegistration {
	/**
	 * Set the ingredient list overlay.
	 *
	 * This is used by JEI's GUI and can be used by other mods
	 * that want to override JEI's GUI and have it still work with mods that use JEI's API.
	 */
	void setIngredientListOverlay(IIngredientListOverlay ingredientListOverlay);

	/**
	 * Set the bookmark list overlay.
	 *
	 * This is used by JEI's GUI and can be used by other mods
	 * that want to override JEI's GUI and have it still work with mods that use JEI's API.
	 */
	void setBookmarkOverlay(IBookmarkOverlay bookmarkOverlay);

	/**
	 * Set the Recipe GUI.
	 *
	 * This is used by JEI's GUI and can be used by other mods
	 * that want to override JEI's GUI and have it still work with mods that use JEI's API.
	 */
	void setRecipesGui(IRecipesGui recipesGui);

	/**
	 * Set the Ingredient Filter.
	 *
	 * This is used by JEI's GUI and can be used by other mods
	 * that want to override JEI's GUI and have it still work with mods that use JEI's API.
	 */
	void setIngredientFilter(IIngredientFilter ingredientFilter);

	/**
	 * The {@link IRecipeManager} offers several functions for retrieving and handling recipes.
	 */
	IRecipeManager getRecipeManager();

	/**
	 * {@link IJeiHelpers} provides helpers and tools for addon mods.
	 */
	IJeiHelpers getJeiHelpers();

	/**
	 * The {@link IIngredientManager} has some useful functions related to recipe ingredients.
	 */
	IIngredientManager getIngredientManager();

	/**
	 * The {@link IIngredientVisibility} allows mod plugins to do advanced filtering of
	 * ingredients based on what is visible in JEI.
	 * @deprecated use {@link IJeiHelpers#getIngredientVisibility()}
	 */
	@Deprecated(since = "19.18.4", forRemoval = true)
	default IIngredientVisibility getIngredientVisibility() {
		return getJeiHelpers().getIngredientVisibility();
	}

	/**
	 * Get a helper for all runtime Screen functions.
	 * This is used by JEI's GUI and can be used by other mods that want to use the same information from JEI.
	 */
	IScreenHelper getScreenHelper();

	/**
	 * Get a manager that holds all the registered recipe transfer handlers.
	 * This is used by JEI's GUI and can be used by other mods that want to use the same information from JEI.
	 */
	IRecipeTransferManager getRecipeTransferManager();

	/**
	 * Get access to the edit-mode config, which lets users hide ingredients from JEI.
	 * This is used by JEI's GUI and can be used by other mods that want to use the same information from JEI.
	 */
	IEditModeConfig getEditModeConfig();
}
