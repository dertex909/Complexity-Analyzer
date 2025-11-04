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

import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/**
 * Represents one Config file used by JEI.
 *
 * Config files contain one or more {@link IJeiConfigCategory},
 * and each category has one or more {@link IJeiConfigValue}.
 *
 * @since 12.1.0
 */
public interface IJeiConfigFile {
	/**
	 * Get the path of this config file.
	 * Used for differentiating between config files.
	 *
	 * Note that config values will read from this file automatically,
	 * and updating config values will save the file automatically,
	 * so you should not read or write this file yourself.
	 *
	 * @since 12.1.0
	 */
	Path getPath();

	/**
	 * Get all the categories in this file.
	 * Each category contains values that can be read or edited.
	 *
	 * @since 12.1.0
	 */
	@Unmodifiable
	List<? extends IJeiConfigCategory> getCategories();
}
