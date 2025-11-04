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

package mezz.jei.api.recipe.vanilla;

import mezz.jei.api.recipe.advanced.IRecipeManagerPlugin;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Builds a serializable ShapedRecipe that isn't registered with the vanilla game.
 * Useful for generating crafting recipes from {@link IRecipeManagerPlugin}.
 *
 * @since 19.15.0
 */
public interface IJeiShapedRecipeBuilder {

	/**
	 * Indicate which ingredient should be used for the given character in the recipe pattern.
	 *
	 * @see ShapedRecipeBuilder#define
	 * @since 19.15.0
	 */
	IJeiShapedRecipeBuilder define(Character character, Ingredient ingredient);

	/**
	 * Set a row of the pattern for this recipe.
	 *
	 * @see ShapedRecipeBuilder#pattern
	 * @since 19.15.0
	 */
	IJeiShapedRecipeBuilder pattern(String patternRow);

	/**
	 * Optionally set the group name of this recipe.
	 *
	 * @see ShapedRecipeBuilder#group(String)
	 * @since 19.15.0
	 */
	IJeiShapedRecipeBuilder group(String group);

	/**
	 * Create an unregistered shaped recipe based on the ingredients and pattern.
	 *
	 * @since 19.15.0
	 */
	CraftingRecipe build();
}
