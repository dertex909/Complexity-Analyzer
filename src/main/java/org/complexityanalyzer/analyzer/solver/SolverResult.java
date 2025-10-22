package org.complexityanalyzer.analyzer.solver;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.Map;
import java.util.Optional;

public record SolverResult(
        Map<Item, Double> optimalComplexities,
        Map<Item, RecipeNode> optimalRecipes,
        int iterations,
        long executionTimeMs,
        boolean converged
) {
    public Optional<Double> getComplexity(Item item) {
        return Optional.ofNullable(optimalComplexities.get(item));
    }

    public Optional<RecipeNode> getOptimalRecipe(Item item) {
        return Optional.ofNullable(optimalRecipes.get(item));
    }
}