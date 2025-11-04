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

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;

/**
 * Tell JEI how to interpret Components and capabilities when comparing and looking up ingredients.
 *
 * If your ingredient has subtypes that depend on Components or capabilities,
 * use this so JEI can tell those subtypes apart.
 */
public interface ISubtypeRegistration {

	/**
	 * Add an interpreter to allow JEI to understand the differences between ingredient subtypes.
	 * This interpreter should account for Components and anything else
	 * that's relevant to differentiating the ingredient's subtypes.
	 *
	 * @param type        the ingredient type (for example {@link VanillaTypes#ITEM_STACK}
	 * @param base        the base of the ingredient that has subtypes (for example, {@link Items#ENCHANTED_BOOK}).
	 *                       All ingredients with this base will use the given interpreter.
	 * @param interpreter the interpreter for the ingredient's subtypes
	 *
	 * @since 19.9.0
	 */
	<B, I> void registerSubtypeInterpreter(IIngredientTypeWithSubtypes<B, I> type, B base, ISubtypeInterpreter<I> interpreter);

	/**
	 * Add an interpreter to allow JEI to understand the differences between ingredient subtypes.
	 * This interpreter should account for Components and anything else
	 * that's relevant to differentiating the ingredient's subtypes.
	 *
	 * @param item        the item base of the ItemStack that has subtypes (for example, {@link Items#ENCHANTED_BOOK}).
	 *                       All ItemStacks with this base will use the given interpreter.
	 * @param interpreter the interpreter for the ItemStack's subtypes
	 *
	 * @since 19.9.0
	 */
	default void registerSubtypeInterpreter(Item item, ISubtypeInterpreter<ItemStack> interpreter) {
		registerSubtypeInterpreter(VanillaTypes.ITEM_STACK, item, interpreter);
	}

	/**
	 * Add an interpreter to allow JEI to understand the differences between ingredient subtypes.
	 * This interpreter should account for Components and anything else
	 * that's relevant to differentiating the ingredient's subtypes.
	 *
	 * @param type        the ingredient type (for example {@link VanillaTypes#ITEM_STACK}
	 * @param base        the base of the ingredient that has subtypes (for example, {@link Items#ENCHANTED_BOOK}).
	 *                       All ingredients with this base will use the given interpreter.
	 * @param interpreter the interpreter for the ingredient's subtypes
	 *
	 * @since 9.7.0
	 *
	 * @deprecated use {@link #registerSubtypeInterpreter(IIngredientTypeWithSubtypes, Object, ISubtypeInterpreter)}
	 */
	@SuppressWarnings("removal")
	@Deprecated(since = "19.9.0", forRemoval = true)
	<B, I> void registerSubtypeInterpreter(IIngredientTypeWithSubtypes<B, I> type, B base, mezz.jei.api.ingredients.subtypes.IIngredientSubtypeInterpreter<I> interpreter);

	/**
	 * Add an interpreter to allow JEI to understand the differences between ingredient subtypes.
	 * This interpreter should account for Components and anything else
	 * that's relevant to differentiating the ingredient's subtypes.
	 *
	 * @param item        the item base of the ItemStack that has subtypes (for example, {@link Items#ENCHANTED_BOOK}).
	 *                       All ItemStacks with this base will use the given interpreter.
	 * @param interpreter the interpreter for the ItemStack's subtypes
	 *
	 * @since 11.1.1
	 *
	 * @deprecated use {@link #registerSubtypeInterpreter(Item, ISubtypeInterpreter)}
	 */
	@SuppressWarnings("removal")
	@Deprecated(since = "19.9.0", forRemoval = true)
	default void registerSubtypeInterpreter(Item item, mezz.jei.api.ingredients.subtypes.IIngredientSubtypeInterpreter<ItemStack> interpreter) {
		registerSubtypeInterpreter(VanillaTypes.ITEM_STACK, item, interpreter);
	}

}
