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

package org.complexityanalyzer.analyzer;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.cache.ComplexityCache;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.resource.SourceManager;
import org.jetbrains.annotations.Nullable;

public class ComplexityCalculator {
    private final RecipeGraph graph;
    private final DepthAnalyzer depthAnalyzer;
    private final SolverResult solverResult;
    private final SourceManager sourceManager;
    private final ComplexityCache cache;

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

    public SolverResult getSolverResult() {
        return this.solverResult;
    }

    @Nullable
    public ItemComplexity getOrCalculateComplexity(Item item) {
        var cachedResult = cache.get(item);
        if (cachedResult != null) return cachedResult;

        try {
            var result = buildComplexityResult(item);
            cache.put(item, result);
            return result;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Failed to build complexity result for {}", item, e);
            return null;
        }
    }

    private ItemComplexity buildComplexityResult(Item item) {
        double complexity;

        if (solverResult.optimalComplexities().containsKey(item)) {
            complexity = solverResult.optimalComplexities().getDouble(item);
        } else {
            complexity = sourceManager.getBaseFactor(item);
        }

        if (Double.isInfinite(complexity) || complexity < 0) complexity = -1.0;

        var optimalRecipe = solverResult.optimalRecipes().get(item);
        boolean hasRecipe = graph.hasRecipe(item);
        int depth = depthAnalyzer.getDepth(item);

        int ingredients = 0;
        var baseData = sourceManager.analyze(item);
        if (optimalRecipe != null) {
            ingredients = optimalRecipe.getTotalIngredientCount();
        } else if (baseData != null) {
            for (var entry : baseData.getSourceItems().reference2DoubleEntrySet()) {
                if (entry.getKey() != item) ingredients += (int) entry.getDoubleValue();
            }
        }

        return new ItemComplexity.Builder(item)
                .complexity(complexity)
                .depth(depth)
                .totalIngredients(ingredients)
                .hasRecipe(hasRecipe)
                .optimalRecipe(optimalRecipe)
                .build();
    }
}