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

import java.util.List;
import java.util.function.Consumer;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;

/**
 * Lets mods accept ghost ingredients from JEI.
 * These ingredients are dragged from the ingredient list on to your gui, and are useful
 * for setting recipes or anything else that does not need the real ingredient to exist.
 *
 * Register your handler with {@link IGuiHandlerRegistration#addGhostIngredientHandler}
 */
public interface IGhostIngredientHandler<T extends Screen> {
	/**
	 * Called when a player wants to drag an ingredient on to your gui.
	 * Return the targets that can accept the ingredient.
	 *
	 * This is called when a player hovers over an ingredient with doStart=false,
	 * and called again when they pick up the ingredient with doStart=true.
	 *
	 * @since 12.2.0
	 */
	<I> List<Target<I>> getTargetsTyped(T gui, ITypedIngredient<I> ingredient, boolean doStart);

	/**
	 * Called when the player is done dragging an ingredient.
	 * If the drag succeeded, {@link Target#accept(Object)} was called before this.
	 * Otherwise, the player failed to drag an ingredient to a {@link Target}.
	 */
	void onComplete();

	/**
	 * @return true if JEI should highlight the targets for the player.
	 * false to handle highlighting yourself.
	 */
	default boolean shouldHighlightTargets() {
		return true;
	}

	interface Target<I> extends Consumer<I> {
		/**
		 * @return the area (in screen coordinates) where the ingredient can be dropped.
		 */
		Rect2i getArea();

		/**
		 * Called with the ingredient when it is dropped on the target.
		 */
		@Override
		void accept(I ingredient);
	}
}
