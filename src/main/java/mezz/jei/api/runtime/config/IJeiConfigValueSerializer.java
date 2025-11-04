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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Serialization and validation helper for JEI config values.
 * Get an instance from {@link IJeiConfigValue#getSerializer()}
 *
 * Note that if this value is a {@link List}
 * it should implement {@link IJeiConfigListValueSerializer} as well.
 *
 * @since 12.1.1
 */
public interface IJeiConfigValueSerializer<T> {
	/**
	 * Serialize the config value to a string.
	 *
	 * @since 12.1.1
	 */
	String serialize(T value);

	/**
	 * Deserialize the config value from a string.
	 *
	 * @since 12.1.1
	 */
	IDeserializeResult<T> deserialize(String string);

	/**
	 * Check if a given value is valid for this config value.
	 *
	 * @since 12.1.1
	 */
	boolean isValid(T value);

	/**
	 * If this config value only has a limited number of valid values,
	 * this returns them all.
	 *
	 * If there are many or unlimited valid values, this will return
	 * {@link Optional#empty()}
	 *
	 * @since 12.1.1
	 */
	Optional<Collection<T>> getAllValidValues();

	/**
	 * Get the description of what values are valid for this config value.
	 *
	 * @since 12.1.1
	 */
	String getValidValuesDescription();

	/**
	 * The result of {@link #deserialize}.
	 * If deserialization is successful, {@link #getResult()} will return a value.
	 * Otherwise, a list of errors can be fetched from {@link #getErrors()}.
	 *
	 * @since 12.1.1
	 */
	interface IDeserializeResult<T> {
		/**
		 * The successful deserialization result,
		 * or {@link Optional#empty()} if deserialzation failed.
		 *
		 * @since 12.1.1
		 */
		Optional<T> getResult();

		/**
		 * A list of errors during deserialization, if it failed.
		 * On successful deserialization this will be an empty list.
		 *
		 * @since 12.1.1
		 */
		List<String> getErrors();
	}
}
