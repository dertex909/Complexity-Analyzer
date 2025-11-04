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

package mezz.jei.api.recipe.category.extensions;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IAdvancedRegistration;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The {@link IRecipeCategoryDecorator} allows further customization of recipe categories.
 * It can be used to draw additional elements or tooltips on recipes, even of other mods.
 * <p>
 * Register it with {@link IAdvancedRegistration#addRecipeCategoryDecorator(RecipeType, IRecipeCategoryDecorator)}.
 *
 * @since 15.1.0
 */
public interface IRecipeCategoryDecorator<T> {
	/**
	 * Draw extras or additional info about the recipe after the {@link IRecipeCategory} that
	 * this decorator is registered to has drawn.
	 * Tooltips are handled by {@link #decorateTooltips(ITooltipBuilder, Object, IRecipeCategory, IRecipeSlotsView, double, double)}.
	 *
	 * @param recipe          the current recipe being drawn.
	 * @param recipeCategory  the recipe category of the recipe.
	 * @param recipeSlotsView a view of the current recipe slots being drawn.
	 * @param guiGraphics     the current {@link GuiGraphics} for rendering.
	 * @param mouseX          the X position of the mouse, relative to the recipe.
	 * @param mouseY          the Y position of the mouse, relative to the recipe.
	 *
	 * @see IDrawable for a simple class for drawing things.
	 * @see IGuiHelper for useful functions.
	 * @see IRecipeSlotsView for information about the ingredients that are currently being drawn.
	 *
	 * @since 15.1.0
	 */
	default void draw(T recipe, IRecipeCategory<T> recipeCategory, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {

	}

	/**
	 * Allows modifying of and adding to existing tooltips added by the {@link IRecipeCategory} that
	 * this decorator is registered to.
	 * To avoid removing tooltips from the category itself, make sure to return original list with your
	 * edits and additions.
	 *
	 * @param tooltips        the existing tooltip strings.
	 * @param recipe          the current recipe being drawn.
	 * @param recipeCategory  the recipe category of the recipe.
	 * @param recipeSlotsView a view of the current recipe slots being drawn.
	 * @param mouseX          the X position of the mouse, relative to the recipe.
	 * @param mouseY          the Y position of the mouse, relative to the recipe.
	 * @return tooltip strings. If there is no tooltip at this position, return an empty list.
	 *
	 * @since 15.1.0
	 * @deprecated use {@link #decorateTooltips(ITooltipBuilder, Object, IRecipeCategory, IRecipeSlotsView, double, double)}
	 */
	@Deprecated(since = "19.5.4", forRemoval = true)
	default List<Component> decorateExistingTooltips(List<Component> tooltips, T recipe, IRecipeCategory<T> recipeCategory, IRecipeSlotsView recipeSlotsView, double mouseX, double mouseY) {
		return tooltips;
	}

	/**
	 * Allows adding to existing tooltips added by the {@link IRecipeCategory} that
	 * this decorator is registered to.
	 *
	 * @param tooltip         the existing tooltip strings.
	 * @param recipe          the current recipe being drawn.
	 * @param recipeCategory  the recipe category of the recipe.
	 * @param recipeSlotsView a view of the current recipe slots being drawn.
	 * @param mouseX          the X position of the mouse, relative to the recipe.
	 * @param mouseY          the Y position of the mouse, relative to the recipe.
	 *
	 * @since 19.5.4
	 */
	default void decorateTooltips(ITooltipBuilder tooltip, T recipe, IRecipeCategory<T> recipeCategory, IRecipeSlotsView recipeSlotsView, double mouseX, double mouseY) {

	}
}
