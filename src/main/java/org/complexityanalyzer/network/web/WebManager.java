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