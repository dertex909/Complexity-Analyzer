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

package mezz.jei.api.runtime.config;

import net.minecraft.network.chat.Component;

/**
 * Represents config value used by JEI.
 * Config values can be read or updated by mods that display in-game config files to players.
 *
 * These config values are automatically synced with the config file.
 * {@link #getValue()} will automatically update based on changes to the file,
 * and using {@link #set} will automatically update the file.
 *
 * @since 12.1.0
 */
public interface IJeiConfigValue<T> {
	/**
	 * Get the name of this config value.
	 *
	 * @since 12.1.0
	 */
	String getName();

	/**
	 * Get the description of this config value.
	 *
	 * @since 12.1.0
	 * @deprecated use {@link #getLocalizedDescription()}
	 */
	@Deprecated(since = "19.21.0", forRemoval = true)
	String getDescription();

	/**
	 * Get the translated name component of this config value.
	 *
	 * @since 19.21.0
	 */
	Component getLocalizedName();

	/**
	 * Get the translated description component of this config value.
	 *
	 * @since 19.21.0
	 */
	Component getLocalizedDescription();

	/**
	 * Get the current value.
	 * This will automatically update and load from the config file if there are changes.
	 *
	 * @since 12.1.0
	 */
	T getValue();

	/**
	 * Get the default value.
	 *
	 * @since 12.1.0
	 */
	T getDefaultValue();

	/**
	 * Set the config value to the given value.
	 * This will automatically mark the config file as dirty so that it will save the new values.
	 *
	 * @since 12.1.0
	 */
	boolean set(T value);

	/**
	 * Get the helper for serializing values to and from Strings, and validating values.
	 *
	 * @since 12.1.1
	 */
	IJeiConfigValueSerializer<T> getSerializer();
}
