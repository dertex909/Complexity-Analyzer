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

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Helper class for getting mod names from their modIds.
 * Get an instance from {@link IJeiHelpers#getModIdHelper()}
 */
public interface IModIdHelper {
	/**
	 * Get the mod name for its modId
	 */
	String getModNameForModId(String modId);

	/**
	 * Returns true if JEI is configured to display mod names.
	 */
	boolean isDisplayingModNameEnabled();

	/**
	 * Returns the mod name with color formatting, as specified in JEI's config. (default is blue italic)
	 */
	String getFormattedModNameForModId(String modId);

	/**
	 * Returns alternative mod names, used for searching for a mod by a different name.
	 * @since 17.1.0
	 */
	Set<String> getModAliases(String modId);

	/**
	 * Adds the mod name to the tooltip with color formatting.
	 *
	 * If {@link #isDisplayingModNameEnabled()} is false,
	 * this will just return the tooltip without adding the mod name.
	 *
	 * @deprecated use {@link #getModNameForTooltip(ITypedIngredient)}
	 */
	@Deprecated(since = "19.5.4", forRemoval = true)
	<T> List<Component> addModNameToIngredientTooltip(List<Component> tooltip, T ingredient, IIngredientHelper<T> ingredientHelper);

	/**
	 * Adds the mod name to the tooltip with color formatting.
	 *
	 * If {@link #isDisplayingModNameEnabled()} is false,
	 * this will just return the tooltip without adding the mod name.
	 *
	 * @since 11.5.0
	 *
	 * @deprecated use {@link #getModNameForTooltip(ITypedIngredient)}
	 */
	@Deprecated(since = "19.5.4", forRemoval = true)
	<T> List<Component> addModNameToIngredientTooltip(List<Component> tooltip, ITypedIngredient<T> typedIngredient);

	/**
	 * Gets the mod name for the tooltip with color formatting.
	 *
	 * If {@link #isDisplayingModNameEnabled()} is false,
	 * or another mod already adds the mod name, this will return {@link Optional#empty}.
	 *
	 * @since 19.5.1
	 */
	<T> Optional<Component> getModNameForTooltip(ITypedIngredient<T> typedIngredient);
}
