/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.compat.jei;

import mezz.jei.api.IModPlugin;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.lang.reflect.Constructor;

public class SafePluginLoader {
    public static IModPlugin tryLoadPlugin(String className, String modId) {
        try {
            Class<?> pluginClass = Class.forName(className);

            if (!IModPlugin.class.isAssignableFrom(pluginClass)) {
                return null;
            }

            Constructor<?> ctor = pluginClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (IModPlugin) ctor.newInstance();

        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            ComplexityAnalyzer.LOGGER.warn("[SafeLoader] Skipping client-only JEI plugin from mod '{}': {} (Reason: {})",
                    modId, className, e.getClass().getSimpleName());
            return null;

        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.error("[SafeLoader] Failed to load JEI plugin class '{}' from mod '{}'",
                    className, modId, t);
            return null;
        }
    }
}