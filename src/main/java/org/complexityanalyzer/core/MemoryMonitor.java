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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.core;

import org.complexityanalyzer.ComplexityAnalyzer;

public class MemoryMonitor {

    private static final double PAUSE_THRESHOLD = 0.8;
    private static final double CRITICAL_THRESHOLD = 0.9;

    public static boolean isMemoryPressureHigh() {
        return getUsedMemoryRatio() > PAUSE_THRESHOLD;
    }

    public static boolean isMemoryCritical() {
        return getUsedMemoryRatio() > CRITICAL_THRESHOLD;
    }

    public static double getUsedMemoryRatio() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        if (maxMemory == Long.MAX_VALUE) return 0.0;
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (double) usedMemory / maxMemory;
    }

    public static String getMemoryStats() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory() / 1024 / 1024;
        long total = runtime.totalMemory() / 1024 / 1024;
        long free = runtime.freeMemory() / 1024 / 1024;
        long used = total - free;

        return String.format("Memory: %dMB/%dMB (%.1f%%)", used, max, (used * 100.0 / max));
    }

    public static void logMemoryStatus() {
        ComplexityAnalyzer.LOGGER.info("[MemoryMonitor] Current heap usage: {}", getMemoryStats());
    }
}
