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

package org.complexityanalyzer.compat.jei;

import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.RecipeGraph;

public class JeiCompatibilityModule {
    public static void collectRecipesFromJeiPlugins(RecipeGraph graph, Level level) {
        if (!ModList.get().isLoaded("jei")) {
            ComplexityAnalyzer.LOGGER.info("JEI not found. Skipping JEI recipe import.");
            return;
        }

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