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

package org.complexityanalyzer.util;

import org.complexityanalyzer.core.ThreadPoolManager;

public final class ProbeScope implements AutoCloseable {

    private static final ThreadLocal<Integer> PROBE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private boolean closed;

    private ProbeScope() {
        PROBE_DEPTH.set(PROBE_DEPTH.get() + 1);
    }

    public static ProbeScope open() {
        return new ProbeScope();
    }

    public static boolean isProbing() {
        return PROBE_DEPTH.get() > 0 || ThreadPoolManager.isComplexityThread();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        int depth = PROBE_DEPTH.get();
        if (depth <= 1) PROBE_DEPTH.remove();
        else PROBE_DEPTH.set(depth - 1);
    }
}
