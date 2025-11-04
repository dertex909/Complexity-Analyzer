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

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Gets subtype information from ingredients that have subtype interpreters.
 * <p>
 * Add subtypes for your ingredients with {@link ISubtypeRegistration#registerSubtypeInterpreter(IIngredientTypeWithSubtypes, Object, ISubtypeInterpreter)}.
 */
public interface ISubtypeManager {
	/**
	 * Get the data from an ItemStack that is relevant to comparing and telling subtypes apart.
	 * Returns null if the ItemStack has no information used for subtypes.
	 *
	 * @since 19.9.0
	 */
	@Nullable
	default Object getSubtypeData(ItemStack ingredient, UidContext context) {
		return getSubtypeData(VanillaTypes.ITEM_STACK, ingredient, context);
	}

	/**
	 * Get the data from an ingredient that is relevant to comparing and telling subtypes apart.
	 * Returns null if the ingredient has no information used for subtypes.
	 *
	 * @since 19.9.0
	 */
	@Nullable
	<T> Object getSubtypeData(IIngredientTypeWithSubtypes<?, T> ingredientType, T ingredient, UidContext context);

	/**
	 * Get the data from a typed ingredient that is relevant to comparing and telling subtypes apart.
	 * Returns null if the typed ingredient has no information used for subtypes.
	 *
	 * @since 19.19.4
	 */
	@Nullable
	<B, T> Object getSubtypeData(IIngredientTypeWithSubtypes<B, T> ingredientType, ITypedIngredient<T> typedIngredient, UidContext context);

	/**
	 * Get the data from an ItemStack that is relevant to comparing and telling subtypes apart.
	 * Returns {@link IIngredientSubtypeInterpreter#NONE} if the ItemStack has no information used for subtypes.
	 *
	 * @since 11.1.1
	 *
	 * @deprecated use {@link #getSubtypeData(ItemStack, UidContext)}
	 */
	@SuppressWarnings({"removal", "DeprecatedIsStillUsed"})
	@Deprecated(since = "19.9.0", forRemoval = true)
	default String getSubtypeInfo(ItemStack ingredient, UidContext context) {
		return getSubtypeInfo(VanillaTypes.ITEM_STACK, ingredient, context);
	}

	/**
	 * Get the data from an ingredient that is relevant to comparing and telling subtypes apart.
	 * Returns {@link IIngredientSubtypeInterpreter#NONE} if the ingredient has no information used for subtypes.
	 *
	 * @since 9.7.0
	 *
	 * @deprecated use {@link #getSubtypeData(IIngredientTypeWithSubtypes, Object, UidContext)}
	 */
	@SuppressWarnings({"removal", "DeprecatedIsStillUsed"})
	@Deprecated(since = "19.9.0", forRemoval = true)
	<T> String getSubtypeInfo(IIngredientTypeWithSubtypes<?, T> ingredientType, T ingredient, UidContext context);

	/**
	 * Return true if the given ItemStack can have subtypes.
	 * For example in the vanilla game an enchanted book may have subtypes, but an apple does not.
	 *
	 * @see ISubtypeRegistration#registerSubtypeInterpreter
	 * @see ISubtypeManager#getSubtypeData
	 *
	 * @since 19.23.0
	 */
	default boolean hasSubtypes(ItemStack ingredient) {
		return hasSubtypes(VanillaTypes.ITEM_STACK, ingredient);
	}

	/**
	 * Return true if the given ingredient can have subtypes.
	 * For example in the vanilla game an enchanted book may have subtypes, but an apple does not.
	 *
	 * @see ISubtypeRegistration#registerSubtypeInterpreter
	 * @see ISubtypeManager#getSubtypeData
	 *
	 * @since 19.3.0
	 */
	<T, B> boolean hasSubtypes(IIngredientTypeWithSubtypes<B, T> ingredientType, T ingredient);
}
