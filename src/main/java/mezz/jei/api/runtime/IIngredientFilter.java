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

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientType;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The {@link IIngredientFilter} is JEI's filter that can be set by players or controlled by mods.
 * Use this interface to get information from and interact with it.
 * Get the instance from {@link IJeiRuntime#getIngredientFilter()}.
 */
public interface IIngredientFilter {
	/**
	 * Set the search filter string for the ingredient list.
	 */
	void setFilterText(String filterText);

	/**
	 * @return the current search filter string for the ingredient list
	 */
	String getFilterText();

	/**
	 * @return a list containing all ItemStacks that match the current filter.
	 *
	 * @see #getFilteredIngredients(IIngredientType) to get a different type of ingredient, not just ItemStack.
	 *
	 * @see	IIngredientManager#getAllTypedIngredients(IIngredientType)
	 * to get all the ingredients known to JEI, not just ones currently shown by the filter.
	 *
	 * @since 11.1.1
	 */
	default List<ItemStack> getFilteredItemStacks() {
		return getFilteredIngredients(VanillaTypes.ITEM_STACK);
	}

	/**
	 * @return a list containing all ingredients that match the current filter.
	 *
	 * @see #getFilteredItemStacks() to just get ItemStacks, not all types of ingredients.
	 *
	 * @see	IIngredientManager#getAllTypedIngredients(IIngredientType)
	 * to get all the ingredients known to JEI, not just ones currently shown by the filter
	 */
	<T> List<T> getFilteredIngredients(IIngredientType<T> ingredientType);
}
