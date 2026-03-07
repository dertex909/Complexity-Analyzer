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

package org.complexityanalyzer.analyzer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.cache.ComplexityCache;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.Optional;

public class ComplexityCalculator {
    private final RecipeGraph graph;
    private final DepthAnalyzer depthAnalyzer;
    private final SolverResult solverResult;
    private final SourceManager sourceManager;
    private final ComplexityCache cache;

    public SolverResult getSolverResult() {
        return this.solverResult;
    }

    public ComplexityCalculator(
            RecipeGraph graph,
            DepthAnalyzer depthAnalyzer,
            SolverResult solverResult,
            SourceManager sourceManager
    ) {
        this.graph = graph;
        this.depthAnalyzer = depthAnalyzer;
        this.solverResult = solverResult;
        this.sourceManager = sourceManager;
        this.cache = AnalysisEngine.getInstance().getComplexityCache();
    }

    public Optional<ItemComplexity> getOrCalculateComplexity(Item item) {
        Optional<ItemComplexity> cachedResult = cache.get(item);
        if (cachedResult.isPresent()) {
            return cachedResult;
        }

        try {
            ItemComplexity result = buildComplexityResult(item);
            cache.put(item, result);
            return Optional.of(result);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Failed to build complexity result for {}", item, e);
            return Optional.empty();
        }
    }

    private ItemComplexity buildComplexityResult(Item item) {
        double complexity = solverResult.getComplexity(item)
                .orElseGet(() -> sourceManager.getBaseFactor(item));

        RecipeNode optimalRecipe = solverResult.optimalRecipes().get(item);

        boolean hasRecipe = graph.hasRecipe(item);
        int depth = hasRecipe ? depthAnalyzer.getDepth(item) : 0;

        int ingredients = (optimalRecipe != null) ? optimalRecipe.getTotalIngredientCount() : 0;

        ItemComplexity.Builder builder = new ItemComplexity.Builder(item)
                .complexity(complexity)
                .depth(depth)
                .totalIngredients(ingredients)
                .hasRecipe(hasRecipe);

        if (optimalRecipe != null) {
            builder.optimalRecipe(optimalRecipe);

            if (ComplexityAnalyzer.LOGGER.isDebugEnabled()) {
                String itemName = BuiltInRegistries.ITEM.getKey(item).toString();
                String recipeType = optimalRecipe.getRecipeType().toString();

                StringBuilder debugMsg = new StringBuilder(
                        String.format("Item %s uses recipe type: %s (complexity: %.2f)",
                                itemName, recipeType, complexity)
                );

                if (optimalRecipe.hasFluidIngredients()) {
                    debugMsg.append(String.format(", fluids: %d (total: %d mB)",
                            optimalRecipe.getFluidIngredientSlotCount(),
                            optimalRecipe.getTotalFluidAmount()));
                }

                ComplexityAnalyzer.LOGGER.debug(debugMsg.toString());
            }
        }

        if (optimalRecipe != null) {
            builder.optimalRecipe(optimalRecipe);

            if (ComplexityAnalyzer.LOGGER.isDebugEnabled()) {
                String itemName = BuiltInRegistries.ITEM.getKey(item).toString();
                String recipeType = optimalRecipe.getRecipeType().toString();

                StringBuilder debugMsg = new StringBuilder(
                        String.format("Item %s uses recipe type: %s (complexity: %.2f)",
                                itemName, recipeType, complexity)
                );

                if (optimalRecipe.hasFluidIngredients()) {
                    debugMsg.append(String.format(", fluids: %d (total: %d mB)",
                            optimalRecipe.getFluidIngredientSlotCount(),
                            optimalRecipe.getTotalFluidAmount()));
                }

                ComplexityAnalyzer.LOGGER.debug(debugMsg.toString());
            }
        } else {
            Optional<BaseResourceData> baseData = sourceManager.analyze(item);
            baseData.ifPresent(builder::baseData);

            if (ComplexityAnalyzer.LOGGER.isDebugEnabled() && baseData.isPresent()) {
                String itemName = BuiltInRegistries.ITEM.getKey(item).toString();
                BaseResourceData data = baseData.get();
                ComplexityAnalyzer.LOGGER.debug("Item {} is base resource: {} (source: {})",
                        itemName, data.getSourceType(), data.getSourceSpecifier());
            }
        }

        return builder.build();
    }
}
