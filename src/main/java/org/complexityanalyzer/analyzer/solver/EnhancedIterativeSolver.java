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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.MachineRegistry;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.compat.jei.AdaptiveRecipeConverter;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EnhancedIterativeSolver {

    private static final double EPSILON = 1e-12;
    private static final double CONVERGENCE_THRESHOLD = ComplexityConfig.CONVERGENCE_THRESHOLD.get();
    private static final int MAX_ITERATIONS = ComplexityConfig.MAX_ITERATIONS.get();

    private final RecipeGraph graph;
    private final SourceManager sourceManager;
    private final MachineRegistry machineRegistry;
    private final DependencyGraph dependencies;
    private final ComplexityCache cache;
    private final Map<Item, Double> itemComplexities;
    private final Map<Fluid, Double> fluidComplexities;
    private final Map<Item, RecipeNode> optimalRecipes;
    private final ChemicalComplexityManager chemicalManager;
    private final ThreadLocal<Set<net.minecraft.world.level.material.Fluid>> fluidCalculationStack =
            ThreadLocal.withInitial(HashSet::new);

    private int totalIterations = 0;
    private int recipeCostCalculations = 0;
    private int cacheHits = 0;

    public EnhancedIterativeSolver(RecipeGraph graph, SourceManager sourceManager, MachineRegistry machineRegistry) {
        this.graph = graph;
        this.sourceManager = sourceManager;
        this.machineRegistry = machineRegistry;
        this.dependencies = new DependencyGraph();
        this.cache = new ComplexityCache();
        this.itemComplexities = new ConcurrentHashMap<>();
        this.fluidComplexities = new ConcurrentHashMap<>();
        this.optimalRecipes = new ConcurrentHashMap<>();
        this.chemicalManager = new ChemicalComplexityManager();
    }

    public SolverResult solve() {
        ComplexityAnalyzer.LOGGER.info("🚀 Starting Enhanced Iterative Solver...");
        long startTime = System.currentTimeMillis();

        Map<String, Integer> recipeTypes = new HashMap<>();
        for (RecipeNode recipe : graph.getAllRecipes()) {
            String type = recipe.getRecipeType().toString();
            recipeTypes.put(type, recipeTypes.getOrDefault(type, 0) + 1);
        }

        ComplexityAnalyzer.LOGGER.debug("🔍 Recipe types in graph ({} total):", graph.getAllRecipes().size());
        recipeTypes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .forEach(e -> ComplexityAnalyzer.LOGGER.debug("   {} → {} recipes", e.getKey(), e.getValue()));

        initialize();

        ComplexityAnalyzer.LOGGER.info("📦 Phase 1: Calculating item complexities...");
        int itemIterations = solveItems();
        logPhaseResults("ITEMS", itemIterations, itemComplexities.size());
        cache.clear();

        ComplexityAnalyzer.LOGGER.info("💧 Phase 2: Calculating fluid complexities...");
        int fluidIterations = solveFluids();
        logPhaseResults("FLUIDS", fluidIterations, fluidComplexities.size());
        cache.clear();

        ComplexityAnalyzer.LOGGER.info("🧪 Phase 3: Calculating chemical complexities...");
        int chemIterations = solveChemicals();
        logPhaseResults("CHEMICALS", chemIterations, chemicalManager.size());

        for (ResourceLocation chemId : chemicalManager.getAllChemicals()) {
            Fluid fluid = BuiltInRegistries.FLUID.get(chemId);

            if (fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                double chemComplexity = chemicalManager.getComplexity(chemId);
                double fluidComplexity = fluidComplexities.getOrDefault(fluid, Double.POSITIVE_INFINITY);

                if (!Double.isInfinite(chemComplexity)) {
                    if (chemComplexity < fluidComplexity || fluidComplexity < EPSILON) {
                        fluidComplexities.put(fluid, chemComplexity);
                    }
                }
            }
        }


        cache.clear();

        ComplexityAnalyzer.LOGGER.info("🔄 Refinement: Final convergence pass...");
        int refinementIterations = refinementPass();
        logPhaseResults("REFINEMENT", refinementIterations, itemComplexities.size() + fluidComplexities.size() + chemicalManager.size());

        int reclassified = graph.reclassifyRecipesBasedOnComplexity(itemComplexities);
        if (reclassified > 0) {
            ComplexityAnalyzer.LOGGER.info("🔄 Reclassified {} recipes, running final pass...", reclassified);
            cache.clear();
            totalIterations += refinementPass();
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logFinalStatistics(totalTime);

        return new SolverResult(new HashMap<>(itemComplexities), new HashMap<>(optimalRecipes), totalIterations, totalTime, true);
    }

    private void initialize() {
        ComplexityAnalyzer.LOGGER.info("🔧 Initializing solver...");
        dependencies.build(graph, sourceManager);

        for (Item item : graph.getCorpus()) {
            Optional<BaseResourceData> dataOpt = sourceManager.analyze(item);
            itemComplexities.put(item, dataOpt.isPresent() && dataOpt.get().getSourceItems().isEmpty()
                    ? dataOpt.get().getBaseFactor()
                    : Double.POSITIVE_INFINITY);
        }

        for (Fluid fluid : graph.getAllUsedFluids()) {
            String fluidName = BuiltInRegistries.FLUID.getKey(fluid).toString();

            if (fluidName.equals("minecraft:water") || fluidName.equals("minecraft:lava")) {
                fluidComplexities.put(fluid, 1.0);
            } else {
                fluidComplexities.put(fluid, Double.POSITIVE_INFINITY);
            }
        }

        int chemicalsRegistered = 0;
        int chemicalRecipesCreated = 0;

        for (RecipeNode recipe : graph.getAllRecipes()) {
            Map<Item, Double> itemInputs = new HashMap<>();
            Map<Fluid, Double> fluidInputs = new HashMap<>();

            List<AdaptiveRecipeConverter.ChemicalOutput> chemOutputs = recipe.getChemicalOutputs();

            if (chemOutputs.isEmpty()) {
                Object rawRecipe = recipe.getRawRecipeRef();
                if (rawRecipe != null) {
                    chemOutputs = AdaptiveRecipeConverter.extractChemicalOutputs(rawRecipe, null);
                }
            }

            if (chemOutputs.isEmpty()) continue;

            for (AdaptiveRecipeConverter.ChemicalOutput output : chemOutputs) {
                if (!chemicalManager.getAllChemicals().contains(output.id())) {
                    chemicalManager.registerChemical(output.id(), Double.POSITIVE_INFINITY);
                    chemicalsRegistered++;
                }


                Map<ResourceLocation, Double> chemInputs = new HashMap<>();

                for (IngredientSlot slot : recipe.getIngredients()) {
                    if (!slot.getVariants().isEmpty()) {
                        Item bestItem = slot.getVariants().getFirst();
                        itemInputs.put(bestItem, (double) slot.getCount());
                    }
                }

                for (FluidIngredientSlot slot : recipe.getFluidIngredients()) {
                    if (!slot.getFluidVariants().isEmpty()) {
                        Fluid bestFluid = slot.getFluidVariants().getFirst();
                        fluidInputs.put(bestFluid, slot.getAmount() / 1000.0);
                    }
                }

                Object rawRecipeForInputs = recipe.getRawRecipeRef();
                if (rawRecipeForInputs != null) {
                    List<AdaptiveRecipeConverter.ChemicalOutput> chemInputsList =
                            AdaptiveRecipeConverter.extractChemicalInputs(rawRecipeForInputs);
                    for (AdaptiveRecipeConverter.ChemicalOutput chemInput : chemInputsList) {
                        chemInputs.put(chemInput.id(), chemInput.amount() / 1000.0);
                    }
                }

                double machineComplexity = 0.0;
                if (machineRegistry != null) {
                    Optional<Item> machineOpt = machineRegistry.getMachineForRecipe(recipe.getRecipeType());
                    if (machineOpt.isPresent()) {
                        Item machineItem = machineOpt.get();
                        machineComplexity = itemComplexities.getOrDefault(machineItem, 0.0);
                        if (Double.isInfinite(machineComplexity)) {
                            machineComplexity = ComplexityConfig.getMachineBaseComplexity();
                        }
                        machineComplexity *= ComplexityConfig.getMachineTaxMultiplier();
                    }
                }

                ChemicalComplexityManager.ChemicalRecipe chemRecipe =
                        new ChemicalComplexityManager.ChemicalRecipe(
                                itemInputs,
                                fluidInputs,
                                chemInputs,
                                output.amount() / 1000.0,
                                machineComplexity,
                                recipe.getRecipeMultiplier()
                        );

                chemicalManager.addProducingRecipe(output.id(), chemRecipe);
                chemicalRecipesCreated++;
            }
        }

        ComplexityAnalyzer.LOGGER.info("✅ Initialization complete: {} items, {} fluids, {} chemicals ({} recipes)",
                itemComplexities.size(), fluidComplexities.size(), chemicalsRegistered, chemicalRecipesCreated);
    }

    private int solveChemicals() {
        Set<ResourceLocation> toUpdate = new HashSet<>();

        for (ResourceLocation chemId : chemicalManager.getAllChemicals()) {
            if (Double.isInfinite(chemicalManager.getComplexity(chemId))) {
                toUpdate.add(chemId);
            }
        }

        if (toUpdate.isEmpty()) return 0;

        int iterations = 0;
        boolean changed;

        while (iterations < MAX_ITERATIONS / 2) {
            iterations++;
            changed = false;

            for (ResourceLocation chemId : toUpdate) {
                if (updateChemicalComplexity(chemId)) {
                    changed = true;
                }
            }

            if (!changed) break;
        }

        return iterations;
    }

    private boolean updateChemicalComplexity(ResourceLocation chemId) {
        double oldComplexity = chemicalManager.getComplexity(chemId);
        double newComplexity = chemicalManager.calculateComplexity(chemId, itemComplexities, fluidComplexities);

        if (hasSignificantChange(oldComplexity, newComplexity)) {
            chemicalManager.setComplexity(chemId, newComplexity);
            return true;
        }
        return false;
    }

    private int solveItems() {
        PriorityQueue<Item> updateQueue = new PriorityQueue<>(Comparator.comparingDouble(itemComplexities::get));
        Set<Item> inQueue = new HashSet<>(graph.getCorpus());
        updateQueue.addAll(inQueue);
        int iterations = 0;
        while (!updateQueue.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            int batchSize = Math.min(500, updateQueue.size());
            List<Item> batch = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize && !updateQueue.isEmpty(); i++) {
                Item item = updateQueue.poll();
                inQueue.remove(item);
                batch.add(item);
            }
            boolean changed = false;
            for (Item item : batch) {
                if (updateItemComplexity(item)) {
                    changed = true;
                    for (Item dependent : dependencies.getItemDependents(item)) {
                        if (inQueue.add(dependent)) {
                            updateQueue.offer(dependent);
                        }
                    }
                }
            }
            if (!changed && updateQueue.isEmpty()) break;
        }
        return iterations;
    }

    private boolean updateItemComplexity(Item item) {
        double oldComplexity = itemComplexities.get(item);
        double sourceCost = calculateSourceCost(item);
        ComplexityResult craftResult = calculateCraftingCost(item);
        double newComplexity = Math.min(sourceCost, craftResult.complexity);
        if (hasSignificantChange(oldComplexity, newComplexity)) {
            itemComplexities.put(item, newComplexity);
            if (craftResult.recipe() != null && !Double.isInfinite(craftResult.complexity)
                    && craftResult.complexity + EPSILON < sourceCost) {
                optimalRecipes.put(item, craftResult.recipe());
            } else {
                optimalRecipes.remove(item);
            }
            cache.invalidateItem(item);
            return true;
        }
        return false;
    }

    private int solveFluids() {
        List<Fluid> toUpdate = fluidComplexities.entrySet().stream()
                .filter(e -> Double.isInfinite(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        if (toUpdate.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("⚠️  No fluids with INFINITY! All already have values.");
            return 0;
        }

        int iterations = 0;
        boolean changed;
        while (iterations < MAX_ITERATIONS / 2) {
            iterations++;
            changed = false;
            for (Fluid fluid : toUpdate) {
                if (updateFluidComplexity(fluid)) {
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return iterations;
    }

    private static final Set<String> PROTECTED_FLUIDS = Set.of(
            "minecraft:water",
            "minecraft:lava"
    );

    private boolean isProtectedFluid(Fluid fluid) {
        return PROTECTED_FLUIDS.contains(BuiltInRegistries.FLUID.getKey(fluid).toString());
    }

    private boolean updateFluidComplexity(Fluid fluid) {
        if (isProtectedFluid(fluid)) {
            return false;
        }

        double oldComplexity = fluidComplexities.get(fluid);
        double newComplexity = calculateFluidComplexity(fluid);

        if (hasSignificantChange(oldComplexity, newComplexity)) {
            fluidComplexities.put(fluid, newComplexity);
            cache.invalidateFluid(fluid);
            for (Item user : graph.getItemsUsingFluid(fluid)) updateItemComplexity(user);
            return true;
        }
        return false;
    }

    private double calculateFluidComplexity(Fluid fluid) {
        if (isProtectedFluid(fluid)) {
            return 1.00;
        }

        Set<Fluid> stack = fluidCalculationStack.get();
        if (stack.contains(fluid)) {
            ComplexityAnalyzer.LOGGER.debug("🔄 Cycle detected for fluid {}",
                    BuiltInRegistries.FLUID.getKey(fluid));
            return Double.POSITIVE_INFINITY;
        }

        stack.add(fluid);
        try {
            List<RecipeNode> producers = graph.getRecipesProducingFluid(fluid);

            if (producers.isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }


            double minCost = Double.POSITIVE_INFINITY;
            boolean foundFinite = false;

            for (RecipeNode recipe : producers) {
                double recipeCost = calculateRecipeCost(recipe, true);

                if (Double.isInfinite(recipeCost)) {
                    Object rawRecipe = recipe.getRawRecipeRef();
                    if (rawRecipe != null) {
                        List<AdaptiveRecipeConverter.ChemicalOutput> chemInputs =
                                AdaptiveRecipeConverter.extractChemicalInputs(rawRecipe);
                        for (AdaptiveRecipeConverter.ChemicalOutput chemInput : chemInputs) {
                            double chemComplexity = chemicalManager.getComplexity(chemInput.id());
                            if (!Double.isInfinite(chemComplexity)) {
                                recipeCost = chemComplexity;
                                break;
                            }
                        }
                    }

                    if (Double.isInfinite(recipeCost)) {
                        continue;
                    }
                }

                int outputAmount = recipe.getFluidOutputs().stream()
                        .filter(s -> s.getFluid().equals(fluid))
                        .mapToInt(net.neoforged.neoforge.fluids.FluidStack::getAmount)
                        .sum();

                if (outputAmount <= 0) outputAmount = 1000;

                double costPerUnit = (recipeCost * recipe.getRecipeMultiplier()) / (outputAmount / 1000.0);

                minCost = Math.min(minCost, costPerUnit);
                foundFinite = true;
            }

            if (!foundFinite || Double.isInfinite(minCost)) {
                minCost = Double.POSITIVE_INFINITY;
            }

            ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
            if (chemicalManager.getAllChemicals().contains(fluidId)) {
                double chemComplexity = chemicalManager.getComplexity(fluidId);
                if (!Double.isInfinite(chemComplexity) && chemComplexity < minCost) {
                    minCost = chemComplexity;
                }
            }

            return minCost;

        } finally {
            stack.remove(fluid);
        }
    }

    private int refinementPass() {
        int iterations = 0;
        boolean changed = true;
        while (changed && iterations < 100) {
            iterations++;
            changed = false;
            for (Item item : graph.getCorpus()) if (updateItemComplexity(item)) changed = true;
            for (Fluid fluid : graph.getAllUsedFluids()) if (updateFluidComplexity(fluid)) changed = true;
            for (ResourceLocation chemId : chemicalManager.getAllChemicals())
                if (updateChemicalComplexity(chemId)) changed = true;
        }
        return iterations;
    }

    private double calculateSourceCost(Item item) {
        List<BaseResourceData> allSources = sourceManager.findAllSources(item);
        if (allSources.isEmpty()) return Double.POSITIVE_INFINITY;
        double bestCost = Double.POSITIVE_INFINITY;
        for (BaseResourceData data : allSources) {
            if (data.getSourceItems().isEmpty()) {
                bestCost = Math.min(bestCost, data.getBaseFactor());
            } else {
                double cost = data.getBaseFactor();
                boolean hasInfiniteDep = false;
                for (Map.Entry<Item, Double> entry : data.getSourceItems().entrySet()) {
                    double depComplexity = itemComplexities.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
                    if (Double.isInfinite(depComplexity)) {
                        hasInfiniteDep = true;
                        break;
                    }
                    cost += depComplexity * entry.getValue();
                }
                if (!hasInfiniteDep) bestCost = Math.min(bestCost, cost);
            }
        }
        return bestCost;
    }

    private ComplexityResult calculateCraftingCost(Item item) {
        List<RecipeNode> recipes = graph.getRecipes(item);
        if (recipes.isEmpty()) return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        double minCost = Double.POSITIVE_INFINITY;
        RecipeNode bestRecipe = null;
        for (RecipeNode recipe : recipes) {
            double cost = calculateRecipeCost(recipe, false);
            if (cost < minCost) {
                minCost = cost;
                bestRecipe = recipe;
            }
        }
        return new ComplexityResult(minCost, bestRecipe);
    }

    private double calculateRecipeCost(RecipeNode recipe, boolean allowInfiniteMachines) {
        recipeCostCalculations++;
        if (!allowInfiniteMachines) {
            RecipeCostCache cached = cache.getRecipeCost(recipe);
            if (cached != null && cached.isValid(itemComplexities, fluidComplexities)) {
                cacheHits++;
                return cached.cost;
            }
        }
        double totalCost = 0.0;
        Map<Item, Double> usedItems = new HashMap<>();
        Map<Fluid, Double> usedFluids = new HashMap<>();

        for (IngredientSlot slot : recipe.getIngredients()) {
            double slotCost = getMinComplexity(slot.getVariants(), itemComplexities);
            if (Double.isInfinite(slotCost)) return Double.POSITIVE_INFINITY;
            totalCost += slotCost * slot.getCount();
            Item bestVariant = getBestVariant(slot.getVariants(), itemComplexities);
            if (bestVariant != null) usedItems.put(bestVariant, slotCost);
        }

        for (FluidIngredientSlot slot : recipe.getFluidIngredients()) {
            double slotCost = getMinComplexity(slot.getFluidVariants(), fluidComplexities);

            if (Double.isInfinite(slotCost)) {
                if (allowInfiniteMachines) {
                    Fluid bestFluid = getBestVariant(slot.getFluidVariants(), fluidComplexities);
                    if (bestFluid != null) {
                        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(bestFluid);
                        double chemComplexity = chemicalManager.getComplexity(fluidId);
                        if (!Double.isInfinite(chemComplexity)) {
                            slotCost = chemComplexity;
                        } else {
                            return Double.POSITIVE_INFINITY;
                        }
                    } else {
                        return Double.POSITIVE_INFINITY;
                    }
                } else {
                    return Double.POSITIVE_INFINITY;
                }
            }

            totalCost += slotCost * (slot.getAmount() / 1000.0) * ComplexityConfig.getFluidNormalizationFactor();
            Fluid bestFluid = getBestVariant(slot.getFluidVariants(), fluidComplexities);
            if (bestFluid != null) usedFluids.put(bestFluid, slotCost);
        }


        if (machineRegistry != null) {
            Optional<Item> machineOpt = machineRegistry.getMachineForRecipe(recipe.getRecipeType());
            if (machineOpt.isPresent()) {
                Item machineItem = machineOpt.get();
                double machineComplexity = itemComplexities.getOrDefault(machineItem, 0.0);

                if (isZeroCostMachine(recipe.getRecipeType())) {
                    machineComplexity = 0.0;
                } else if (Double.isInfinite(machineComplexity)) {
                    if (allowInfiniteMachines) machineComplexity = ComplexityConfig.getMachineBaseComplexity();
                    else return Double.POSITIVE_INFINITY;
                }

                if (machineComplexity > 0.0) {
                    totalCost += machineComplexity * ComplexityConfig.getMachineTaxMultiplier();
                }
            }
        }

        double multiplier = recipe.getRecipeMultiplier();

        int resultCount = recipe.getResultCount();

        if (resultCount <= 0 || Double.isInfinite(multiplier)) return Double.POSITIVE_INFINITY;

        double finalCost = (totalCost * multiplier) / resultCount;

        if (!allowInfiniteMachines) cache.putRecipeCost(recipe, new RecipeCostCache(finalCost, usedItems, usedFluids));

        return finalCost;
    }

    private boolean isZeroCostMachine(RecipeType<?> recipeType) {

        if (recipeType == null) {
            return false;
        }

        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);

        if (typeId == null) {
            return false;
        }

        ResourceLocation craftingId = BuiltInRegistries.RECIPE_TYPE.getKey(RecipeType.CRAFTING);
        return typeId.equals(craftingId);
    }

    private <T> double getMinComplexity(List<T> variants, Map<T, Double> complexities) {
        return variants.stream().mapToDouble(v -> complexities.getOrDefault(v, Double.POSITIVE_INFINITY)).min().orElse(Double.POSITIVE_INFINITY);
    }

    private <T> T getBestVariant(List<T> variants, Map<T, Double> complexities) {
        return variants.stream().min(Comparator.comparingDouble(v -> complexities.getOrDefault(v, Double.POSITIVE_INFINITY))).orElse(null);
    }

    private boolean hasSignificantChange(double oldValue, double newValue) {
        if (Double.isInfinite(oldValue) && Double.isInfinite(newValue)) return false;
        if (Double.isInfinite(oldValue) || Double.isInfinite(newValue)) return true;
        double delta = Math.abs(oldValue - newValue);
        return delta > CONVERGENCE_THRESHOLD && (oldValue <= EPSILON ? delta : delta / oldValue) > CONVERGENCE_THRESHOLD;
    }

    private void logPhaseResults(String phase, int iterations, int elementsProcessed) {
        ComplexityAnalyzer.LOGGER.info("  ✅ {} complete: {} iterations, {} elements", phase, iterations, elementsProcessed);
        totalIterations += iterations;
    }

    private void logFinalStatistics(long totalTime) {
        long finiteItems = itemComplexities.values().stream().filter(v -> !Double.isInfinite(v)).count();
        long infiniteFluids = fluidComplexities.values().stream().filter(v -> Double.isInfinite(v)).count();
        long finiteChemicals = chemicalManager.getAllChemicals().stream()
                .filter(id -> !Double.isInfinite(chemicalManager.getComplexity(id))).count();

        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        ComplexityAnalyzer.LOGGER.info("🎯 SOLVER RESULTS");
        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        ComplexityAnalyzer.LOGGER.info("⏱️  Time: {}ms", totalTime);
        ComplexityAnalyzer.LOGGER.info("🔄 Total iterations: {}", totalIterations);
        ComplexityAnalyzer.LOGGER.info("📦 Items: {}/{} finite", finiteItems, itemComplexities.size());
        ComplexityAnalyzer.LOGGER.info("💧 Fluids: {}/{} infinite", infiniteFluids, fluidComplexities.size());
        ComplexityAnalyzer.LOGGER.info("🧪 Chemicals: {}/{} finite", finiteChemicals, chemicalManager.size());

        if (recipeCostCalculations > 0) {
            ComplexityAnalyzer.LOGGER.info("💾 Cache: {} calculations, {} hits ({}%), {} invalidations",
                    recipeCostCalculations, cacheHits,
                    String.format("%.1f", 100.0 * cacheHits / recipeCostCalculations),
                    cache.getInvalidationCount());
        }
    }


    private static class DependencyGraph {
        private final Map<Item, Set<Item>> itemDependents = new HashMap<>();

        void build(RecipeGraph graph, SourceManager sourceManager) {
            for (RecipeNode recipe : graph.getAllRecipes()) {
                Item result = recipe.getResultItem();
                for (IngredientSlot slot : recipe.getIngredients()) {
                    for (Item ingredient : slot.getVariants()) {
                        itemDependents.computeIfAbsent(ingredient, k -> new HashSet<>()).add(result);
                    }
                }
            }
            for (Item item : graph.getCorpus()) {
                for (BaseResourceData source : sourceManager.findAllSources(item)) {
                    for (Item sourceItem : source.getSourceItems().keySet()) {
                        itemDependents.computeIfAbsent(sourceItem, k -> new HashSet<>()).add(item);
                    }
                }
            }
        }

        Set<Item> getItemDependents(Item item) {
            return itemDependents.getOrDefault(item, Collections.emptySet());
        }
    }

    private static class ComplexityCache {
        private final Map<RecipeNode, RecipeCostCache> recipeCosts = new ConcurrentHashMap<>();
        private int invalidationCount = 0;

        RecipeCostCache getRecipeCost(RecipeNode recipe) {
            return recipeCosts.get(recipe);
        }

        void putRecipeCost(RecipeNode recipe, RecipeCostCache cache) {
            recipeCosts.put(recipe, cache);
        }

        void invalidateItem(Item item) {
            int removed = recipeCosts.size();
            recipeCosts.entrySet().removeIf(e -> e.getValue().dependsOnItem(item) || e.getKey().getIngredients().stream().anyMatch(s -> s.getVariants().contains(item)));
            invalidationCount += removed - recipeCosts.size();
        }

        void invalidateFluid(Fluid fluid) {
            int removed = recipeCosts.size();
            recipeCosts.entrySet().removeIf(e -> e.getValue().dependsOnFluid(fluid) || e.getKey().getFluidIngredients().stream().anyMatch(s -> s.fluidVariants().contains(fluid)));
            invalidationCount += removed - recipeCosts.size();
        }

        void clear() {
            recipeCosts.clear();
        }

        int getInvalidationCount() {
            return invalidationCount;
        }
    }

    private static class RecipeCostCache {
        final double cost;
        final Map<Item, Double> usedItems;
        final Map<Fluid, Double> usedFluids;

        RecipeCostCache(double cost, Map<Item, Double> usedItems, Map<Fluid, Double> usedFluids) {
            this.cost = cost;
            this.usedItems = new HashMap<>(usedItems);
            this.usedFluids = new HashMap<>(usedFluids);
        }

        boolean isValid(Map<Item, Double> currentItems, Map<Fluid, Double> currentFluids) {
            return isValidMap(usedItems, currentItems) && isValidMap(usedFluids, currentFluids);
        }

        private <T> boolean isValidMap(Map<T, Double> cached, Map<T, Double> current) {
            for (Map.Entry<T, Double> entry : cached.entrySet()) {
                Double currentValue = current.get(entry.getKey());
                if (currentValue == null || Math.abs(currentValue - entry.getValue()) > CONVERGENCE_THRESHOLD)
                    return false;
            }
            return true;
        }

        boolean dependsOnItem(Item item) {
            return usedItems.containsKey(item);
        }

        boolean dependsOnFluid(Fluid fluid) {
            return usedFluids.containsKey(fluid);
        }
    }

    private record ComplexityResult(double complexity, RecipeNode recipe) {
    }
}