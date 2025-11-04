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

import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IModIngredientRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Allows plugins to change how JEI is displayed next to their mod's guis.
 * Register your implementation with {@link IGuiHandlerRegistration#addGuiContainerHandler(Class, IGuiContainerHandler)}.
 */
public interface IGuiContainerHandler<T extends AbstractContainerScreen<?>> {
	/**
	 * Give JEI information about extra space that the {@link AbstractContainerScreen} takes up.
	 * Used for moving JEI out of the way of extra things like gui tabs.
	 *
	 * @return the space that the gui takes up besides the normal rectangle defined by {@link AbstractContainerScreen}.
	 */
	default List<Rect2i> getGuiExtraAreas(T containerScreen) {
		return Collections.emptyList();
	}

	/**
	 * Return a clickable ingredient under the mouse that JEI could not normally detect, used for JEI recipe lookups.
	 *
	 * This is useful for guis that don't have normal slots (which is how JEI normally detects items under the mouse).
	 *
	 * This can also be used to let JEI look up liquids in tanks directly, by returning a FluidStack.
	 * Works with any ingredient type that has been registered with {@link IModIngredientRegistration}.
	 *
	 * @param mouseX the current X position of the mouse in screen coordinates.
	 * @param mouseY the current Y position of the mouse in screen coordinates.
	 *
	 * @since 19.23.0
	 */
	default Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(
		IClickableIngredientFactory builder,
		T containerScreen,
		double mouseX,
		double mouseY
	) {
		return getClickableIngredientUnderMouse(containerScreen, mouseX, mouseY);
	}

	/**
	 * Return a clickable ingredient under the mouse that JEI could not normally detect, used for JEI recipe lookups.
	 *
	 * This is useful for guis that don't have normal slots (which is how JEI normally detects items under the mouse).
	 *
	 * This can also be used to let JEI look up liquids in tanks directly, by returning a FluidStack.
	 * Works with any ingredient type that has been registered with {@link IModIngredientRegistration}.
	 *
	 * @param mouseX the current X position of the mouse in screen coordinates.
	 * @param mouseY the current Y position of the mouse in screen coordinates.
	 *
	 * @since 11.5.0
	 * @deprecated use {@link #getClickableIngredientUnderMouse(IClickableIngredientFactory, AbstractContainerScreen, double, double)}
	 */
	@SuppressWarnings("DeprecatedIsStillUsed")
	@Deprecated(forRemoval = true, since = "19.23.0")
	default Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(T containerScreen, double mouseX, double mouseY) {
		return Optional.empty();
	}

	/**
	 * Return the JEI-controlled clickable areas for this GUI.
	 * This is useful when you want to add a spot on your GUI that opens JEI and shows recipes.
	 *
	 * Optionally, you can restrict what you return here based on the current mouse position.
	 * @param guiMouseX the current X position of the mouse in gui-relative coordinates.
	 * @param guiMouseY the current Y position of the mouse in gui-relative coordinates.
	 * @since 6.0.1
	 */
	default Collection<IGuiClickableArea> getGuiClickableAreas(T containerScreen, double guiMouseX, double guiMouseY) {
		return Collections.emptyList();
	}
}
