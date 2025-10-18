package org.complexityanalyzer.analyzer.solver;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class IterativeSolver {

    private final RecipeGraph graph;
    private final SourceManager sourceManager;
    private static final double CONVERGENCE_THRESHOLD = 1e-9;

    public IterativeSolver(RecipeGraph graph, SourceManager sourceManager) {
        this.graph = graph;
        this.sourceManager = sourceManager;
    }

    public SolverResult solve() {
        ComplexityAnalyzer.LOGGER.debug("Starting iterative solver...");
        long startTime = System.currentTimeMillis();
        Map<Item, Double> optimalComplexities = new HashMap<>();

        for (Item item : graph.getAllItems()) {
            double baseFactor = getBaseResourceCost(item, optimalComplexities);
            optimalComplexities.put(item, baseFactor);
        }

        boolean changed;
        int iterations = 0;
        do {
            changed = false;
            iterations++;
            for (Item item : graph.getAllItems()) {
                double oldOptimal = optimalComplexities.get(item);

                double newOptimal = calculateComplexity(item, optimalComplexities);

                if (newOptimal < oldOptimal - CONVERGENCE_THRESHOLD) {
                    optimalComplexities.put(item, newOptimal);
                    changed = true;
                }
            }
        } while (changed && iterations < SolverConfig.MAX_ITERATIONS);

        long totalTime = System.currentTimeMillis() - startTime;
        boolean converged = iterations < SolverConfig.MAX_ITERATIONS;
        if (!converged) {
            ComplexityAnalyzer.LOGGER.warn("Iterative solver did NOT converge after {} iterations. Results may be approximate.", SolverConfig.MAX_ITERATIONS);
        }
        ComplexityAnalyzer.LOGGER.debug("Iterative solver finished in {}ms and {} iterations.", totalTime, iterations);

        return new SolverResult(optimalComplexities, new HashMap<>(), iterations, totalTime, converged);
    }

    private double calculateComplexity(Item item, Map<Item, Double> currentComplexities) {

        double baseCost = currentComplexities.get(item);

        if (!graph.hasRecipe(item)) {
            return baseCost;
        }

        List<RecipeNode> allRecipes = graph.getRecipes(item);
        List<RecipeNode> primaryRecipes = allRecipes.stream()
                .filter(r -> r.getCategory() == RecipeCategory.PRIMARY)
                .toList();

        double craftCost;
        if (!primaryRecipes.isEmpty()) {
            craftCost = primaryRecipes.stream()
                    .mapToDouble(recipe -> calculateRecipeCost(recipe, currentComplexities))
                    .min().orElse(Double.POSITIVE_INFINITY);
        } else {
            craftCost = allRecipes.stream()
                    .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE && r.getCategory() != RecipeCategory.RECYCLING)
                    .mapToDouble(recipe -> calculateRecipeCost(recipe, currentComplexities))
                    .min().orElse(Double.POSITIVE_INFINITY);
        }

        return Math.min(craftCost, baseCost);
    }

    private double calculateRecipeCost(RecipeNode recipe, Map<Item, Double> currentComplexities) {
        double ingredientsCost = 0;
        for (IngredientSlot slot : recipe.getIngredients()) {
            double slotCost = getSlotCost(slot, currentComplexities);
            if (Double.isInfinite(slotCost)) {
                return Double.POSITIVE_INFINITY;
            }
            ingredientsCost += slotCost * slot.getCount();
        }
        return (ingredientsCost * recipe.getRecipeMultiplier()) / recipe.getResultCount();
    }

    private double getSlotCost(IngredientSlot slot, Map<Item, Double> currentComplexities) {
        if (slot.getVariants().isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        return slot.getVariants().stream()
                .mapToDouble(variant -> currentComplexities.getOrDefault(variant, Double.POSITIVE_INFINITY))
                .min()
                .orElse(Double.POSITIVE_INFINITY);
    }

    private double getBaseResourceCost(Item item, Map<Item, Double> currentComplexities) {
        Optional<BaseResourceData> dataOpt = sourceManager.analyze(item);

        if (dataOpt.isEmpty()) {
            return BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier();
        }

        BaseResourceData data = dataOpt.get();

        if (data.getSourceItems().isEmpty()) {
            return data.getBaseFactor();
        } else {
            double dependencyCost = 0;
            for (Map.Entry<Item, Double> entry : data.getSourceItems().entrySet()) {
                Item sourceItem = entry.getKey();
                Double amount = entry.getValue();
                dependencyCost += currentComplexities.getOrDefault(sourceItem, Double.POSITIVE_INFINITY) * amount;
            }
            if (Double.isInfinite(dependencyCost)) {
                return Double.POSITIVE_INFINITY;
            }
            return data.getBaseFactor() + dependencyCost;
        }
    }
}