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

package mezz.jei.api.recipe.category;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.network.chat.Component;

/**
 * Simple abstract implementation of {@link IRecipeCategory} to help simplify creating recipe categories.
 * @since 19.19.0
 */
public abstract class AbstractRecipeCategory<T> implements IRecipeCategory<T> {
	private final RecipeType<T> recipeType;
	private final Component title;
	private final IDrawable icon;
	private final int width;
	private final int height;

	/**
	 * @since 19.19.0
	 */
	public AbstractRecipeCategory(RecipeType<T> recipeType, Component title, IDrawable icon, int width, int height) {
		this.recipeType = recipeType;
		this.title = title;
		this.icon = icon;
		this.width = width;
		this.height = height;
	}

	@Override
	public final RecipeType<T> getRecipeType() {
		return recipeType;
	}

	@Override
	public final Component getTitle() {
		return title;
	}

	@Override
	public final IDrawable getIcon() {
		return icon;
	}

	@Override
	public final int getWidth() {
		return width;
	}

	@Override
	public final int getHeight() {
		return height;
	}
}
