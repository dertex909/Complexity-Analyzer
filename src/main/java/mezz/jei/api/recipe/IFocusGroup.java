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
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.stream.Stream;

/**
 * Represents multiple {@link IFocus} used for recipe lookups.
 *
 * Helper functions make this simpler to use than a regular `List<IFocus<?>>`
 * when you only care about certain types of focuses.
 *
 * Create an instance with {@link IFocusFactory#createFocusGroup}
 * or get the empty one from {@link IFocusFactory#getEmptyFocusGroup}
 *
 * @since 9.4.0
 */
public interface IFocusGroup {
	/**
	 * When the player is looking at all recipes in a category,
	 * there will be no focused ingredient and this group will be empty.
	 *
	 * @since 9.4.0
	 */
	boolean isEmpty();

	/**
	 * Get a raw list of all the current focuses.
	 *
	 * @since 9.4.0
	 */
	List<IFocus<?>> getAllFocuses();

	/**
	 * Get a stream of the current focuses filtered by role.
	 *
	 * @since 9.4.0
	 */
	Stream<IFocus<?>> getFocuses(RecipeIngredientRole role);

	/**
	 * Get a stream of the current focuses filtered by type.
	 *
	 * @since 9.4.0
	 */
	<T> Stream<IFocus<T>> getFocuses(IIngredientType<T> ingredientType);

	/**
	 * Get a stream of the current focuses filtered by type and role.
	 *
	 * @since 9.4.0
	 */
	<T> Stream<IFocus<T>> getFocuses(IIngredientType<T> ingredientType, RecipeIngredientRole role);

	/**
	 * Get a stream of the current focuses filtered to only ItemStacks.
	 *
	 * @since 11.1.1
	 */
	default Stream<IFocus<ItemStack>> getItemStackFocuses() {
		return getFocuses(VanillaTypes.ITEM_STACK);
	}

	/**
	 * Get a stream of the current focuses filtered by ItemStack and role.
	 *
	 * @since 11.1.1
	 */
	default Stream<IFocus<ItemStack>> getItemStackFocuses(RecipeIngredientRole role) {
		return getFocuses(VanillaTypes.ITEM_STACK, role);
	}

}
