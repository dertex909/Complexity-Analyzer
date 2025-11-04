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

import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.level.material.Fluid;

/**
 * Helper for mods that want to handle Fluid ingredients across multiple platforms (Forge and Fabric).
 * @param <T> the type of Fluid ingredient for the current platform.
 * @since 10.1.0
 */
public interface IPlatformFluidHelper<T> {
	/**
	 * Returns the type of Fluid ingredients on the current platform.
	 * @since 10.1.0
	 */
	IIngredientTypeWithSubtypes<Fluid, T> getFluidIngredientType();

	/**
	 * Creates a new fluid ingredient for the current platform.
	 * @since 18.0.0
	 */
	T create(Holder<Fluid> fluid, long amount, DataComponentPatch components);

	/**
	 * Creates a new fluid ingredient for the current platform.
	 * @since 18.0.0
	 */
	T create(Holder<Fluid> fluid, long amount);

	/**
	 * Returns amount of Fluid in one bucket on the current platform.
	 * @since 10.1.0
	 */
	long bucketVolume();
}
