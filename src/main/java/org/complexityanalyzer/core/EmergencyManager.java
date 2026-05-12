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
import org.complexityanalyzer.geoscan.GeoAnalysisManager;

import java.util.concurrent.atomic.AtomicBoolean;

public class EmergencyManager {

    private static final AtomicBoolean PANIC_MODE = new AtomicBoolean(false);

    public static void panic(String reason) {
        if (!PANIC_MODE.compareAndSet(false, true)) return;

        ComplexityAnalyzer.LOGGER.error("!!! CRITICAL SYSTEM PANIC: {} !!!", reason);
        ComplexityAnalyzer.LOGGER.error("Attempting emergency shutdown to save server stability...");

        try {
            AnalysisEngine engine = AnalysisEngine.getInstance();
            GeoAnalysisManager geoManager = engine.getGeoManager();
            if (geoManager != null) geoManager.shutdown();
            engine.shutdown();
            ThreadPoolManager.getInstance().shutdown();

            ComplexityAnalyzer.LOGGER.error("Emergency shutdown complete. Mod is now in FAILED state.");
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Error during emergency shutdown!", e);
        }
    }
}