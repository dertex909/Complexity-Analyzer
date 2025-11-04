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

package mezz.jei.api.recipe.vanilla;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * There is no vanilla registry of Grindstone Recipes,
 * so JEI creates these Grindstone recipes to use internally.
 *
 * Create your own with {@link IVanillaRecipeFactory#createGrindstoneRecipe}
 * @since 19.22.1
 */
public interface IJeiGrindstoneRecipe {
	/**
	 * Get the inputs that go into the top slot of the Grindstone.
	 *
	 * @since 19.22.1
	 */
	@Unmodifiable
	List<ItemStack> getTopInputs();

	/**
	 * Get the inputs that go into the bottom slot of the Grindstone.
	 *
	 * @since 19.22.1
	 */
	@Unmodifiable
	List<ItemStack> getBottomInputs();

	/**
	 * Get the outputs of the Grindstone recipe.
	 *
	 * @since 19.22.1
	 */
	@Unmodifiable
	List<ItemStack> getOutputs();

	/**
	 * The minimum XP that a player can receive.
	 *
	 * @since 19.22.1
	 */
	int getMinXpReward();

	/**
	 * The maximum XP that a player can receive.
	 *
	 * @since 19.22.1
	 */
	int getMaxXpReward();

	/**
	 * Unique ID for this recipe.
	 *
	 * @since 19.22.1
	 */
	@Nullable
	ResourceLocation getUid();

	/**
	 * Make the output render only, to avoid displaying unnecessary crafting recipes when looking up outputs.
	 *
	 * @since 19.22.1
	 */
	@Unmodifiable
	boolean isOutputRenderOnly();
}
