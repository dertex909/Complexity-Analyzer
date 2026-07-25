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

package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeGraph;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

import static java.util.Locale.US;

public final class RegistryHarvestService {
    private final FastHarvester harvester;

    public RegistryHarvestService() {
        this.harvester = new FastHarvester();
    }

    private static @NotNull String getRemaining(long elapsed, int scanned, int totalRecipes) {
        double avgTimePerRecipe = (double) elapsed / scanned;
        long estimatedTotal = (long) (avgTimePerRecipe * totalRecipes);
        long estimatedRemaining = estimatedTotal - elapsed;
        if (estimatedRemaining <= 0) return "0s";
        String remainingStr;
        if (estimatedRemaining > 60000) {
            remainingStr = String.format(US, "%dm %ds", estimatedRemaining / 60000, (estimatedRemaining % 60000) / 1000);
        } else {
            remainingStr = String.format(US, "%ds", estimatedRemaining / 1000);
        }
        return remainingStr;
    }

    public void harvestInto(RecipeGraph graph, Level level, Path worldDir) {
        if (graph == null || level == null) return;

        harvester.clearCaches();

        var recipes = level.getRecipeManager().getRecipes();
        int totalRecipes = recipes.size();
        int scanned = 0;
        int harvested = 0;
        int rejected = 0;
        int failed = 0;

        var knownRecipeIds = new ObjectOpenHashSet<ResourceLocation>(totalRecipes);
        var debugTrace = new FullDebugTracePipeline(worldDir);

        ComplexityAnalyzer.LOGGER.info("[Harvest] Starting runtime recipe scan (Total: {} recipes)...", totalRecipes);
        long startTime = System.currentTimeMillis();

        for (var holder : recipes) {
            scanned++;
            var recipeId = holder.id();
            knownRecipeIds.add(recipeId);
            try {
                var recipe = holder.value();
                var items = harvester.harvest(recipe, level);
                var node = HarvestedRecipeConverter.convert(items, level);

                if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty() || !node.getChemicalIngredients().isEmpty())) {
                    graph.addRecipe(node);
                    harvested++;
                    debugTrace.traceHarvested(recipeId, recipe, level, items);
                } else {
                    rejected++;
                    debugTrace.traceRejected(recipeId, recipe, level, items);
                }
            } catch (Throwable t) {
                failed++;
                debugTrace.traceFailed(recipeId, holder.value().getClass().getName(), t);
                ComplexityAnalyzer.LOGGER.debug("[Harvest] Failed to scan recipe {}: {}", recipeId, t.getMessage());
            }

            if (scanned % 1000 == 0 || scanned == totalRecipes) {
                long elapsed = System.currentTimeMillis() - startTime;
                final var remainingStr = getRemaining(elapsed, scanned, totalRecipes);

                ComplexityAnalyzer.LOGGER.debug("[Harvest] Progress: {}/{} ({}%). Estimated remaining time: {}. Status: harvested={}, rejected={}, failed={}",
                        scanned, totalRecipes, String.format(US, "%.1f", (scanned * 100.0) / totalRecipes),
                        remainingStr, harvested, rejected, failed);
            }
        }

        debugTrace.flush();
        DynamicRecipeHarvester.harvest(graph, level, knownRecipeIds);
        harvester.clearCaches();

        ComplexityAnalyzer.LOGGER.info("[Harvest] Runtime scan complete: {} scanned, {} harvested, {} rejected, {} failed",
                scanned, harvested, rejected, failed);
    }
}