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
import org.jetbrains.annotations.Nullable;

/**
 * A subtype interpreter tells JEI how to create unique ids for ingredients.
 *
 * For example, an ItemStack may have some NBT that is used to create many subtypes,
 * and other NBT that is used for electric charge that can be ignored.
 * You can tell JEI how to interpret these differences by implementing an
 * {@link ISubtypeInterpreter} and registering it with JEI in {@link ISubtypeRegistration}
 *
 * @since 19.9.0
 */
public interface ISubtypeInterpreter<T> {
	/**
	 * Get the data from an ingredient that is relevant to telling subtypes of a given ingredient apart.
	 * This should account for components, and anything else that's relevant.
	 *
	 * The returned value must implement {@link Object#equals} and {@link Object#hashCode}
	 * for use as map keys and for comparisons with other objects.
	 *
	 * {@link UidContext} can be used to give different subtype information depending on the given context.
	 * Most cases will return the same value for all contexts, and it can usually be ignored.
	 *
	 * Return null if there is no data used for subtypes.
	 *
	 * @since 19.9.0
	 */
	@Nullable
	Object getSubtypeData(T ingredient, UidContext context);

	/**
	 * Provide a legacy string uid like from {@link IIngredientSubtypeInterpreter#apply},
	 * used for loading old config files that used string UIDs for serialization.
	 *
	 * Return an empty string if there is no data used for subtypes or if you have no legacy data to support.
	 *
	 * @since 19.9.0
	 */
	@SuppressWarnings({"removal"})
	@Deprecated(since = "19.9.0")
	String getLegacyStringSubtypeInfo(T ingredient, UidContext context);
}
