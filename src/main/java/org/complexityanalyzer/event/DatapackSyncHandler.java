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

package org.complexityanalyzer.event;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public class DatapackSyncHandler {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        engine.createGeoManager(server);

        engine.initializeAsync(server.overworld(), () -> ComplexityAnalyzer.LOGGER.info("Analysis engine initialization complete."));
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AnalysisEngine.getInstance().shutdown();
    }

    public static AnalysisEngine getEngine() {
        return AnalysisEngine.getInstance();
    }
}