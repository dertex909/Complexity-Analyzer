package org.complexityanalyzer.analyzer.solver;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.data.PathType;

import java.util.Map;
import java.util.Optional;

public record SolverResult(
        Map<Item, Double> optimalComplexities,
        Map<Item, Double> worstCaseComplexities,
        int iterations,
        long executionTimeMs,
        boolean converged
) {
    public Optional<Double> getComplexity(Item item, PathType pathType) {
        Map<Item, Double> map = (pathType == PathType.OPTIMAL) ? optimalComplexities : worstCaseComplexities;
        Double value = map.get(item);
        return Optional.ofNullable(value);
    }
}