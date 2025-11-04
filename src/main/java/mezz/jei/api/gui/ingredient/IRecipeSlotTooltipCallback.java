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

package mezz.jei.api.gui.ingredient;

import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Used to add tooltips to ingredients drawn on a recipe.
 *
 * Implement a tooltip callback and add it with
 * {@link IRecipeSlotBuilder#addTooltipCallback(IRecipeSlotTooltipCallback)}
 *
 * @since 9.3.0
 * @deprecated use {@link IRecipeSlotRichTooltipCallback}
 */
@SuppressWarnings({"removal", "DeprecatedIsStillUsed"})
@Deprecated(since = "19.8.5", forRemoval = true)
@FunctionalInterface
public interface IRecipeSlotTooltipCallback {
	/**
	 * Change the tooltip for an ingredient.
	 *
	 * @since 9.3.0
	 * @deprecated use {@link IRecipeSlotRichTooltipCallback} instead
	 */
	@Deprecated(since = "19.5.4", forRemoval = true)
	void onTooltip(IRecipeSlotView recipeSlotView, List<Component> tooltip);

	/**
	 * Add to the tooltip for an ingredient.
	 *
	 * @since 19.5.4
	 * @deprecated use {@link IRecipeSlotRichTooltipCallback} instead
	 */
	@Deprecated(since = "19.8.5", forRemoval = true)
	@SuppressWarnings({"removal", "DeprecatedIsStillUsed"})
	default void onRichTooltip(IRecipeSlotView recipeSlotView, ITooltipBuilder tooltip) {
		List<Component> components = tooltip.toLegacyToComponents();
		List<Component> changedComponents = new ArrayList<>(components);
		onTooltip(recipeSlotView, changedComponents);
		if (!components.equals(changedComponents)) {
			tooltip.removeAll(components);
			tooltip.addAll(changedComponents);
		}
	}
}
