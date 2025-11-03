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

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.SourceManager;
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

        RecipeSelector staticSelector = new RecipeSelector(graph);
        RecipeNode representativeRecipe = staticSelector.selectStaticBestRecipe(item);

        boolean hasRecipe = graph.hasRecipe(item);
        int depth = hasRecipe ? depthAnalyzer.getDepth(item) : 0;
        int ingredients = hasRecipe ? representativeRecipe.getTotalIngredientCount() : 0;

        return new ItemComplexity.Builder(item)
                .complexity(complexity)
                .depth(depth)
                .totalIngredients(ingredients)
                .hasRecipe(hasRecipe)
                .build();
    }
}
