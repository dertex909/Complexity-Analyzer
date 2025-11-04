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

package mezz.jei.api.gui.drawable;

import net.minecraft.resources.ResourceLocation;

import mezz.jei.api.gui.ITickTimer;
import mezz.jei.api.helpers.IGuiHelper;

/**
 * Builder for creating drawables from a resource location.
 * Create an instance with {@link IGuiHelper#drawableBuilder(ResourceLocation, int, int, int, int)}
 */
public interface IDrawableBuilder {
	/**
	 * For textures that are not 256x256, specify the size.
	 */
	IDrawableBuilder setTextureSize(int width, int height);

	/**
	 * Add extra blank space around the texture by adjusting the padding.
	 */
	IDrawableBuilder addPadding(int paddingTop, int paddingBottom, int paddingLeft, int paddingRight);

	/**
	 * Remove blank space around the texture by trimming it.
	 */
	IDrawableBuilder trim(int trimTop, int trimBottom, int trimLeft, int trimRight);

	/**
	 * Creates a normal, non-animated drawable.
	 */
	IDrawableStatic build();

	/**
	 * Creates an animated texture for a gui, revealing the texture over time.
	 *
	 * @param ticksPerCycle  the number of ticks for the animation to run before starting over
	 * @param startDirection the direction that the animation starts drawing the texture
	 * @param inverted       when inverted is true, the texture will start fully drawn and be hidden over time
	 */
	IDrawableAnimated buildAnimated(int ticksPerCycle, IDrawableAnimated.StartDirection startDirection, boolean inverted);

	/**
	 * Creates an animated texture for a gui, revealing the texture over time.
	 *
	 * @param tickTimer      a custom tick timer, used for advanced control over the animation
	 * @param startDirection the direction that the animation starts drawing the texture
	 */
	IDrawableAnimated buildAnimated(ITickTimer tickTimer, IDrawableAnimated.StartDirection startDirection);
}
