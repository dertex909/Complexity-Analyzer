/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.harvest.collector;

import org.complexityanalyzer.harvest.inspector.TerminalTypeRegistry;

import java.util.Collection;
import java.util.Map;

public final class HarvestUtility {

    private HarvestUtility() {
    }

    public static boolean isTerminal(Object obj) {
        if (obj == null) return true;
        var c = obj.getClass();
        String name = c.getName();
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("jdk.")) return true;
        return TerminalTypeRegistry.isTerminalType(c);
    }

    public static boolean isTooSmall(Object obj) {
        if (obj instanceof Collection<?> c) return c.size() <= 50;
        if (obj instanceof Map<?, ?> m) return m.size() <= 50;
        if (obj instanceof Object[] arr) return arr.length <= 50;
        return true;
    }

    public static boolean isEmptyContainer(Object obj) {
        if (obj instanceof Collection<?> c) return c.isEmpty();
        if (obj instanceof Map<?, ?> m) return m.isEmpty();
        if (obj instanceof Object[] arr) return arr.length == 0;
        return false;
    }
}