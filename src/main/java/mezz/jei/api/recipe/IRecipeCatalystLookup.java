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

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.world.item.ItemStack;

import java.util.stream.Stream;

/**
 * This is a helper class for looking up recipe catalysts.
 * Create one with {@link IRecipeManager#createRecipeCatalystLookup(RecipeType)},
 * then set its properties and call {@link #get()} to get the results.
 *
 * @since 9.5.0
 */
public interface IRecipeCatalystLookup {
	/**
	 * By default, hidden results are not returned.
	 * Calling this will make this lookup include hidden recipe catalysts.
	 *
	 * @since 9.5.0
	 */
	IRecipeCatalystLookup includeHidden();

	/**
	 * Get the recipe catalyst results for this lookup.
	 *
	 * @since 9.5.0
	 */
	Stream<ITypedIngredient<?>> get();

	/**
	 * Get the recipe catalyst results of the given type for this lookup.
	 *
	 * @since 9.5.0
	 */
	<S> Stream<S> get(IIngredientType<S> ingredientType);

	/**
	 * Get the ItemStack recipe catalyst results for this lookup.
	 *
	 * @since 11.1.1
	 */
	default Stream<ItemStack> getItemStack() {
		return get(VanillaTypes.ITEM_STACK);
	}
}
