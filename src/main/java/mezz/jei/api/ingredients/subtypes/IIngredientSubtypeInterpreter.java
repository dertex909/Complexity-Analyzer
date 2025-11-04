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

import mezz.jei.api.registration.ISubtypeRegistration;

/**
 * A subtype interpreter tells JEI how to create unique ids for ingredients.
 *
 * For example, an ItemStack may have some NBT that is used to create many subtypes,
 * and other NBT that is used for electric charge that can be ignored.
 * You can tell JEI how to interpret these differences by implementing an
 * {@link IIngredientSubtypeInterpreter} and registering it with JEI in
 * {@link ISubtypeRegistration}
 *
 * @since 7.6.2
 * @deprecated use {@link ISubtypeInterpreter} instead.
 */
@Deprecated(since = "19.9.0", forRemoval = true)
@SuppressWarnings("DeprecatedIsStillUsed")
@FunctionalInterface
public interface IIngredientSubtypeInterpreter<T> {
	@Deprecated(since = "19.9.0", forRemoval = true)
	String NONE = "";

	/**
	 * Get the data from an ingredient that is relevant to telling subtypes apart in the given context.
	 * This should account for nbt, and anything else that's relevant.
	 *
	 * {@link UidContext} can be used to give different subtype information depending on the given context.
	 * Most cases will return the same value for all contexts, and it can usually be ignored.
	 *
	 * Return {@link #NONE} if there is no data used for subtypes.
	 *
	 * @deprecated use {@link ISubtypeInterpreter#getSubtypeData(Object, UidContext)} instead.
	 */
	@Deprecated(since = "19.9.0", forRemoval = true)
	String apply(T ingredient, UidContext context);
}
