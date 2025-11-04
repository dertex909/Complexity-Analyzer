package org.complexityanalyzer.compat.jei;

import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.RecipeGraph;

public class JeiCompatibilityModule {

    public static void collectRecipesFromJeiPlugins(RecipeGraph graph, Level level) {
        if (!ComplexityConfig.ENABLE_JEI_INTEGRATION.get()) {
            ComplexityAnalyzer.LOGGER.info("JEI integration disabled in config");
            return;
        }

        try {
            JeiPluginScanner.scanAndImportRecipes(graph, level);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.error("JEI integration failed catastrophically", t);
        }
    }
}