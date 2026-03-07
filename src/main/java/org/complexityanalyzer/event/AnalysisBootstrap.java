package org.complexityanalyzer.event;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.compat.jei.AdaptiveRecipeConverter;
import org.complexityanalyzer.core.AnalysisEngine;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public class AnalysisBootstrap {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        ComplexityAnalyzer.LOGGER.info("Server started, initializing Complexity Analyzer...");

        engine.createGeoManager(server);

        engine.initializeAsync(server.overworld(), () ->
                ComplexityAnalyzer.LOGGER.info("✅ Analysis engine initialization complete.")
        );
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ComplexityAnalyzer.LOGGER.info("Server stopping, shutting down Complexity Analyzer...");
        AnalysisEngine.getInstance().shutdown();

        ComplexityAnalyzer.LOGGER.debug("Cleaning up AdaptiveRecipeConverter resources...");
        AdaptiveRecipeConverter.shutdown();
        AdaptiveRecipeConverter.clearCaches();
    }

    public static AnalysisEngine getEngine() {
        return AnalysisEngine.getInstance();
    }
}