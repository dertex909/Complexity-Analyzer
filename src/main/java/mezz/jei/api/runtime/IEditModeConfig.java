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

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;

import java.util.Collection;
import java.util.Set;

/**
 * This class gives access to the edit-mode config, which lets users hide ingredients from JEI.
 * These changes are written to a config file that users can edit.
 *
 * The only mods that should use this class are ones that are creating a GUI
 * that players can use to edit this config file more conveniently.
 *
 * If you want to hide items in a way that is not related to this config file,
 * see {@link IIngredientManager#removeIngredientsAtRuntime(IIngredientType, Collection)}.
 *
 * @since 11.5.0
 */
public interface IEditModeConfig {
	/**
	 * Returns true if the given ingredient is hidden because it is configured to be hidden by the player.
	 *
	 * @since 11.5.0
	 */
	<V> boolean isIngredientHiddenUsingConfigFile(ITypedIngredient<V> ingredient);

	/**
	 * Returns a set of the different {@link HideMode}s that are used to hide this ingredient,
	 * if the given ingredient is hidden because it is configured to be hidden by the player.
	 *
	 * If the ingredient is not hidden, the set will be empty.
	 *
	 * @since 11.5.0
	 */
	<V> Set<HideMode> getIngredientHiddenUsingConfigFile(ITypedIngredient<V> ingredient);

	/**
	 * Sets an ingredient as hidden.
	 * This action should only be initiated by a player directly deciding to hide the ingredient.
	 *
	 * @since 11.5.0
	 */
	<V> void hideIngredientUsingConfigFile(ITypedIngredient<V> ingredient, HideMode hideMode);

	/**
	 * Sets an ingredient as shown.
	 * This action should only be initiated by a player directly deciding to show the ingredient.
	 *
	 * @since 11.5.0
	 */
	<V> void showIngredientUsingConfigFile(ITypedIngredient<V> ingredient, HideMode hideMode);

	/**
	 * The mode to hide or show an ingredient with.
	 * Note that ingredients can be hidden my multiple modes at once.
	 *
	 * @since 11.5.0
	 */
	enum HideMode {
		/**
		 * Hides or shows a single item based on its UID.
		 * See {@link IIngredientHelper#getUid(Object, UidContext)} using {@link UidContext#Ingredient}.
		 *
		 * @since 11.5.0
		 */
		SINGLE,
		/**
		 * Hides or shows a single item based on its Wildcard UID.
		 * See {@link IIngredientHelper#getGroupingUid}.
		 *
		 * @since 11.5.0
		 */
		WILDCARD
	}
}
