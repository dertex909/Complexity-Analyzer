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

package mezz.jei.api.gui.builder;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Helper factory for creating {@link IClickableIngredient}.
 *
 * Passed to mods in methods that need to create clickable ingredients, like
 * {@link IGuiContainerHandler#getClickableIngredientUnderMouse(IClickableIngredientFactory, AbstractContainerScreen, double, double)}
 *
 * An instance is also available from {@link IIngredientManager#getClickableIngredientFactory()}.
 *
 * @since 19.23.0
 */
public interface IClickableIngredientFactory {
	/**
	 * Create a clickable ingredient builder with the given ItemStack.
	 *
	 * @since 19.23.0
	 */
	default IBuilder<ItemStack> createBuilder(ItemStack itemStack) {
		return createBuilder(VanillaTypes.ITEM_STACK, itemStack);
	}

	/**
	 * Create a clickable ingredient builder with the given typed ingredient.
	 *
	 * @since 19.23.0
	 */
	<T> IBuilder<T> createBuilder(ITypedIngredient<T> value);

	/**
	 * Create a clickable ingredient builder with the given typed ingredient.
	 *
	 * @since 19.23.0
	 */
	<T> IBuilder<T> createBuilder(IIngredientType<T> ingredientType, T ingredient);

	/**
	 * An intermediate builder for clickable ingredients.
	 * It has an ingredient and needs an area in order to build the clickable ingredient.
	 *
	 * @since 19.23.0
	 */
	interface IBuilder<T> {
		/**
		 * Create a clickable ingredient with the given area
		 *
		 * @since 19.23.0
		 */
		Optional<IClickableIngredient<T>> buildWithArea(int x, int y, int width, int height);

		/**
		 * Create a clickable ingredient with the given area
		 *
		 * @since 19.23.0
		 */
		Optional<IClickableIngredient<T>> buildWithArea(Rect2i area);
	}
}
