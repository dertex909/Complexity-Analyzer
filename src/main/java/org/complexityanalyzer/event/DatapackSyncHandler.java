package org.complexityanalyzer.event;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public class DatapackSyncHandler {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        engine.createGeoManager(server);

        engine.initializeAsync(server.overworld(), () -> {
            ComplexityAnalyzer.LOGGER.info("Analysis engine is ready. Triggering initial geo-scan if needed.");
            engine.getGeoManager().ifPresent(GeoAnalysisManager::startInitialScanIfNeeded);
        });
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AnalysisEngine.getInstance().shutdown();
    }

    public static AnalysisEngine getEngine() {
        return AnalysisEngine.getInstance();
    }
}