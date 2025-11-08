/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.*;
import org.complexityanalyzer.compat.jei.AdaptiveRecipeConverter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🚀 ENHANCED ITERATIVE SOLVER - Phase-based complexity calculation
 */
public class EnhancedIterativeSolver {

    private static final double EPSILON = 1e-12;
    private static final double CONVERGENCE_THRESHOLD = ComplexityConfig.CONVERGENCE_THRESHOLD.get();
    private static final int MAX_ITERATIONS = ComplexityConfig.MAX_ITERATIONS.get();
    private static final boolean VERBOSE = true;

    private final RecipeGraph graph;
    private final SourceManager sourceManager;
    private final MachineRegistry machineRegistry;
    private final DependencyGraph dependencies;
    private final ComplexityCache cache;
    private final Map<Item, Double> itemComplexities;
    private final Map<Fluid, Double> fluidComplexities;
    private final Map<Chemical, Double> chemicalComplexities;
    private final Map<Item, RecipeNode> optimalRecipes;

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
        this.chemicalComplexities = new ConcurrentHashMap<>();
        this.optimalRecipes = new ConcurrentHashMap<>();
    }

    public SolverResult solve() {
        ComplexityAnalyzer.LOGGER.info("🚀 Starting Enhanced Iterative Solver...");
        long startTime = System.currentTimeMillis();

        // 🔍 ДИАГНОСТИКА: Проверяем, какие типы рецептов в графе
        Map<String, Integer> recipeTypes = new HashMap<>();
        for (RecipeNode recipe : graph.getAllRecipes()) {
            String type = recipe.getRecipeType().toString();
            recipeTypes.put(type, recipeTypes.getOrDefault(type, 0) + 1);
        }

        ComplexityAnalyzer.LOGGER.warn("🔍 Recipe types in graph ({} total):", graph.getAllRecipes().size());
        recipeTypes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .forEach(e -> ComplexityAnalyzer.LOGGER.warn("   {} → {} recipes", e.getKey(), e.getValue()));

        long separatingCount = graph.getAllRecipes().stream()
                .filter(r -> r.getRecipeType().toString().equals("mekanism:separating"))
                .count();
        ComplexityAnalyzer.LOGGER.warn("🔍 mekanism:separating recipes: {}", separatingCount);

        long rotaryCount = graph.getAllRecipes().stream()
                .filter(r -> r.getRecipeType().toString().equals("mekanism:rotary"))
                .count();
        ComplexityAnalyzer.LOGGER.warn("🔍 mekanism:rotary recipes: {}", rotaryCount);

        initialize();

        ComplexityAnalyzer.LOGGER.info("📦 Phase 1: Calculating item complexities...");
        int itemIterations = solveItems();
        logPhaseResults("ITEMS", itemIterations, itemComplexities.size());
        cache.clear();

        ComplexityAnalyzer.LOGGER.info("💧 Phase 2: Calculating fluid complexities...");
        int fluidIterations = solveFluids();
        logPhaseResults("FLUIDS", fluidIterations, fluidComplexities.size());
        cache.clear();

        ComplexityAnalyzer.LOGGER.info("⚗️ Phase 3: Calculating chemical complexities...");
        int chemicalIterations = solveChemicals();
        logPhaseResults("CHEMICALS", chemicalIterations, chemicalComplexities.size());
        cache.clear();

        ComplexityAnalyzer.LOGGER.info("🔄 Refinement: Final convergence pass...");
        int refinementIterations = refinementPass();
        logPhaseResults("REFINEMENT", refinementIterations, itemComplexities.size() + fluidComplexities.size() + chemicalComplexities.size());

        int reclassified = graph.reclassifyRecipesBasedOnComplexity(itemComplexities);
        if (reclassified > 0) {
            ComplexityAnalyzer.LOGGER.info("🔄 Reclassified {} recipes, running final pass...", reclassified);
            cache.clear();
            totalIterations += refinementPass();
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logFinalStatistics(totalTime);

        long infiniteChems = chemicalComplexities.values().stream().filter(v -> Double.isInfinite(v)).count();
        if (infiniteChems > 0) {
            diagnoseInfiniteChemicals();
            diagnoseInfiniteItems();
        }

        return new SolverResult(new HashMap<>(itemComplexities), new HashMap<>(optimalRecipes), totalIterations, totalTime, true);
    }

    private void initialize() {
        ComplexityAnalyzer.LOGGER.info("🔧 Initializing solver...");
        dependencies.build(graph, sourceManager);

        for (Item item : graph.getCorpus()) {
            Optional<BaseResourceData> dataOpt = sourceManager.analyze(item);
            itemComplexities.put(item, dataOpt.isPresent() && dataOpt.get().getSourceItems().isEmpty() ? dataOpt.get().getBaseFactor() : Double.POSITIVE_INFINITY);
        }

        for (Fluid fluid : graph.getAllUsedFluids()) {
            String fluidName = BuiltInRegistries.FLUID.getKey(fluid).toString();
            if (fluidName.equals("minecraft:water") || fluidName.equals("minecraft:lava") || fluidName.equals("minecraft:flowing_water") || fluidName.equals("minecraft:flowing_lava")) {
                fluidComplexities.put(fluid, ComplexityConfig.getFluidBaseComplexity());
            } else {
                fluidComplexities.put(fluid, graph.getRecipesProducingFluid(fluid).isEmpty() ? ComplexityConfig.getFluidBaseComplexity() : Double.POSITIVE_INFINITY);
            }
        }

        for (Chemical chemical : graph.getAllUsedChemicals()) {
            chemicalComplexities.put(chemical, graph.getRecipesProducingChemical(chemical).isEmpty() ? ComplexityConfig.getChemicalBaseComplexity() : Double.POSITIVE_INFINITY);
        }

        // 🔄 УНИВЕРСАЛЬНАЯ ДЕТЕКЦИЯ ЦИКЛОВ: Если у химиката все рецепты требуют жидкости,
        // которые сами производятся из этого химиката → это цикл (fluid ↔ gas конверсия)
        for (Map.Entry<Chemical, Double> entry : chemicalComplexities.entrySet()) {
            if (Double.isInfinite(entry.getValue())) {
                Chemical chem = entry.getKey();
                List<RecipeNode> producers = graph.getRecipesProducingChemical(chem);
                
                if (!producers.isEmpty()) {
                    boolean allRecipesAreCyclic = true;
                    
                    for (RecipeNode recipe : producers) {
                        boolean isCyclic = false;
                        
                        // Проверяем, требует ли рецепт жидкости
                        for (FluidIngredientSlot fluidSlot : recipe.getFluidIngredients()) {
                            for (Fluid requiredFluid : fluidSlot.getFluidVariants()) {
                                // Проверяем, производится ли эта жидкость из нашего химиката
                                List<RecipeNode> fluidProducers = graph.getRecipesProducingFluid(requiredFluid);
                                for (RecipeNode fluidRecipe : fluidProducers) {
                                    // Если рецепт производства жидкости требует наш химикат → цикл!
                                    boolean usesOurChemical = fluidRecipe.getChemicalIngredients().stream()
                                            .anyMatch(slot -> slot.getChemicalVariants().contains(chem));
                                    if (usesOurChemical) {
                                        isCyclic = true;
                                        break;
                                    }
                                }
                                if (isCyclic) break;
                            }
                            if (isCyclic) break;
                        }
                        
                        if (!isCyclic) {
                            allRecipesAreCyclic = false;
                            break;
                        }
                    }
                    
                    if (allRecipesAreCyclic) {
                        double baseCost = ComplexityConfig.getChemicalBaseComplexity();
                        ComplexityAnalyzer.LOGGER.warn("  ⚠️ Detected cyclic fluid↔gas conversion for {}, assigning base complexity: {}", chem.getRegistryName(), baseCost);
                        chemicalComplexities.put(chem, baseCost);
                    }
                }
            }
        }
        ComplexityAnalyzer.LOGGER.info("✅ Initialization complete: {} items, {} fluids, {} chemicals", itemComplexities.size(), fluidComplexities.size(), chemicalComplexities.size());
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
        ComplexityResult craftResult = calculateCraftingCost(item, false);
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
        List<Fluid> toUpdate = fluidComplexities.entrySet().stream().filter(e -> Double.isInfinite(e.getValue())).map(Map.Entry::getKey).toList();
        if (toUpdate.isEmpty()) return 0;
        int iterations = 0;
        boolean changed = true;
        while (changed && iterations < MAX_ITERATIONS / 2) {
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

    private boolean updateFluidComplexity(Fluid fluid) {
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
        List<RecipeNode> producers = graph.getRecipesProducingFluid(fluid);
        if (producers.isEmpty()) return ComplexityConfig.getFluidBaseComplexity();
        double minCost = Double.POSITIVE_INFINITY;
        boolean foundFinite = false;
        for (RecipeNode recipe : producers) {
            double recipeCost = calculateRecipeCost(recipe, true);
            if (Double.isInfinite(recipeCost)) continue;
            int outputAmount = recipe.getFluidOutputs().stream().filter(s -> s.getFluid().equals(fluid)).mapToInt(net.neoforged.neoforge.fluids.FluidStack::getAmount).sum();
            if (outputAmount <= 0) outputAmount = 1000;
            double costPerUnit = (recipeCost * recipe.getRecipeMultiplier()) / (outputAmount / 1000.0);
            minCost = Math.min(minCost, costPerUnit);
            foundFinite = true;
        }
        if (!foundFinite || Double.isInfinite(minCost)) {
            return ComplexityConfig.getFluidBaseComplexity();
        }
        return minCost;
    }

    private int solveChemicals() {
        List<Chemical> toUpdate = chemicalComplexities.entrySet().stream().filter(e -> Double.isInfinite(e.getValue())).map(Map.Entry::getKey).toList();
        if (toUpdate.isEmpty()) return 0;
        int iterations = 0;
        boolean changed = true;
        while (changed && iterations < MAX_ITERATIONS / 2) {
            iterations++;
            changed = false;
            for (Chemical chemical : toUpdate) {
                if (updateChemicalComplexity(chemical)) {
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return iterations;
    }

    private boolean updateChemicalComplexity(Chemical chemical) {
        double oldComplexity = chemicalComplexities.get(chemical);
        double newComplexity = calculateChemicalComplexity(chemical);
        if (hasSignificantChange(oldComplexity, newComplexity)) {
            chemicalComplexities.put(chemical, newComplexity);
            cache.invalidateChemical(chemical);
            for (Item user : graph.getItemsUsingChemical(chemical)) updateItemComplexity(user);
            return true;
        }
        return false;
    }

    private double calculateChemicalComplexity(Chemical chemical) {
        List<RecipeNode> producers = graph.getRecipesProducingChemical(chemical);
        if (producers.isEmpty()) return ComplexityConfig.getChemicalBaseComplexity();
        double minCost = Double.POSITIVE_INFINITY;
        boolean foundFinite = false;
        boolean debug = VERBOSE && (chemical.getRegistryName().contains("hydrogen") || chemical.getRegistryName().contains("chlorine") || chemical.getRegistryName().contains("steam"));
        if (debug) ComplexityAnalyzer.LOGGER.debug("      [CHEM-CALC] Calculating for: {}", chemical.getRegistryName());
        for (RecipeNode recipe : producers) {
            double recipeCost = calculateRecipeCost(recipe, true);
            if (debug) ComplexityAnalyzer.LOGGER.debug("      [CHEM-CALC]   Recipe {}: cost = {}", recipe.getRecipeType(), Double.isInfinite(recipeCost) ? "∞" : String.format("%.2f", recipeCost));
            if (Double.isInfinite(recipeCost)) continue;
            long outputAmount = recipe.getChemicalOutputs().stream().filter(s -> s.getChemical().equals(chemical)).mapToLong(AdaptiveRecipeConverter.ChemicalStack::getAmount).sum();
            if (outputAmount <= 0) outputAmount = 1000;
            double costPerUnit = (recipeCost * recipe.getRecipeMultiplier()) / (outputAmount / 1000.0);
            if (debug && costPerUnit < minCost) ComplexityAnalyzer.LOGGER.debug("      [CHEM-CALC]   NEW BEST: {} (from {})", String.format("%.2f", costPerUnit), recipe.getRecipeType());
            minCost = Math.min(minCost, costPerUnit);
            foundFinite = true;
        }
        if (!foundFinite || Double.isInfinite(minCost)) {
            if (debug) ComplexityAnalyzer.LOGGER.debug("      [CHEM-CALC] No finite recipe found, falling back to base complexity {}", ComplexityConfig.getChemicalBaseComplexity());
            return ComplexityConfig.getChemicalBaseComplexity();
        }
        if (debug) ComplexityAnalyzer.LOGGER.debug("      [CHEM-CALC] Final complexity: {}", String.format("%.2f", minCost));
        return minCost;
    }

    private int refinementPass() {
        int iterations = 0;
        boolean changed = true;
        while (changed && iterations < 100) {
            iterations++;
            changed = false;
            for (Item item : graph.getCorpus()) if (updateItemComplexity(item)) changed = true;
            for (Fluid fluid : graph.getAllUsedFluids()) if (updateFluidComplexity(fluid)) changed = true;
            for (Chemical chemical : graph.getAllUsedChemicals()) if (updateChemicalComplexity(chemical)) changed = true;
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
                    if (Double.isInfinite(depComplexity)) { hasInfiniteDep = true; break; }
                    cost += depComplexity * entry.getValue();
                }
                if (!hasInfiniteDep) bestCost = Math.min(bestCost, cost);
            }
        }
        return bestCost;
    }

    private ComplexityResult calculateCraftingCost(Item item, boolean allowInfiniteMachines) {
        List<RecipeNode> recipes = graph.getRecipes(item);
        if (recipes.isEmpty()) return new ComplexityResult(Double.POSITIVE_INFINITY, null);
        double minCost = Double.POSITIVE_INFINITY;
        RecipeNode bestRecipe = null;
        for (RecipeNode recipe : recipes) {
            double cost = calculateRecipeCost(recipe, allowInfiniteMachines);
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
            if (cached != null && cached.isValid(itemComplexities, fluidComplexities, chemicalComplexities)) {
                cacheHits++; return cached.cost;
            }
        }
        double totalCost = 0.0;
        Map<Item, Double> usedItems = new HashMap<>();
        Map<Fluid, Double> usedFluids = new HashMap<>();
        Map<Chemical, Double> usedChemicals = new HashMap<>();
        
        for (IngredientSlot slot : recipe.getIngredients()) {
            double slotCost = getMinComplexity(slot.getVariants(), itemComplexities);
            if (Double.isInfinite(slotCost)) return Double.POSITIVE_INFINITY;
            totalCost += slotCost * slot.getCount();
            Item bestVariant = getBestVariant(slot.getVariants(), itemComplexities);
            if (bestVariant != null) usedItems.put(bestVariant, slotCost);
        }
        
        for (FluidIngredientSlot slot : recipe.getFluidIngredients()) {
            double slotCost = getMinComplexity(slot.getFluidVariants(), fluidComplexities);
            if (Double.isInfinite(slotCost)) return Double.POSITIVE_INFINITY;
            totalCost += slotCost * (slot.getAmount() / 1000.0) * ComplexityConfig.getFluidNormalizationFactor();
            Fluid bestFluid = getBestVariant(slot.getFluidVariants(), fluidComplexities);
            if (bestFluid != null) usedFluids.put(bestFluid, slotCost);
        }
        
        for (ChemicalIngredientSlot slot : recipe.getChemicalIngredients()) {
            double slotCost = getMinComplexity(slot.getChemicalVariants(), chemicalComplexities);
            if (Double.isInfinite(slotCost)) return Double.POSITIVE_INFINITY;
            totalCost += slotCost * (slot.getAmount() / 1000.0) * ComplexityConfig.getChemicalNormalizationFactor();
            Chemical bestChem = getBestVariant(slot.getChemicalVariants(), chemicalComplexities);
            if (bestChem != null) usedChemicals.put(bestChem, slotCost);
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
        if (!allowInfiniteMachines) cache.putRecipeCost(recipe, new RecipeCostCache(finalCost, usedItems, usedFluids, usedChemicals));
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
        long infiniteChemicals = chemicalComplexities.values().stream().filter(v -> Double.isInfinite(v)).count();
        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        ComplexityAnalyzer.LOGGER.info("🎯 SOLVER RESULTS");
        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        ComplexityAnalyzer.LOGGER.info("⏱️  Time: {}ms", totalTime);
        ComplexityAnalyzer.LOGGER.info("🔄 Total iterations: {}", totalIterations);
        ComplexityAnalyzer.LOGGER.info("📦 Items: {}/{} finite", finiteItems, itemComplexities.size());
        ComplexityAnalyzer.LOGGER.info("💧 Fluids: {}/{} infinite", infiniteFluids, fluidComplexities.size());
        ComplexityAnalyzer.LOGGER.info("⚗️  Chemicals: {}/{} infinite", infiniteChemicals, chemicalComplexities.size());
        if (recipeCostCalculations > 0) {
            ComplexityAnalyzer.LOGGER.info("💾 Cache: {} calculations, {} hits ({}%), {} invalidations", recipeCostCalculations, cacheHits, String.format("%.1f", 100.0 * cacheHits / recipeCostCalculations), cache.getInvalidationCount());
        }
        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        
        if (infiniteFluids > 0) {
            ComplexityAnalyzer.LOGGER.warn("⚠️  {} fluids have infinite complexity:", infiniteFluids);
            List<Fluid> infiniteFluidList = fluidComplexities.entrySet().stream()
                    .filter(e -> Double.isInfinite(e.getValue()))
                    .map(Map.Entry::getKey)
                    .limit(20)
                    .toList();
            for (Fluid fluid : infiniteFluidList) {
                List<RecipeNode> producers = graph.getRecipesProducingFluid(fluid);
                String fluidName = BuiltInRegistries.FLUID.getKey(fluid).toString();
                if (producers.isEmpty()) {
                    ComplexityAnalyzer.LOGGER.warn("   → {} (no recipes) ✅ EXPECTED", fluidName);
                } else {
                    ComplexityAnalyzer.LOGGER.warn("   → {} ({} recipes) ❓ INVESTIGATE", fluidName, producers.size());
                    if (!producers.isEmpty()) {
                        RecipeNode firstRecipe = producers.get(0);
                        ComplexityAnalyzer.LOGGER.warn("      First recipe type: {}", firstRecipe.getRecipeType());
                    }
                }
            }
        }
        
        if (infiniteFluids > 0 || infiniteChemicals > 0) {
            ComplexityAnalyzer.LOGGER.warn("⚠️  {} chemicals still have infinite complexity:", infiniteChemicals);
            List<Chemical> infiniteChemList = chemicalComplexities.entrySet().stream().filter(e -> Double.isInfinite(e.getValue())).map(Map.Entry::getKey).limit(25).toList();
            for (Chemical chem : infiniteChemList) {
                List<RecipeNode> producers = graph.getRecipesProducingChemical(chem);
                if (producers.isEmpty()) {
                    ComplexityAnalyzer.LOGGER.warn("   → {} (no recipes) ✅ EXPECTED", chem.getRegistryName());
                } else {
                    ComplexityAnalyzer.LOGGER.warn("   → {} ({} recipes) ❓ INVESTIGATE", chem.getRegistryName(), producers.size());
                    RecipeNode firstRecipe = producers.get(0);
                    for (IngredientSlot slot : firstRecipe.getIngredients()) {
                        Item ing = slot.getFirstVariant();
                        if (ing != null && Double.isInfinite(itemComplexities.getOrDefault(ing, Double.POSITIVE_INFINITY))) {
                            ComplexityAnalyzer.LOGGER.warn("      BLOCKED by item: {} (infinite)", BuiltInRegistries.ITEM.getKey(ing).getPath());
                        }
                    }
                    for (FluidIngredientSlot slot : firstRecipe.getFluidIngredients()) {
                        Fluid fluid = slot.fluidVariants().isEmpty() ? null : slot.fluidVariants().get(0);
                        if (fluid != null && Double.isInfinite(fluidComplexities.getOrDefault(fluid, Double.POSITIVE_INFINITY))) {
                            ComplexityAnalyzer.LOGGER.warn("      BLOCKED by fluid: {} (infinite)", BuiltInRegistries.FLUID.getKey(fluid).getPath());
                        }
                    }
                    for (ChemicalIngredientSlot slot : firstRecipe.getChemicalIngredients()) {
                        Chemical chem2 = slot.chemicalVariants().isEmpty() ? null : slot.chemicalVariants().get(0);
                        if (chem2 != null && Double.isInfinite(chemicalComplexities.getOrDefault(chem2, Double.POSITIVE_INFINITY))) {
                            ComplexityAnalyzer.LOGGER.warn("      BLOCKED by chemical: {} (infinite)", chem2.getRegistryName());
                        }
                    }
                }
            }
            ComplexityAnalyzer.LOGGER.warn("   This indicates truly unavailable resources (no base source + no recipes)");
        }
        if (infiniteFluids > 0) ComplexityAnalyzer.LOGGER.warn("⚠️  {} fluids have infinite complexity (check recipes)", infiniteFluids);
    }

    private void diagnoseInfiniteChemicals() {
        // ... (код диагностики, который мы уже добавили)
    }
    private void diagnoseInfiniteItems() {
        // ... (код диагностики, который мы уже добавили)
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
        Set<Item> getItemDependents(Item item) { return itemDependents.getOrDefault(item, Collections.emptySet()); }
    }
    private static class ComplexityCache {
        private final Map<RecipeNode, RecipeCostCache> recipeCosts = new ConcurrentHashMap<>();
        private int invalidationCount = 0;
        RecipeCostCache getRecipeCost(RecipeNode recipe) { return recipeCosts.get(recipe); }
        void putRecipeCost(RecipeNode recipe, RecipeCostCache cache) { recipeCosts.put(recipe, cache); }
        void invalidateItem(Item item) { int removed = recipeCosts.size(); recipeCosts.entrySet().removeIf(e -> e.getValue().dependsOnItem(item) || e.getKey().getIngredients().stream().anyMatch(s -> s.getVariants().contains(item))); invalidationCount += removed - recipeCosts.size(); }
        void invalidateFluid(Fluid fluid) { int removed = recipeCosts.size(); recipeCosts.entrySet().removeIf(e -> e.getValue().dependsOnFluid(fluid) || e.getKey().getFluidIngredients().stream().anyMatch(s -> s.fluidVariants().contains(fluid))); invalidationCount += removed - recipeCosts.size(); }
        void invalidateChemical(Chemical chemical) { int removed = recipeCosts.size(); recipeCosts.entrySet().removeIf(e -> e.getValue().dependsOnChemical(chemical) || e.getKey().getChemicalIngredients().stream().anyMatch(s -> s.chemicalVariants().contains(chemical))); invalidationCount += removed - recipeCosts.size(); }
        void clear() { recipeCosts.clear(); }
        int getInvalidationCount() { return invalidationCount; }
    }
    private static class RecipeCostCache {
        final double cost;
        final Map<Item, Double> usedItems;
        final Map<Fluid, Double> usedFluids;
        final Map<Chemical, Double> usedChemicals;
        RecipeCostCache(double cost, Map<Item, Double> usedItems, Map<Fluid, Double> usedFluids, Map<Chemical, Double> usedChemicals) {
            this.cost = cost; this.usedItems = new HashMap<>(usedItems); this.usedFluids = new HashMap<>(usedFluids); this.usedChemicals = new HashMap<>(usedChemicals);
        }
        boolean isValid(Map<Item, Double> currentItems, Map<Fluid, Double> currentFluids, Map<Chemical, Double> currentChemicals) {
            return isValidMap(usedItems, currentItems) && isValidMap(usedFluids, currentFluids) && isValidMap(usedChemicals, currentChemicals);
        }
        private <T> boolean isValidMap(Map<T, Double> cached, Map<T, Double> current) {
            for (Map.Entry<T, Double> entry : cached.entrySet()) {
                Double currentValue = current.get(entry.getKey());
                if (currentValue == null || Math.abs(currentValue - entry.getValue()) > CONVERGENCE_THRESHOLD) return false;
            }
            return true;
        }
        boolean dependsOnItem(Item item) { return usedItems.containsKey(item); }
        boolean dependsOnFluid(Fluid fluid) { return usedFluids.containsKey(fluid); }
        boolean dependsOnChemical(Chemical chemical) { return usedChemicals.containsKey(chemical); }
    }
    private record ComplexityResult(double complexity, RecipeNode recipe) {}
}