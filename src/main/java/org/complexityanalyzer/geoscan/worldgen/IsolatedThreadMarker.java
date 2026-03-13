/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.geoscan.worldgen;

public final class IsolatedThreadMarker {

    private static final ThreadLocal<Boolean> ISOLATED = ThreadLocal.withInitial(() -> false);

    private IsolatedThreadMarker() {
    }

    public static void markIsolated() {
        ISOLATED.set(true);
    }

    public static void unmarkIsolated() {
        ISOLATED.set(false);
    }

    public static boolean isIsolatedThread() {
        return ISOLATED.get();
    }
}