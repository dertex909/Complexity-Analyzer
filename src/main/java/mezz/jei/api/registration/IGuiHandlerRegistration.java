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

package mezz.jei.api.registration;

import java.util.Collection;
import java.util.List;

import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiClickableArea;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.handlers.IScreenHandler;

public interface IGuiHandlerRegistration {
	/**
	 * {@link IJeiHelpers} provides helpers and tools for addon mods.
	 * @since 11.5.0
	 */
	IJeiHelpers getJeiHelpers();

	/**
	 * Add a handler to give JEI extra information about how to layout the item list next to a specific type of {@link AbstractContainerScreen}.
	 * Multiple handlers can be registered for one {@link AbstractContainerScreen}.
	 *
	 * @see #addGenericGuiContainerHandler(Class, IGuiContainerHandler) for handlers that use Java Generics
	 */
	<T extends AbstractContainerScreen<?>> void addGuiContainerHandler(Class<? extends T> guiClass, IGuiContainerHandler<T> guiHandler);

	/**
	 * Same as {@link #addGuiContainerHandler(Class, IGuiContainerHandler)} but for handlers that use Java Generics to
	 * support multiple types of containers. This type of handler runs into type issues with the regular method.
	 * @since 7.1.1
	 */
	<T extends AbstractContainerScreen<?>> void addGenericGuiContainerHandler(Class<? extends T> guiClass, IGuiContainerHandler<?> guiHandler);

	/**
	 * Add a handler to let JEI draw next to a specific class (or subclass) of {@link Screen}.
	 * By default, JEI can only draw next to {@link AbstractContainerScreen}.
	 */
	<T extends Screen> void addGuiScreenHandler(Class<T> guiClass, IScreenHandler<T> handler);

	/**
	 * Add a handler to give JEI extra information about how to layout the ingredient list.
	 * Used for guis that display next to GUIs and would normally intersect with JEI.
	 */
	void addGlobalGuiHandler(IGlobalGuiHandler globalGuiHandler);

	/**
	 * Add a clickable area on a gui to jump to specific categories of recipes in JEI.
	 *
	 * @param containerScreenClass  the gui class for JEI to detect.
	 * @param xPos                  left x position of the clickable area, relative to the left edge of the gui.
	 * @param yPos                  top y position of the clickable area, relative to the top edge of the gui.
	 * @param width                 the width of the clickable area.
	 * @param height                the height of the clickable area.
	 * @param recipeTypes           the recipe types that JEI should display.
	 *
	 * @since 9.5.0
	 */
	default <T extends AbstractContainerScreen<?>> void addRecipeClickArea(Class<? extends T> containerScreenClass, int xPos, int yPos, int width, int height, RecipeType<?>... recipeTypes) {
		this.addGuiContainerHandler(containerScreenClass, new IGuiContainerHandler<T>() {
			@Override
			public Collection<IGuiClickableArea> getGuiClickableAreas(T containerScreen, double mouseX, double mouseY) {
				IGuiClickableArea clickableArea = IGuiClickableArea.createBasic(xPos, yPos, width, height, recipeTypes);
				return List.of(clickableArea);
			}
		});
	}

	/**
	 * Lets mods accept ghost ingredients from JEI.
	 * These ingredients are dragged from the ingredient list on to your gui, and are useful
	 * for setting recipes or anything else that does not need the real ingredient to exist.
	 */
	<T extends Screen> void addGhostIngredientHandler(Class<T> guiClass, IGhostIngredientHandler<T> handler);

}
