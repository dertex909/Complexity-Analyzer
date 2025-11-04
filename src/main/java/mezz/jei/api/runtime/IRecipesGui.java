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

import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeType;

import java.util.List;
import java.util.Optional;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.RecipeIngredientRole;

import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.screens.Screen;

/**
 * JEI's gui for displaying recipes. Use this interface to open recipes.
 * Get the instance from {@link IJeiRuntime#getRecipesGui()}.
 */
public interface IRecipesGui {
	/**
	 * Show recipes for an {@link IFocus}.
	 * Opens the {@link IRecipesGui} if recipes are found and the gui is closed.
	 *
	 * @see IFocusFactory#createFocus(RecipeIngredientRole, IIngredientType, Object)
	 */
	default <V> void show(IFocus<V> focus) {
		show(List.of(focus));
	}

	/**
	 * Show recipes for multiple {@link IFocus}.
	 * Opens the {@link IRecipesGui} if recipes are found and the gui is closed.
	 *
	 * @see IFocusFactory#createFocus(RecipeIngredientRole, IIngredientType, Object)
	 *
	 * @since 9.3.0
	 */
	void show(List<IFocus<?>> focuses);

	/**
	 * Show entire categories of recipes.
	 *
	 * @param recipeTypes a list of recipe types to display, in order. Must not be empty.
	 */
	void showTypes(List<RecipeType<?>> recipeTypes);

	/**
	 * Show specific recipes for one recipe category, with multiple {@link IFocus}.
	 * Opens the {@link IRecipesGui} if recipes are valid and the gui is closed.
	 *
	 * @see IFocusFactory#createFocus(RecipeIngredientRole, IIngredientType, Object)
	 *
	 * @since 19.1.0
	 */
	<T> void showRecipes(IRecipeCategory<T> recipeCategory, List<T> recipes, List<IFocus<?>> focuses);

	/**
	 * @return the ingredient that's currently under the mouse in this gui
	 */
	<T> Optional<T> getIngredientUnderMouse(IIngredientType<T> ingredientType);

	/**
	 * Get the screen that the {@link IRecipesGui} was opened from.
	 * When the {@link IRecipesGui} is closed, it will re-open the parent screen.
	 *
	 * If the {@link IRecipesGui} is not open, this will return {@link Optional#empty()}.
	 *
	 * @since 19.20.0
	 */
	Optional<Screen> getParentScreen();
}
