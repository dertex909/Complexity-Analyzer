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

import com.mojang.serialization.Codec;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link IJeiHelpers} provides helpers and tools for addon mods.
 * <p>
 * An instance is passed to your {@link IModPlugin}'s registration methods.
 */
public interface IJeiHelpers {
	/**
	 * Helps with the implementation of GUIs.
	 */
	IGuiHelper getGuiHelper();

	/**
	 * Helps with getting itemStacks from recipes.
	 */
	IStackHelper getStackHelper();

	/**
	 * Helps with getting the mod name from a mod ID.
	 */
	IModIdHelper getModIdHelper();

	/**
	 * Helps with creating focuses.
	 *
	 * @since 9.4.0
	 */
	IFocusFactory getFocusFactory();

	/**
	 * Helps with getting colors of ingredients.
	 *
	 * @since 11.5.0
	 */
	IColorHelper getColorHelper();

	/**
	 * Helps with handling fluid ingredients on multiple platforms (Forge and Fabric).
	 *
	 * @since 10.1.0
	 */
	IPlatformFluidHelper<?> getPlatformFluidHelper();

	/**
	 * Get the registered recipe type for the given unique id.
	 * <p>
	 * This is useful for integrating with other mods that do not share their
	 * recipe types directly from their API.
	 *
	 * @see RecipeType#getUid()
	 * @since 19.11.0
	 */
	<T> Optional<RecipeType<T>> getRecipeType(ResourceLocation uid, Class<? extends T> recipeClass);

	/**
	 * Get the registered recipe type for the given unique id.
	 * <p>
	 * This is useful for integrating with other mods that do not share their
	 * recipe types directly from their API.
	 *
	 * @see RecipeType#getUid()
	 * @since 11.4.0
	 */
	Optional<RecipeType<?>> getRecipeType(ResourceLocation uid);

	/**
	 * Get all registered recipe types.
	 *
	 * @since 15.1.0
	 */
	Stream<RecipeType<?>> getAllRecipeTypes();

	/**
	 * The ingredient manager, with information about all registered ingredients.
	 *
	 * @since 11.5.0
	 */
	IIngredientManager getIngredientManager();

	/**
	 * Helps with implementing various {@link Codec}s.
	 *
	 * @since 19.9.0
	 */
	ICodecHelper getCodecHelper();

	/**
	 * The {@link IVanillaRecipeFactory} allows creation of vanilla recipes.
	 *
	 * @since 19.15.0
	 */
	IVanillaRecipeFactory getVanillaRecipeFactory();

	/**
	 * The {@link IIngredientVisibility} allows mod plugins to do advanced filtering of
	 * ingredients based on what is visible in JEI.
	 *
	 * @since 19.18.4
	 */
	IIngredientVisibility getIngredientVisibility();
}
