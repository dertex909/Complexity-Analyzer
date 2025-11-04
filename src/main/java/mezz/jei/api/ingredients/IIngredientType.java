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

package mezz.jei.api.ingredients;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.ICodecHelper;
import mezz.jei.api.registration.IModIngredientRegistration;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A type of ingredient (i.e. ItemStack, FluidStack, etc.) handled by JEI.
 * Register new types with {@link IModIngredientRegistration#register}
 *
 * @see VanillaTypes for the built-in vanilla type {@link VanillaTypes#ITEM_STACK}
 */
@FunctionalInterface
public interface IIngredientType<T> {
	/**
	 * @return The class of the ingredient for this type.
	 */
	Class<? extends T> getIngredientClass();

	/**
	 * @return The unique ID for this type, used for serialization to and from disk.
	 *
	 * @see ICodecHelper#getIngredientTypeCodec()
	 *
	 * @since 19.1.0
	 */
	default String getUid() {
		Class<? extends T> ingredientClass = getIngredientClass();
		return ingredientClass.getName();
	}

	/**
	 * Helper to cast an unknown ingredient to this type if it matches.
	 *
	 * @since 11.5.0
	 */
	default Optional<T> castIngredient(@Nullable Object ingredient) {
		Class<? extends T> ingredientClass = getIngredientClass();
		if (ingredientClass.isInstance(ingredient)) {
			return Optional.of(ingredientClass.cast(ingredient));
		}
		return Optional.empty();
	}

	/**
	 * Helper to cast an unknown ingredient to this type if it matches.
	 *
	 * @since 19.19.5
	 */
	@Nullable
	default T getCastIngredient(@Nullable Object ingredient) {
		Class<? extends T> ingredientClass = getIngredientClass();
		if (ingredientClass.isInstance(ingredient)) {
			return ingredientClass.cast(ingredient);
		}
		return null;
	}
}
