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

    public IterativeSolver(RecipeGraph graph, SourceManager sourceManager) {
        this.graph = graph;
        this.sourceManager = sourceManager;
    }

    public SolverResult solve() {
        ComplexityAnalyzer.LOGGER.debug("Starting iterative solver...");
        long startTime = System.currentTimeMillis();
        Map<Item, Double> optimalComplexities = new HashMap<>();

        for (Item item : graph.getAllItems()) {
            if (!graph.hasRecipe(item)) {
                double baseFactor = getBaseResourceCost(item, optimalComplexities);
                optimalComplexities.put(item, baseFactor);
            }
        }

        boolean changed;
        int iterations = 0;
        do {
            changed = false;
            iterations++;
            for (Item item : graph.getAllItems()) {
                double oldOptimal = optimalComplexities.getOrDefault(item, Double.MAX_VALUE);

                double newOptimal = calculateComplexity(item, optimalComplexities);

                if (Math.abs(oldOptimal - newOptimal) > 1e-6) {
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
        double baseCost = getBaseResourceCost(item, currentComplexities);

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
                    .min().orElse(Double.MAX_VALUE);
        } else {
            craftCost = allRecipes.stream()
                    .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE && r.getCategory() != RecipeCategory.RECYCLING)
                    .mapToDouble(recipe -> calculateRecipeCost(recipe, currentComplexities))
                    .min().orElse(Double.MAX_VALUE);
        }

        return Math.min(craftCost, baseCost);
    }

    private double calculateRecipeCost(RecipeNode recipe, Map<Item, Double> currentComplexities) {
        double ingredientsCost = 0;
        for (IngredientSlot slot : recipe.getIngredients()) {
            double slotCost = getSlotCost(slot, currentComplexities);
            if (slotCost >= BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier()) {
                return Double.MAX_VALUE;
            }
            ingredientsCost += slotCost * slot.getCount();
        }
        return (ingredientsCost * recipe.getRecipeMultiplier()) / recipe.getResultCount();
    }

    private double getSlotCost(IngredientSlot slot, Map<Item, Double> currentComplexities) {
        if (slot.getVariants().isEmpty()) {
            return Double.MAX_VALUE;
        }

        return slot.getVariants().stream()
                .mapToDouble(variant -> currentComplexities.getOrDefault(variant, getBaseResourceCost(variant, currentComplexities)))
                .min()
                .orElse(Double.MAX_VALUE);
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
                dependencyCost += currentComplexities.getOrDefault(sourceItem, BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier()) * amount;
            }
            return data.getBaseFactor() + dependencyCost;
        }
    }
}