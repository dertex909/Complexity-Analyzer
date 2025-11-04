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

package mezz.jei.api.helpers;

import mezz.jei.api.ingredients.ITypedIngredient;
import org.jetbrains.annotations.Nullable;

import mezz.jei.api.ingredients.subtypes.UidContext;
import net.minecraft.world.item.ItemStack;

import mezz.jei.api.ingredients.subtypes.ISubtypeManager;

/**
 * Helps get ItemStacks from common formats used in recipes.
 * Get the instance from {@link IJeiHelpers#getStackHelper()}.
 */
public interface IStackHelper {
	/**
	 * Gets the unique identifier for a stack, ignoring NBT on items without subtypes, and uses the {@link ISubtypeManager}.
	 * If two unique identifiers are equal, then the items can be considered equivalent.
	 *
	 * @since 19.9.0
	 */
	Object getUidForStack(ItemStack stack, UidContext context);

	/**
	 * Gets the unique identifier for a stack, ignoring NBT on items without subtypes, and uses the {@link ISubtypeManager}.
	 * If two unique identifiers are equal, then the items can be considered equivalent.
	 *
	 * @since 19.19.4
	 */
	Object getUidForStack(ITypedIngredient<ItemStack> stack, UidContext context);

	/**
	 * Similar to ItemStack.areItemStacksEqual but ignores NBT on items without subtypes, and uses the {@link ISubtypeManager}
	 * @since 7.3.0
	 */
	boolean isEquivalent(@Nullable ItemStack lhs, @Nullable ItemStack rhs, UidContext context);

	/**
	 * Gets the unique identifier for a stack, ignoring NBT on items without subtypes, and uses the {@link ISubtypeManager}.
	 * If two unique identifiers are equal, then the items can be considered equivalent.
	 * @since 7.6.1
	 *
	 * @deprecated use {@link #getUidForStack(ItemStack, UidContext)}
	 */
	@Deprecated(since = "19.9.0", forRemoval = true)
	String getUniqueIdentifierForStack(ItemStack stack, UidContext context);
}
