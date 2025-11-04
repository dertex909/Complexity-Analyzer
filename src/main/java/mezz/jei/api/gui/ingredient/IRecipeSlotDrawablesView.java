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
import mezz.jei.api.recipe.RecipeIngredientRole;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents all the drawn ingredients in slots that are part of a recipe.
 *
 * This view is meant as a source of information for drawing, positioning, and tooltips.
 *
 * @see IRecipeSlotsView for a view with less access to drawable properties of the slots.
 *
 * @since 19.19.3
 */
public interface IRecipeSlotDrawablesView {
	/**
	 * Get all slots for a recipe.
	 *
	 * @since 19.19.3
	 */
	@Unmodifiable
	List<IRecipeSlotDrawable> getSlots();

	/**
	 * Get the list of slots for the given {@link RecipeIngredientRole} for a recipe.
	 *
	 * @since 19.19.3
	 */
	default List<IRecipeSlotDrawable> getSlots(RecipeIngredientRole role) {
		List<IRecipeSlotDrawable> list = new ArrayList<>();
		for (IRecipeSlotDrawable slotView : getSlots()) {
			if (slotView.getRole() == role) {
				list.add(slotView);
			}
		}
		return list;
	}

	/**
	 * Get a recipe slot by its name set with {@link IRecipeSlotBuilder#setSlotName(String)}.
	 *
	 * @since 19.19.3
	 */
	default Optional<IRecipeSlotDrawable> findSlotByName(String slotName) {
		return getSlots().stream()
			.filter(slot ->
				slot.getSlotName()
					.map(slotName::equals)
					.orElse(false)
			)
			.findFirst();
	}
}
