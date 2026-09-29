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

package org.complexityanalyzer.network.web;

import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;
import org.complexityanalyzer.network.web.pack.CabinResourceResolver;
import org.complexityanalyzer.network.web.ws.CabinWsHub;

import java.util.concurrent.CompletableFuture;

public final class WebManager {

    private WebManager() {
    }

    public static CompletableFuture<CabinBackgroundService.Snapshot> reload(MinecraftServer server, AnalysisEngine engine) {
        CabinResourceResolver.invalidateCache();
        StandaloneWebServer.start();
        return CabinBackgroundService.getInstance().regenerateAsync(server, engine).whenComplete((snap, err) -> {
            if (snap != null) CabinWsHub.broadcastReload();
        });
    }
}