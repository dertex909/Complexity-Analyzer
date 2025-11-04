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
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientType;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;

/**
 * Allows adding extra ingredients (including ItemStack and FluidStack) for any registered ingredient type.
 *
 * This is intended to be used to add ingredients to another mod's type.
 * If you want to add ingredients to your own custom type,
 * pass them to {@link IModIngredientRegistration#register} instead.
 *
 * This is given to your {@link IModPlugin#registerExtraIngredients(IExtraIngredientRegistration)}.
 *
 * @since 19.18.0
 */
public interface IExtraIngredientRegistration {
	/**
	 * Add extra ItemStacks that are not already in the creative menu.
	 *
	 * @param extraItemStacks A collection of extra ItemStacks to be displayed in the ingredient list.
	 *
	 * @since 19.18.0
	 */
	default void addExtraItemStacks(Collection<ItemStack> extraItemStacks) {
		addExtraIngredients(VanillaTypes.ITEM_STACK, extraItemStacks);
	}

	/**
	 * Add extra ingredients to an existing ingredient type.
	 *
	 * @param ingredientType     The type of the ingredient.
	 *                           This must already be registered with {@link IModIngredientRegistration#register} by another mod.
	 * @param extraIngredients   A collection of extra ingredients to be displayed in the ingredient list.
	 *
	 * @since 19.18.0
	 */
	<V> void addExtraIngredients(
		IIngredientType<V> ingredientType,
		Collection<V> extraIngredients
	);
}
