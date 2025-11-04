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

import org.jetbrains.annotations.Nullable;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;

import java.util.List;
import java.util.Optional;

/**
 * The {@link IIngredientListOverlay} is JEI's gui that displays all the ingredients next to an open container gui.
 * Use this interface to get information from and interact with it.
 * Get the instance from {@link IJeiRuntime#getIngredientListOverlay()}.
 */
public interface IIngredientListOverlay {
	/**
	 * @return the ingredient that's currently under the mouse.
	 * @since 9.3.0
	 */
	Optional<ITypedIngredient<?>> getIngredientUnderMouse();

	/**
	 * @return the ingredient that's currently under the mouse if it matches the given type, or null if there is none.
	 * @since 7.0.1
	 */
	@Nullable
	<T> T getIngredientUnderMouse(IIngredientType<T> ingredientType);

	/**
	 * @return true if the ingredient list is currently displayed.
	 *
	 * @since 10.1.0
	 */
	boolean isListDisplayed();

	/**
	 * @return true if the text box is focused by the player.
	 */
	boolean hasKeyboardFocus();

	/**
	 * @return a list containing all currently visible ingredients. If JEI is hidden, the list will be empty.
	 */
	<T> List<T> getVisibleIngredients(IIngredientType<T> ingredientType);
}
