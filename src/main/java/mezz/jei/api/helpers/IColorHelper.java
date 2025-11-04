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

import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemStack;

/**
 * Helper class for getting colors for sprites for purposes of implementing {@link mezz.jei.api.ingredients.IIngredientHelper#getColors(Object)}.
 * Get an instance from {@link mezz.jei.api.registration.IModIngredientRegistration#getColorHelper()}
 *
 * @since 7.6.3
 */
public interface IColorHelper {

	/**
	 * Gets the "main" colors of a given sprite when overlayed with a specific tint color.
	 * @param textureAtlasSprite Sprite to extract main colors from.
	 * @param renderColor        Overlay/tint color that is applied to the sprite.
	 * @param colorCount         Number of "main" colors to get.
	 * @return A list of the main ARGB colors for the given sprite when overlayed with a specific tint color.
	 */
	List<Integer> getColors(TextureAtlasSprite textureAtlasSprite, int renderColor, int colorCount);

	/**
	 * Gets the "main" colors of a given ItemStack.
	 * @param itemStack ItemStack to extract main colors from.
	 * @param colorCount Number of "main" colors to get.
	 * @return A list of the main ARGB colors for the given ItemStack
	 *
	 * @since 11.5.0
	 */
	List<Integer> getColors(ItemStack itemStack, int colorCount);

	/**
	 * @param color a color in ARGB format (0xAARRGGBB) Alpha, Red, Green, Blue
	 * @return the color name that is closest to the given color, using JEI's color name config file
	 *
	 * @since 11.5.0
	 */
	String getClosestColorName(int color);
}
