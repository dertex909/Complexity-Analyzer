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

package mezz.jei.api.gui.inputs;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * An input handler interface modeled after the vanilla {@link GuiEventListener}.
 * It has added support for passing in relative mouse positions when keys are pressed.
 *
 * For JEI-like input handling, use {@link IJeiInputHandler} instead.
 *
 * @since 19.6.0
 */
public interface IJeiGuiEventListener {
	/**
	 * Get the area covered by this handler relative to its parent element.
	 *
	 * Mouse coordinates passed to this handler are translated so that when
	 * the mouse is at this area's position, it is passed to this handler as if it were (0, 0).
	 *
	 * @since 19.6.0
	 */
	ScreenRectangle getArea();

	/**
	 * Called when the mouse is moved within the GUI element.
	 *
	 * @param mouseX the X coordinate of the mouse relative to the parent element.
	 * @param mouseY the Y coordinate of the mouse relative to the parent element.
	 *
	 * @since 19.6.0
	 */
	default void mouseMoved(double mouseX, double mouseY) {

	}

	/**
	 * Called when a mouse button is clicked within the GUI element.
	 *
	 * @return {@code true} if the event is consumed, {@code false} otherwise.
	 *
	 * @param mouseX the X coordinate of the mouse relative to the parent element.
	 * @param mouseY the Y coordinate of the mouse relative to the parent element.
	 * @param button the button that was clicked.
	 *
	 * @since 19.6.0
	 */
	default boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	/**
	 * Called when a mouse button is released within the GUI element.
	 *
	 * @return {@code true} if the event is consumed, {@code false} otherwise.
	 *
	 * @param mouseX the X coordinate of the mouse relative to the parent element.
	 * @param mouseY the Y coordinate of the mouse relative to the parent element.
	 * @param button the button that was released.
	 *
	 * @since 19.6.0
	 */
	default boolean mouseReleased(double mouseX, double mouseY, int button) {
		return false;
	}

	/**
	 * Called when the mouse is dragged within the GUI element.
	 *
	 * @return {@code true} if the event is consumed, {@code false} otherwise.
	 *
	 * @param mouseX the X coordinate of the mouse relative to the parent element.
	 * @param mouseY the Y coordinate of the mouse relative to the parent element.
	 * @param button the button that is being dragged.
	 * @param dragX  the X distance of the drag.
	 * @param dragY  the Y distance of the drag.
	 *
	 * @since 19.6.0
	 */
	default boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return false;
	}

	/**
	 * Called when the mouse is dragged within the GUI element.
	 *
	 * @return {@code true} if the event is consumed, {@code false} otherwise.
	 *
	 * @param mouseX the X coordinate of the mouse relative to the parent element.
	 * @param mouseY the Y coordinate of the mouse relative to the parent element.
	 * @param scrollX  the X distance of the scroll.
	 * @param scrollY  the Y distance of the scroll.
	 *
	 * @since 19.6.0
	 */
	default boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return false;
	}

	/**
	 * Called when a keyboard key is pressed within the GUI element.
	 *
	 * @return {@code true} if the event is consumed, {@code false} otherwise.
	 *
	 * @param mouseX the X coordinate of the mouse relative to the parent element.
	 * @param mouseY the Y coordinate of the mouse relative to the parent element.
	 * @param keyCode   the key code of the pressed key.
	 * @param scanCode  the scan code of the pressed key.
	 * @param modifiers the keyboard modifiers.
	 *
	 * @since 19.6.0
	 */
	default boolean keyPressed(double mouseX, double mouseY, int keyCode, int scanCode, int modifiers) {
		return false;
	}
}
