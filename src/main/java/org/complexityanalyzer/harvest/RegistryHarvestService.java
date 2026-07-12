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

package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Locale;

public final class RegistryHarvestService {
    private final FastHarvester harvester;

    public RegistryHarvestService() {
        this.harvester = new FastHarvester();
    }

    private static @NotNull String getRemaining(long elapsed, int scanned, int totalRecipes) {
        double avgTimePerRecipe = (double) elapsed / scanned;
        long estimatedTotal = (long) (avgTimePerRecipe * totalRecipes);
        long estimatedRemaining = estimatedTotal - elapsed;

        String remainingStr;
        if (estimatedRemaining > 60000) {
            remainingStr = String.format(Locale.US, "%dm %ds", estimatedRemaining / 60000, (estimatedRemaining % 60000) / 1000);
        } else {
            remainingStr = String.format(Locale.US, "%ds", estimatedRemaining / 1000);
        }
        return remainingStr;
    }

    private static String buildRejectReason(HarvestedItems items) {
        var sb = new StringBuilder("No structural recipe node: ");
        sb.append("inputItems=").append(items.inputItems().size());
        sb.append(" outputItems=").append(items.outputItems().size());
        sb.append(" inputIngredients=").append(items.inputIngredients().size());
        sb.append(" inputFluids=").append(items.inputFluids().size());
        sb.append(" outputFluids=").append(items.outputFluids().size());
        sb.append(" rootType=").append(items.root() != null ? items.root().getClass().getSimpleName() : "null");
        if (items.root() != null) {
            var detection = AntivirusStyleDetector.detect(items.root().getClass());
            sb.append(" antivirus=").append(detection.verdict());
        }
        return sb.toString();
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
        var nodes = new ObjectArrayList<RecipeNode>();
        var knownRecipeIds = new ObjectOpenHashSet<ResourceLocation>();

        var debugTrace = new FullDebugTracePipeline(worldDir);

        ComplexityAnalyzer.LOGGER.info("[Harvest] Starting runtime recipe scan (Total: {} recipes)...", totalRecipes);
        long startTime = System.currentTimeMillis();

        for (var holder : recipes) {
            scanned++;
            knownRecipeIds.add(holder.id());
            try {
                var recipe = holder.value();
                var items = harvester.harvest(recipe, level);
                var node = HarvestedRecipeConverter.convert(items, level);

                if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty() || !node.getChemicalIngredients().isEmpty())) {
                    nodes.add(node);
                    harvested++;
                    debugTrace.traceHarvested(holder.id().toString(), recipe, level, items);
                } else {
                    rejected++;
                    String reason = buildRejectReason(items);
                    debugTrace.traceRejected(holder.id().toString(), recipe, level, reason);
                }
            } catch (Throwable t) {
                failed++;
                debugTrace.traceFailed(holder.id().toString(), holder.value().getClass().getName(), t);
                ComplexityAnalyzer.LOGGER.debug("[Harvest] Failed to scan recipe {}: {}", holder.id(), t.getMessage());
            }

            if ((scanned < 1000 && scanned % 100 == 0) || (scanned >= 1000 && scanned % 1000 == 0) || scanned == totalRecipes) {
                long elapsed = System.currentTimeMillis() - startTime;
                final var remainingStr = getRemaining(elapsed, scanned, totalRecipes);

                ComplexityAnalyzer.LOGGER.info("[Harvest] Progress: {}/{} ({}%). Estimated remaining time: {}. Status: harvested={}, rejected={}, failed={}",
                        scanned, totalRecipes, String.format(Locale.US, "%.1f", (scanned * 100.0) / totalRecipes),
                        remainingStr, harvested, rejected, failed);
            }
        }

        for (var node : nodes) graph.addRecipe(node);
        debugTrace.flush();
        DynamicRecipeHarvester.harvest(graph, level, knownRecipeIds);

        ComplexityAnalyzer.LOGGER.info("[Harvest] Runtime scan complete: {} scanned, {} harvested, {} rejected, {} failed",
                scanned, harvested, rejected, failed);
    }
}