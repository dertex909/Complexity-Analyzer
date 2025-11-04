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

package mezz.jei.api.gui.ingredient;

import java.util.List;

import com.mojang.datafixers.util.Pair;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

/**
 * Helps set crafting-grid-style layouts.
 * This places smaller recipes in the grid in a consistent way.
 *
 * This is passed to plugins that implement
 * {@link ICraftingCategoryExtension#setRecipe(RecipeHolder, IRecipeLayoutBuilder, ICraftingGridHelper, IFocusGroup)}
 * to help them override the default behavior.
 */
public interface ICraftingGridHelper {
	/**
	 * Create and place input ingredients onto the crafting grid in a consistent way.
	 * For shapeless recipes, use a width and height of 0.
	 *
	 * @since 19.16.2
	 */
	List<IRecipeSlotBuilder> createAndSetNamedIngredients(IRecipeLayoutBuilder builder, List<Pair<String, Ingredient>> namedIngredients, int width, int height);

	/**
	 * Create and place input ingredients onto the crafting grid in a consistent way.
	 * For shapeless recipes, use a width and height of 0.
	 *
	 * @since 19.16.2
	 */
	void createAndSetIngredients(IRecipeLayoutBuilder builder, List<Ingredient> ingredients, int width, int height);

	/**
	 * Create and place input ingredients onto the crafting grid in a consistent way.
	 * For shapeless recipes, use a width and height of 0.
	 *
	 * @since 19.16.3
	 */
	default List<IRecipeSlotBuilder> createAndSetNamedInputs(IRecipeLayoutBuilder builder, List<@Nullable Pair<String, List<@Nullable ItemStack>>> namedInputs, int width, int height) {
		return createAndSetNamedInputs(builder, VanillaTypes.ITEM_STACK, namedInputs, width, height);
	}

	/**
	 * Create and place input ingredients onto the crafting grid in a consistent way.
	 * For shapeless recipes, use a width and height of 0.
	 *
	 * @since 19.16.3
	 */
	<T> List<IRecipeSlotBuilder> createAndSetNamedInputs(IRecipeLayoutBuilder builder, IIngredientType<T> ingredientType, List<@Nullable Pair<String, List<@Nullable T>>> namedInputs, int width, int height);

	/**
	 * Create and place input ingredients onto the crafting grid in a consistent way.
	 * For shapeless recipes, use a width and height of 0.
	 *
	 * @see #createAndSetInputs(IRecipeLayoutBuilder, IIngredientType, List, int, int) to set other ingredient types.
	 * @since 11.1.1
	 */
	default List<IRecipeSlotBuilder> createAndSetInputs(IRecipeLayoutBuilder builder, List<@Nullable List<@Nullable ItemStack>> inputs, int width, int height) {
		return createAndSetInputs(builder, VanillaTypes.ITEM_STACK, inputs, width, height);
	}

	/**
	 * Create and place input ingredients onto the crafting grid in a consistent way.
	 * For shapeless recipes, use a width and height of 0.
	 *
	 * @since 11.0.2
	 */
	<T> List<IRecipeSlotBuilder> createAndSetInputs(IRecipeLayoutBuilder builder, IIngredientType<T> ingredientType, List<@Nullable List<@Nullable T>> inputs, int width, int height);

	/**
	 * Place input ingredients onto the slot builders in a consistent way.
	 * For shapeless recipes, use a width and height of 0.
	 *
	 * @since 9.3.2
	 */
	<T> void setInputs(List<IRecipeSlotBuilder> slotBuilders, IIngredientType<T> ingredientType, List<@Nullable List<@Nullable T>> inputs, int width, int height);

	/**
	 * Place output ItemStacks at the right location.
	 *
	 * @see #createAndSetOutputs(IRecipeLayoutBuilder, IIngredientType, List) to set other ingredient types.
	 * @since 11.1.1
	 */
	default IRecipeSlotBuilder createAndSetOutputs(IRecipeLayoutBuilder builder, @Nullable List<@Nullable ItemStack> outputs) {
		return createAndSetOutputs(builder, VanillaTypes.ITEM_STACK, outputs);
	}

	/**
	 * Place output ingredients at the right location.
	 *
	 * @since 11.0.2
	 */
	<T> IRecipeSlotBuilder createAndSetOutputs(IRecipeLayoutBuilder builder, IIngredientType<T> ingredientType, @Nullable List<@Nullable T> outputs);
}
