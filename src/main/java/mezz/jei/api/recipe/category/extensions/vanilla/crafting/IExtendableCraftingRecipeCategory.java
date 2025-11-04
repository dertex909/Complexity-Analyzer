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

package mezz.jei.api.recipe.category.extensions.vanilla.crafting;

import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import net.minecraft.world.item.crafting.CraftingRecipe;

/**
 * Allows extending the vanilla crafting recipe category,
 * to support custom recipes classes that cannot be handled by default.
 *
 * Get the instance from {@link IVanillaCategoryExtensionRegistration#getCraftingCategory()}
 *
 * @since 16.0.0
 */
public interface IExtendableCraftingRecipeCategory {
	/**
	 * Add an extension that handles a subset of the recipes in the recipe category.
	 *
	 * @param recipeClass  the subset class of crafting recipes to handle
	 * @param extension    an extension for handling these recipes
	 * @since 16.0.0
	 */
	<R extends CraftingRecipe> void addExtension(Class<? extends R> recipeClass, ICraftingCategoryExtension<R> extension);
}
