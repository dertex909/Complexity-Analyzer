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
import java.util.Set;

/**
 * Register additional mod info to help JEI understand your mod better.
 *
 * @since 17.1.0
 */
public interface IModInfoRegistration {
	/**
	 * Register alternative mod names, used for searching for a mod by a different name.
	 *
	 * For example "Just Enough Items" can register an alias "JEI" to help make searching for it easier.
	 *
	 * @param modId The modId to register aliases for
	 * @param aliases The aliases to register
	 * @since 17.1.0
	 */
	void addModAliases(String modId, Collection<String> aliases);

	/**
	 * Register alternative mod names, used for searching for a mod by a different name.
	 *
	 * For example "Just Enough Items" can register an alias "JEI" to help make searching for it easier.
	 *
	 * @param modId The modId to register aliases for
	 * @param aliases The aliases to register
	 * @since 17.1.0
	 */
	default void addModAliases(String modId, String... aliases) {
		addModAliases(modId, Set.of(aliases));
	}
}
