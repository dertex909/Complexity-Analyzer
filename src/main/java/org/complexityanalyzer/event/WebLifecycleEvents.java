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

package org.complexityanalyzer.event;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.api.event.ComplexityAnalysisCompleteEvent;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;
import org.complexityanalyzer.network.web.CabinNettyHandler;
import org.complexityanalyzer.network.web.StandaloneWebServer;

import static org.complexityanalyzer.ComplexityAnalyzer.MODID;
import static org.complexityanalyzer.config.ComplexityConfig.SPEC;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public final class WebLifecycleEvents {

    private WebLifecycleEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        CabinNettyHandler.resetToken();
        StandaloneWebServer.start();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        StandaloneWebServer.stop();
        try {
            CabinBackgroundService.getInstance().clear();
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onAnalysisComplete(ComplexityAnalysisCompleteEvent event) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        String modVersion = ModList.get().getModContainerById(MODID).map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        CabinBackgroundService.getInstance().regenerateAsync(server, AnalysisEngine.getInstance(), modVersion);
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            ComplexityAnalyzer.LOGGER.info("[Web] Config reloaded, updating token & restarting web server...");
            CabinNettyHandler.resetToken();
            StandaloneWebServer.start();
        }
    }
}