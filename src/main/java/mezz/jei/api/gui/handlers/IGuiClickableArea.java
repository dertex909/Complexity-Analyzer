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

package mezz.jei.api.gui.handlers;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public interface IGuiClickableArea {
	/**
	 * The hover/click area for this {@link IGuiClickableArea}.
	 * When hovered, the message from {@link #getTooltip} will be displayed.
	 * When clicked, {@link #onClick(IFocusFactory, IRecipesGui)} will be called.
	 *
	 * Area is in gui-relative coordinates (not absolute Screen coordinates).
	 */
	Rect2i getArea();

	/**
	 * Returns whether the area should render a tooltip when hovered over.
	 * The tooltip can be modified by overriding {@link #getTooltip}.
	 * This will also disable the default "Show Recipes" message.
	 *
	 * @since 11.2.2
	 */
	default boolean isTooltipEnabled() {
		return true;
	}

	/**
	 * Returns the strings to be shown on the tooltip when this area is hovered over.
	 * Return an empty list to display the default "Show Recipes" message.
	 *
	 * @deprecated use {@link #getTooltip(ITooltipBuilder)}
	 */
	@SuppressWarnings("DeprecatedIsStillUsed")
	@Deprecated(since = "19.5.4", forRemoval = true)
	default List<Component> getTooltipStrings() {
		return Collections.emptyList();
	}

	/**
	 * Add the tooltip elements to be shown on the tooltip when this area is hovered over.
	 * Leave it empty to display the default "Show Recipes" message.
	 *
	 * @since 19.5.4
	 */
	default void getTooltip(ITooltipBuilder tooltip) {
		tooltip.addAll(getTooltipStrings());
	}

	/**
	 * Called when the area is clicked.
	 * This method is passed some parameters to allow plugins to conveniently
	 * show recipes or recipe categories when their recipe area is clicked.
	 */
	void onClick(IFocusFactory focusFactory, IRecipesGui recipesGui);

	/**
	 * Helper function to create the most basic type of {@link IGuiClickableArea},
	 * which displays a recipe category on click.
	 *
	 * @since 9.5.0
	 */
	static IGuiClickableArea createBasic(int xPos, int yPos, int width, int height, RecipeType<?>... recipeTypes) {
		Rect2i area = new Rect2i(xPos, yPos, width, height);
		List<RecipeType<?>> recipeTypesList = Arrays.asList(recipeTypes);
		return new IGuiClickableArea() {
			@Override
			public Rect2i getArea() {
				return area;
			}

			@Override
			public void onClick(IFocusFactory focusFactory, IRecipesGui recipesGui) {
				recipesGui.showTypes(recipeTypesList);
			}
		};
	}
}
