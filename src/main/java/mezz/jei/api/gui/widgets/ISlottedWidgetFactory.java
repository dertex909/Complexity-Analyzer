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

package mezz.jei.api.gui.widgets;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;

import java.util.List;

/**
 * A widget factory that creates a slotted widget instance that handles specific slots.
 *
 * Assign it slots with {@link IRecipeLayoutBuilder#addSlotToWidget(RecipeIngredientRole, ISlottedWidgetFactory)}
 * and then JEI will call {@link #createWidgetForSlots} after all the slots are built.
 *
 * @since 19.7.0
 * @deprecated there are easier ways to create slotted widgets now. Use {@link IRecipeExtrasBuilder#addSlottedWidget}.
 */
@SuppressWarnings({"DeprecatedIsStillUsed", "removal"})
@Deprecated(since = "19.19.3", forRemoval = true)
@FunctionalInterface
public interface ISlottedWidgetFactory<R> {
	/**
	 * Create a widget instance for the given recipe and slots.
	 * This will be called when the slots are built and ready.
	 *
	 * @param recipe the recipe to be used by
	 * @param slots created and assigned to this widget factory with
	 * 			{@link IRecipeLayoutBuilder#addSlotToWidget(RecipeIngredientRole, ISlottedWidgetFactory)}
	 *
	 * @since 19.7.0
	 */
	@Deprecated(since = "19.19.3", forRemoval = true)
	@SuppressWarnings("removal")
	void createWidgetForSlots(IRecipeExtrasBuilder builder, R recipe, List<IRecipeSlotDrawable> slots);
}
