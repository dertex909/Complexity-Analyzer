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

package org.complexityanalyzer.analyzer.solver;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.MachineRegistry;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class IterativeSolver {

    private final RecipeGraph graph;
    private final SourceManager sourceManager;
    private final MachineRegistry machineRegistry;

    private static final double CONVERGENCE_THRESHOLD = 1e-8;
    private static final double EPSILON = 1e-12;

    private final Map<RecipeNode, RecipeCostCache> recipeCostCache;
    private final Map<Item, BaseResourceCache> baseResourceCache;
    private final Set<Item> itemsInQueue = new HashSet<>();
    private static final int MAX_CACHE_SIZE = 2_500_000;

    private int recipeCostCalculations = 0;
    private int cacheHits = 0;

    public IterativeSolver(RecipeGraph graph, SourceManager sourceManager, MachineRegistry machineRegistry) {
        this.graph = graph;
        this.sourceManager = sourceManager;
        this.machineRegistry = machineRegistry;
        this.recipeCostCache = new HashMap<>();
        this.baseResourceCache = new HashMap<>();
    }

    public SolverResult solve() {
        ComplexityAnalyzer.LOGGER.debug("Starting enhanced iterative solver...");
        long startTime = System.currentTimeMillis();

        Map<Item, Double> optimalComplexities = initializeComplexities();
        Map<Item, RecipeNode> optimalRecipes = new HashMap<>();

        PriorityQueue<ItemUpdate> updateQueue = new PriorityQueue<>(
                Comparator.comparingDouble(ItemUpdate::getPriority)
        );

        Map<Item, Set<Item>> dependents = buildDependencyGraph();
        Set<Item> reusableVisitedSet = new HashSet<>();

        for (Item item : graph.getCorpus()) {
            updateQueue.offer(new ItemUpdate(item, 0, optimalComplexities.get(item)));
        }

        boolean converged;
        int iterations = 0;
        int itemsProcessed = 0;

        while (!updateQueue.isEmpty() && iterations < ComplexityConfig.MAX_ITERATIONS.get()) {
            iterations++;

            int batchSize = Math.min(updateQueue.size(), 1000);
            List<ItemUpdate> currentBatch = new ArrayList<>(batchSize);

            for (int i = 0; i < batchSize && !updateQueue.isEmpty(); i++) {
                ItemUpdate update = updateQueue.poll();
                itemsInQueue.remove(update.getItem());
                currentBatch.add(update);
            }

            boolean batchChanged = false;

            for (ItemUpdate update : currentBatch) {
                Item item = update.getItem();
                double oldComplexity = optimalComplexities.get(item);

                reusableVisitedSet.clear();
                ComplexityResult result = calculateComplexityEnhanced(
                        item,
                        optimalComplexities,
                        reusableVisitedSet
                );

                double newComplexity = result.complexity;

                if (hasSignificantChange(oldComplexity, newComplexity)) {
                    optimalComplexities.put(item, newComplexity);

                    if (result.recipe != null) {
                        optimalRecipes.put(item, result.recipe);
                    } else {
                        optimalRecipes.remove(item);
                    }

                    invalidateCache(item);

                    Set<Item> deps = dependents.getOrDefault(item, Collections.emptySet());
                    for (Item dependent : deps) {
                        if (itemsInQueue.add(dependent)) {
                            updateQueue.offer(new ItemUpdate(dependent, iterations, optimalComplexities.get(dependent)));
                        }
                    }

                    batchChanged = true;
                    itemsProcessed++;
                }
            }

            if (!batchChanged && updateQueue.isEmpty()) {
                break;
            }
        }

        converged = updateQueue.isEmpty();
        long totalTime = System.currentTimeMillis() - startTime;

        logResults(iterations, totalTime, converged, itemsProcessed);

        int reclassified = graph.reclassifyRecipesBasedOnComplexity(optimalComplexities);

        if (reclassified > 0) {
            ComplexityAnalyzer.LOGGER.info("Reclassified {} reverse recipes, recalculating...", reclassified);

            recipeCostCache.clear();
            baseResourceCache.clear();

            updateQueue.clear();
            itemsInQueue.clear();

            Set<Item> affectedItems = new HashSet<>();
            for (Item item : graph.getCorpus()) {
                if (graph.hasRecipe(item)) {
                    for (RecipeNode recipe : graph.getRecipes(item)) {
                        if (recipe.getCategory() == RecipeCategory.PROCESSING) {
                            affectedItems.add(item);
                            Set<Item> deps = dependents.getOrDefault(item, Collections.emptySet());
                            affectedItems.addAll(deps);
                            break;
                        }
                    }
                }
            }

            ComplexityAnalyzer.LOGGER.info("Recalculating {} affected items...", affectedItems.size());

            for (Item item : affectedItems) {
                updateQueue.offer(new ItemUpdate(item, iterations, optimalComplexities.get(item)));
                itemsInQueue.add(item);
            }

            int refinementIterations = 0;
            while (!updateQueue.isEmpty() && refinementIterations < ComplexityConfig.MAX_ITERATIONS.get()) {
                refinementIterations++;

                int batchSize = Math.min(updateQueue.size(), 1000);
                boolean batchChanged = false;

                for (int i = 0; i < batchSize && !updateQueue.isEmpty(); i++) {
                    ItemUpdate update = updateQueue.poll();
                    Item item = update.getItem();
                    itemsInQueue.remove(item);
                    double oldComplexity = optimalComplexities.get(item);

                    reusableVisitedSet.clear();
                    ComplexityResult result = calculateComplexityEnhanced(item, optimalComplexities, reusableVisitedSet);

                    if (hasSignificantChange(oldComplexity, result.complexity)) {
                        optimalComplexities.put(item, result.complexity);
                        if (result.recipe != null) {
                            optimalRecipes.put(item, result.recipe);
                        }

                        Set<Item> deps = dependents.getOrDefault(item, Collections.emptySet());
                        for (Item dep : deps) {
                            if (itemsInQueue.add(dep)) {
                                updateQueue.offer(new ItemUpdate(dep, refinementIterations, optimalComplexities.get(dep)));
                            }
                        }

                        batchChanged = true;
                    }
                }

                if (!batchChanged && updateQueue.isEmpty()) {
                    break;
                }
            }

            ComplexityAnalyzer.LOGGER.info("Refinement complete after {} iterations", refinementIterations);
        }

        clearCaches();

        return new SolverResult(
                optimalComplexities,
                optimalRecipes,
                iterations,
                totalTime,
                converged
        );
    }

    private Map<Item, Double> initializeComplexities() {
        Map<Item, Double> complexities = new HashMap<>();

        for (Item item : graph.getCorpus()) {
            Optional<BaseResourceData> dataOpt = sourceManager.analyze(item);

            if (dataOpt.isPresent() && dataOpt.get().getSourceItems().isEmpty()) {
                complexities.put(item, dataOpt.get().getBaseFactor());
                baseResourceCache.put(item, new BaseResourceCache(
                        dataOpt.get().getBaseFactor(),
                        true
                ));
            } else {
                complexities.put(item, Double.POSITIVE_INFINITY);
            }
        }

        return complexities;
    }

    private Map<Item, Set<Item>> buildDependencyGraph() {
        Map<Item, Set<Item>> dependents = new HashMap<>();

        for (Item item : graph.getCorpus()) {
            if (graph.hasRecipe(item)) {
                for (RecipeNode recipe : graph.getRecipes(item)) {
                    for (IngredientSlot slot : recipe.getIngredients()) {
                        for (Item ingredient : slot.getVariants()) {
                            dependents.computeIfAbsent(ingredient, k -> new HashSet<>())
                                    .add(item);
                        }
                    }
                }
            }

            Optional<BaseResourceData> dataOpt = sourceManager.analyze(item);
            if (dataOpt.isPresent()) {
                Map<Item, Double> sourceItems = dataOpt.get().getSourceItems();
                for (Item sourceItem : sourceItems.keySet()) {
                    dependents.computeIfAbsent(sourceItem, k -> new HashSet<>())
                            .add(item);
                }
            }
        }

        return dependents;
    }

    private ComplexityResult calculateComplexityEnhanced(
            Item item,
            Map<Item, Double> currentComplexities,
            Set<Item> visitedInPath
    ) {
        if (visitedInPath.size() > ComplexityConfig.CYCLE_DETECTION_DEPTH.get()) {
            ComplexityAnalyzer.LOGGER.warn("Cycle detection depth exceeded for item: {}", item);
            return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        }

        if (visitedInPath.contains(item)) {
            ComplexityAnalyzer.LOGGER.debug("Circular dependency detected for item: {}", item);
            return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        }

        visitedInPath.add(item);

        try {
            double sourceCost = getBaseResourceCostEnhanced(item, currentComplexities);

            ComplexityResult craftResult = getCraftingCostEnhanced(
                    item,
                    currentComplexities
            );

            double biasThreshold = ComplexityConfig.SOURCE_BIAS_THRESHOLD.get();
            if (sourceCost <= craftResult.complexity * biasThreshold) {
                return new ComplexityResult(sourceCost, null);
            } else {
                return craftResult;
            }

        } finally {
            visitedInPath.remove(item);
        }
    }

    private ComplexityResult getCraftingCostEnhanced(
            Item item,
            Map<Item, Double> currentComplexities
    ) {
        if (!graph.hasRecipe(item)) {
            return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        }

        List<RecipeNode> allRecipes = graph.getRecipes(item);

        List<RecipeNode> recipesToConsider = filterRecipes(allRecipes);

        if (recipesToConsider.isEmpty()) {
            return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        }

        double minCost = Double.POSITIVE_INFINITY;
        RecipeNode bestRecipe = null;

        for (RecipeNode recipe : recipesToConsider) {
            double cost = calculateRecipeCostEnhanced(
                    recipe,
                    currentComplexities
            );

            if (cost < minCost) {
                minCost = cost;
                bestRecipe = recipe;
            }
        }

        return new ComplexityResult(minCost, bestRecipe);
    }

    private List<RecipeNode> filterRecipes(List<RecipeNode> allRecipes) {
        List<RecipeNode> primaryRecipes = allRecipes.stream()
                .filter(r -> r.getCategory() == RecipeCategory.PRIMARY)
                .toList();

        if (!primaryRecipes.isEmpty()) {
            return primaryRecipes;
        }

        List<RecipeNode> filteredRecipes = allRecipes.stream()
                .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE
                        && r.getCategory() != RecipeCategory.RECYCLING
                        && r.getCategory() != RecipeCategory.PROCESSING
                        && r.getCategory() != RecipeCategory.STORAGE_DECOMPRESSION)
                .toList();

        if (!filteredRecipes.isEmpty()) {
            return filteredRecipes;
        }

        return allRecipes.stream()
                .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE)
                .toList();
    }

    private double calculateRecipeCostEnhanced(
            RecipeNode recipe,
            Map<Item, Double> currentComplexities
    ) {
        recipeCostCalculations++;

        RecipeCostCache cached = recipeCostCache.get(recipe);
        if (cached != null && cached.isValid(currentComplexities)) {
            cacheHits++;
            return cached.cost;
        }

        double ingredientsCost = 0;
        Map<Item, Double> usedComplexities = new HashMap<>();

        for (IngredientSlot slot : recipe.getIngredients()) {
            double slotCost = getSlotCostEnhanced(
                    slot,
                    currentComplexities,
                    usedComplexities
            );

            if (Double.isInfinite(slotCost)) {
                return Double.POSITIVE_INFINITY;
            }

            ingredientsCost += slotCost * slot.getCount();
        }

        double totalCost = (ingredientsCost * recipe.getRecipeMultiplier())
                / recipe.getResultCount();
        if (ComplexityConfig.MACHINE_TAX_ENABLED.get()) {
            double machineTax = calculateMachineTax(recipe, currentComplexities);
            totalCost += machineTax;
        }

        if (recipeCostCache.size() >= MAX_CACHE_SIZE) {
            ComplexityAnalyzer.LOGGER.error("!!! CRITICAL: Recipe cache overflow ({} entries) !!!", recipeCostCache.size());
            ComplexityAnalyzer.LOGGER.error("This should NEVER happen in normal operation. Possible infinite loop or algorithmic bug!");
            ComplexityAnalyzer.LOGGER.error("Stopping further caching to prevent OOM. Results may be slower but correct.");
            return totalCost;
        }

        recipeCostCache.put(recipe, new RecipeCostCache(totalCost, usedComplexities));
        return totalCost;
    }

    private double getSlotCostEnhanced(
            IngredientSlot slot,
            Map<Item, Double> currentComplexities,
            Map<Item, Double> usedComplexities
    ) {
        if (slot.getVariants().isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double minCost = Double.POSITIVE_INFINITY;
        Item bestVariant = null;

        for (Item variant : slot.getVariants()) {
            double cost = currentComplexities.getOrDefault(variant, Double.POSITIVE_INFINITY);
            if (cost < minCost) {
                minCost = cost;
                bestVariant = variant;
            }
        }

        if (bestVariant != null) {
            usedComplexities.put(bestVariant, minCost);
        }

        return minCost;
    }

    private double getBaseResourceCostEnhanced(
                    Item item,
            Map<Item, Double> currentComplexities
    ) {
        BaseResourceCache cached = baseResourceCache.get(item);
        if (cached != null && cached.isSimple) {
            return cached.cost;
        }

        List<BaseResourceData> allSources = sourceManager.findAllSources(item);

        if (allSources.isEmpty()) {
            return BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier();
        }

        double bestCost = Double.POSITIVE_INFINITY;
        boolean foundSimpleSource = false;

        for (BaseResourceData data : allSources) {
            double sourceCost;

            if (data.getSourceItems().isEmpty()) {
                sourceCost = data.getBaseFactor();
                foundSimpleSource = true;
            } else {
                if (graph.hasRecipe(item)) {
                    boolean isDecompressionSource = graph.getRecipes(item).stream()
                            .anyMatch(r -> {
                                if (r.getCategory() != RecipeCategory.STORAGE_DECOMPRESSION) {
                                    return false;
                                }
                                Set<Item> recipeIngredients = new HashSet<>();
                                r.getIngredients().forEach(slot -> recipeIngredients.addAll(slot.getVariants()));
                                return recipeIngredients.equals(data.getSourceItems().keySet());
                            });

                    if (isDecompressionSource) {
                        continue;
                    }
                }

                double dependencyCost = 0;
                boolean hasInfiniteDependency = false;

                for (Map.Entry<Item, Double> entry : data.getSourceItems().entrySet()) {
                    Item sourceItem = entry.getKey();
                    Double amount = entry.getValue();

                    if (amount == null || amount <= EPSILON) {
                        continue;
                    }

                    double itemCost = currentComplexities.getOrDefault(
                            sourceItem,
                            Double.POSITIVE_INFINITY
                    );

                    if (Double.isInfinite(itemCost)) {
                        hasInfiniteDependency = true;
                        break;
                    }

                    dependencyCost += itemCost * amount;
                }

                if (hasInfiniteDependency) {
                    continue;
                }

                sourceCost = data.getBaseFactor() + dependencyCost;
            }

            if (sourceCost < bestCost) {
                bestCost = sourceCost;
            }
        }

        if (foundSimpleSource && bestCost < Double.POSITIVE_INFINITY) {
            baseResourceCache.put(item, new BaseResourceCache(bestCost, true));
        }

        return bestCost;
    }

    private boolean hasSignificantChange(double oldValue, double newValue) {
        if (Double.isInfinite(oldValue) && Double.isInfinite(newValue)) {
            return false;
        }

        if (Double.isInfinite(oldValue) || Double.isInfinite(newValue)) {
            return true;
        }

        double delta = Math.abs(oldValue - newValue);
        double relative = oldValue > EPSILON ? delta / oldValue : delta;

        return delta > CONVERGENCE_THRESHOLD && relative > CONVERGENCE_THRESHOLD;
    }

    private void invalidateCache(Item item) {
        baseResourceCache.remove(item);

        recipeCostCache.entrySet().removeIf(entry ->
                entry.getValue().dependsOn(item)
        );
    }

    private void clearCaches() {
        recipeCostCache.clear();
        baseResourceCache.clear();
    }

    private void logResults(int iterations, long totalTime, boolean converged, int itemsProcessed) {
        if (!converged) {
            ComplexityAnalyzer.LOGGER.warn(
                    "Enhanced solver did NOT converge after {} iterations. " +
                            "Results may be approximate.",
                    ComplexityConfig.MAX_ITERATIONS.get()
            );
        }

        ComplexityAnalyzer.LOGGER.info(
                "Enhanced solver finished: {}ms, {} iterations, {} items processed",
                totalTime, iterations, itemsProcessed
        );

        if (recipeCostCalculations > 0) {
            double hitRate = 100.0 * cacheHits / recipeCostCalculations;
            ComplexityAnalyzer.LOGGER.debug(
                    "Cache stats: {} recipe calculations, {} cache hits ({} hit rate)",
                    recipeCostCalculations,
                    cacheHits,
                    String.format("%.2f%%", hitRate)
            );
        }
    }

    private static class ItemUpdate {
        private final Item item;
        private final int iteration;
        private final double currentComplexity;

        public ItemUpdate(Item item, int iteration, double currentComplexity) {
            this.item = item;
            this.iteration = iteration;
            this.currentComplexity = currentComplexity;
        }

        public Item getItem() {
            return item;
        }

        public double getPriority() {
            return Double.isInfinite(currentComplexity)
                    ? Double.MAX_VALUE
                    : currentComplexity * 0.001 + iteration;
        }
    }

    private static class ComplexityResult {
        final double complexity;
        final RecipeNode recipe;

        ComplexityResult(double complexity, RecipeNode recipe) {
            this.complexity = complexity;
            this.recipe = recipe;
        }
    }

    private double calculateMachineTax(
            RecipeNode recipe,
            Map<Item, Double> currentComplexities
    ) {
        RecipeType<?> recipeType = recipe.getRecipeType();
        Optional<Item> machineItemOpt = machineRegistry.getMachineForRecipe(recipeType);

        if (machineItemOpt.isEmpty()) {

            return 0.0;
        }

        Item machineItem = machineItemOpt.get();

        Double machineComplexity = currentComplexities.get(machineItem);

        if (machineComplexity == null) {
            return 0.0;
        }

        if (!Double.isFinite(machineComplexity)) {
            return 0.0;
        }

        if (machineComplexity <= 0) {
            return 0.0;
        }

        double taxRate = ComplexityConfig.getMachineTaxMultiplier();

        return machineComplexity * taxRate;
    }

    private static class RecipeCostCache {
        final double cost;
        final Map<Item, Double> dependencies;

        RecipeCostCache(double cost, Map<Item, Double> dependencies) {
            this.cost = cost;
            this.dependencies = new HashMap<>(dependencies);
        }

        boolean isValid(Map<Item, Double> currentComplexities) {
            for (Map.Entry<Item, Double> entry : dependencies.entrySet()) {
                Double currentCost = currentComplexities.get(entry.getKey());
                if (currentCost == null ||
                        Math.abs(currentCost - entry.getValue()) > ComplexityConfig.CONVERGENCE_THRESHOLD.get()) {
                    return false;
                }
            }
            return true;
        }

        boolean dependsOn(Item item) {
            return dependencies.containsKey(item);
        }
    }

    private static class BaseResourceCache {
        final double cost;
        final boolean isSimple;

        BaseResourceCache(double cost, boolean isSimple) {
            this.cost = cost;
            this.isSimple = isSimple;
        }
    }
}