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

package mezz.jei.api.ingredients.subtypes;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IModIngredientRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;

/**
 * Optional context used when getting Unique IDs for ingredients and subtypes.
 * Subtype interpreters can use this context to return different subtypes for recipes and ingredients.
 * When implementing this, {@link #Ingredient} subtypes should be more specific than {@link #Recipe} subtypes.
 *
 * @since 7.3.0
 */
public enum UidContext {
	/**
	 * Context used for comparing ingredients in the ingredient list.
	 * This is the main context and should be more specific than {@link #Recipe}.
	 *
	 * Used for:
	 * ingredients (see {@link IModIngredientRegistration}
	 * blacklists from the config
	 * debug info
	 * bookmarks
	 */
	Ingredient,

	/**
	 * Context used for comparing ingredients in recipes.
	 * This is a secondary context and should be less specific than {@link #Ingredient}, to allow for broader matches in recipes.
	 *
	 * Used for:
	 * recipe lookups (see {@link IRecipeCategory#setRecipe(IRecipeLayoutBuilder, Object, IFocusGroup)})
	 * recipe catalysts (see {@link IRecipeCatalystRegistration})
	 * recipe transfer (since JEI 7.4.0) (see {@link IRecipeTransferRegistration}
	 */
	Recipe
}
