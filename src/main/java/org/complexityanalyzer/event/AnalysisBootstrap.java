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

package org.complexityanalyzer.event;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.api.ComplexityAnalyzerAPI;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ComplexityAnalyzerAPIImpl;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;
import org.complexityanalyzer.util.ServerLanguage;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public class AnalysisBootstrap {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLanguage.init();
        var server = event.getServer();
        GameRegistryManager.initialize();
        var engine = AnalysisEngine.getInstance();
        ComplexityAnalyzerAPI.Holder.install(new ComplexityAnalyzerAPIImpl(engine));

        ComplexityAnalyzer.LOGGER.info("Server started, initializing Complexity Analyzer...");
        engine.initializeAsync(server.overworld(), () -> ComplexityAnalyzer.LOGGER.info("✅ Analysis engine initialization complete."));
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ComplexityAnalyzer.LOGGER.info("Server stopping, shutting down Complexity Analyzer...");
        try {
            CabinBackgroundService.getInstance().clear();
        } catch (Throwable ignored) {
        }
        AnalysisEngine.getInstance().shutdownCompletely();
        GameRegistryManager.clear();
        ComplexityAnalyzerAPI.Holder.uninstall();
    }

    public static AnalysisEngine getEngine() {
        return AnalysisEngine.getInstance();
    }
}