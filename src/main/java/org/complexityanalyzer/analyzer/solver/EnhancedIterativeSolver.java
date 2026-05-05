/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.MachineRegistry;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.compat.jei.AdaptiveRecipeConverter;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.graph.*;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class EnhancedIterativeSolver {

    private static final double EPSILON = 1e-12;
    private static final double CONVERGENCE_THRESHOLD = ComplexityConfig.CONVERGENCE_THRESHOLD.get();
    private static final int MAX_ITERATIONS = ComplexityConfig.MAX_ITERATIONS.get();

    private final RecipeGraph graph;
    private final SourceManager sourceManager;
    private final MachineRegistry machineRegistry;
    private final DependencyGraph dependencies;
    private final SolverCache cache;
    private final Reference2DoubleMap<Item> itemComplexities;
    private final Reference2DoubleMap<Fluid> fluidComplexities;
    private final Reference2ObjectMap<Item, RecipeNode> optimalRecipes;
    private final ChemicalComplexityManager chemicalManager;
    private final int parallelism;

    private final ThreadLocal<ReferenceSet<Fluid>> fluidCalculationStack = ThreadLocal.withInitial(ReferenceOpenHashSet::new);

    private final AtomicInteger totalIterations = new AtomicInteger(0);
    private final AtomicInteger recipeCostCalculations = new AtomicInteger(0);
    private final AtomicInteger cacheHits = new AtomicInteger(0);

    public EnhancedIterativeSolver(RecipeGraph graph, SourceManager sourceManager, MachineRegistry machineRegistry) {
        this.graph = graph;
        this.sourceManager = sourceManager;
        this.machineRegistry = machineRegistry;
        this.dependencies = new DependencyGraph();
        this.cache = new SolverCache();
        this.itemComplexities = Reference2DoubleMaps.synchronize(new Reference2DoubleOpenHashMap<>());
        this.fluidComplexities = Reference2DoubleMaps.synchronize(new Reference2DoubleOpenHashMap<>());
        this.optimalRecipes = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        this.chemicalManager = new ChemicalComplexityManager();
        this.parallelism = ThreadPoolManager.getInstance().getParallelism();
    }

    public SolverResult solve() {
        ComplexityAnalyzer.LOGGER.info("🚀 Starting Enhanced Iterative Solver with {} threads...", parallelism);
        long startTime = System.currentTimeMillis();

        var recipeTypes = new Object2IntOpenHashMap<String>();
        for (var recipe : graph.getAllRecipes()) recipeTypes.addTo(recipe.getRecipeType().toString(), 1);

        ComplexityAnalyzer.LOGGER.debug("🔍 Recipe types in graph ({} total):", graph.getAllRecipes().size());
        var sortedTypes = new ObjectArrayList<>(recipeTypes.object2IntEntrySet());
        sortedTypes.sort((a, b) -> Integer.compare(b.getIntValue(), a.getIntValue()));

        for (int i = 0; i < Math.min(30, sortedTypes.size()); i++) {
            var e = sortedTypes.get(i);
            ComplexityAnalyzer.LOGGER.debug("   {} → {} recipes", e.getKey(), e.getIntValue());
        }

        initialize();

        ComplexityAnalyzer.LOGGER.info("📦 Phase 1: Calculating item complexities (parallel)...");
        int itemIterations = solveItemsParallel();
        logPhaseResults("ITEMS", itemIterations, itemComplexities.size());
        cache.clear();

        ComplexityAnalyzer.LOGGER.info("💧 Phase 2: Calculating fluid complexities (parallel)...");
        int fluidIterations = solveFluidsParallel();
        logPhaseResults("FLUIDS", fluidIterations, fluidComplexities.size());
        cache.clear();

        ComplexityAnalyzer.LOGGER.info("🧪 Phase 3: Calculating chemical complexities (parallel)...");
        int chemIterations = solveChemicalsParallel();
        logPhaseResults("CHEMICALS", chemIterations, chemicalManager.size());

        for (var chemId : chemicalManager.getAllChemicals()) {
            var fluid = BuiltInRegistries.FLUID.get(chemId);
            if (fluid != Fluids.EMPTY) {
                var chemComplexity = chemicalManager.getComplexity(chemId);
                var fluidComplexity = fluidComplexities.getOrDefault(fluid, Double.POSITIVE_INFINITY);

                if (!Double.isInfinite(chemComplexity)) {
                    if (chemComplexity < fluidComplexity || fluidComplexity < EPSILON) {
                        fluidComplexities.put(fluid, chemComplexity);
                    }
                }
            }
        }

        cache.clear();

        ComplexityAnalyzer.LOGGER.info("🔄 Refinement: Final convergence pass (parallel)...");
        int refinementIterations = refinementPassParallel();
        logPhaseResults("REFINEMENT", refinementIterations,
                itemComplexities.size() + fluidComplexities.size() + chemicalManager.size());

        int reclassified = graph.reclassifyRecipesBasedOnComplexity(itemComplexities);
        if (reclassified > 0) {
            ComplexityAnalyzer.LOGGER.info("🔄 Reclassified {} recipes, running final pass...", reclassified);
            cache.clear();
            totalIterations.addAndGet(refinementPassParallel());
        }

        cleanupThreadLocals();

        long totalTime = System.currentTimeMillis() - startTime;
        logFinalStatistics(totalTime);

        return new SolverResult(new Reference2DoubleOpenHashMap<>(itemComplexities),
                new Reference2ObjectOpenHashMap<>(optimalRecipes), totalIterations.get(), totalTime, true);
    }

    private void cleanupThreadLocals() {
        try {
            var pool = ThreadPoolManager.getInstance().getComputePool();
            var cleanups = new ObjectArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < parallelism; i++) {
                cleanups.add(CompletableFuture.runAsync(() -> {
                    var set = fluidCalculationStack.get();
                    set.clear();
                    fluidCalculationStack.remove();
                }, pool));
            }
            try {
                CompletableFuture.allOf(cleanups.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("ThreadLocal cleanup warning: {}", e.getMessage());
        }
    }

    private void initialize() {
        ComplexityAnalyzer.LOGGER.info("🔧 Initializing solver...");
        dependencies.build(graph, sourceManager);

        for (var item : graph.getCorpus()) {
            var data = sourceManager.analyze(item);
            var complexity = data != null && data.getSourceItems().isEmpty() ? data.getBaseFactor() : Double.POSITIVE_INFINITY;
            itemComplexities.put(item, complexity);
        }

        for (var fluid : graph.getAllUsedFluids()) {
            var id = BuiltInRegistries.FLUID.getKey(fluid);
            var fluidName = id.toString();
            if (fluidName.equals("minecraft:water") || fluidName.equals("minecraft:lava")) {
                fluidComplexities.put(fluid, 1.0);
            } else {
                fluidComplexities.put(fluid, Double.POSITIVE_INFINITY);
            }
        }

        var chemicalsRegistered = new AtomicInteger(0);
        var chemicalRecipesCreated = new AtomicInteger(0);

        for (var recipe : graph.getAllRecipes()) {
            var itemInputs = new Reference2DoubleOpenHashMap<Item>();
            var fluidInputs = new Reference2DoubleOpenHashMap<Fluid>();

            var chemOutputs = recipe.getChemicalOutputs();
            if (chemOutputs.isEmpty()) {
                var rawRecipe = recipe.getRawRecipeRef();
                if (rawRecipe != null) {
                    chemOutputs = AdaptiveRecipeConverter.extractChemicalOutputs(rawRecipe, null);
                }
            }

            if (chemOutputs.isEmpty()) continue;

            for (var output : chemOutputs) {
                if (!chemicalManager.getAllChemicals().contains(output.id())) {
                    chemicalManager.registerChemical(output.id(), Double.POSITIVE_INFINITY);
                    chemicalsRegistered.incrementAndGet();
                }

                var chemInputs = new Object2DoubleOpenHashMap<ResourceLocation>();

                for (var slot : recipe.getIngredients()) {
                    var variants = slot.getVariants();
                    if (!variants.isEmpty()) itemInputs.put(variants.getFirst(), slot.getCount());
                }

                for (var slot : recipe.getFluidIngredients()) {
                    var variants = slot.getFluidVariants();
                    if (!variants.isEmpty()) fluidInputs.put(variants.getFirst(), slot.getAmount() / 1000.0);
                }

                var rawRecipeForInputs = recipe.getRawRecipeRef();
                if (rawRecipeForInputs != null) {
                    var chemInputsList = AdaptiveRecipeConverter.extractChemicalInputs(rawRecipeForInputs);
                    for (var chemInput : chemInputsList) chemInputs.put(chemInput.id(), chemInput.amount() / 1000.0);
                }

                var machineComplexity = 0.0;
                if (machineRegistry != null) {
                    var machineItem = machineRegistry.getMachineForRecipe(recipe.getRecipeType());
                    if (machineItem != null) {
                        machineComplexity = itemComplexities.getOrDefault(machineItem, 0.0);
                        if (Double.isInfinite(machineComplexity)) {
                            machineComplexity = ComplexityConfig.getMachineBaseComplexity();
                        }
                        machineComplexity *= ComplexityConfig.getMachineTaxMultiplier();
                    }
                }

                var chemRecipe = new ChemicalComplexityManager.ChemicalRecipe(
                        itemInputs,
                        fluidInputs,
                        chemInputs,
                        output.amount() / 1000.0,
                        machineComplexity,
                        recipe.getRecipeMultiplier()
                );

                chemicalManager.addProducingRecipe(output.id(), chemRecipe);
                chemicalRecipesCreated.incrementAndGet();
            }
        }

        ComplexityAnalyzer.LOGGER.info("✅ Initialization complete: {} items, {} fluids, {} chemicals ({} recipes)",
                itemComplexities.size(), fluidComplexities.size(), chemicalsRegistered.get(), chemicalRecipesCreated.get());
    }

    private int solveChemicalsParallel() {
        var toUpdate = new ObjectArrayList<ResourceLocation>();
        for (var chemId : chemicalManager.getAllChemicals()) {
            if (Double.isInfinite(chemicalManager.getComplexity(chemId))) toUpdate.add(chemId);
        }

        if (toUpdate.isEmpty()) return 0;

        int iterations = 0;
        var changed = new AtomicBoolean(true);

        while (iterations < MAX_ITERATIONS / 2 && changed.get()) {
            iterations++;
            changed.set(false);

            toUpdate.parallelStream().forEach(chemId -> {
                if (updateChemicalComplexity(chemId)) changed.set(true);
            });
        }

        return iterations;
    }

    private boolean updateChemicalComplexity(ResourceLocation chemId) {
        var oldComplexity = chemicalManager.getComplexity(chemId);
        var newComplexity = chemicalManager.calculateComplexity(chemId, itemComplexities, fluidComplexities);

        if (hasSignificantChange(oldComplexity, newComplexity)) {
            chemicalManager.setComplexity(chemId, newComplexity);
            return true;
        }
        return false;
    }

    private int solveItemsParallel() {
        var allItems = new ObjectArrayList<>(graph.getCorpus());
        var updateQueue = new ConcurrentLinkedQueue<>(allItems);
        var needsUpdate = ReferenceSets.synchronize(new ReferenceOpenHashSet<>(allItems));

        int iterations = 0;
        var changed = new AtomicBoolean(true);

        while (!updateQueue.isEmpty() && iterations < MAX_ITERATIONS && changed.get()) {
            iterations++;
            changed.set(false);

            var batch = new ObjectArrayList<Item>();
            Item item;
            int batchSize = Math.min(500, updateQueue.size());
            for (int i = 0; i < batchSize && (item = updateQueue.poll()) != null; i++) {
                needsUpdate.remove(item);
                batch.add(item);
            }

            var dependentsToAdd = ReferenceSets.synchronize(new ReferenceOpenHashSet<Item>());

            batch.parallelStream().forEach(batchItem -> {
                if (updateItemComplexity(batchItem)) {
                    changed.set(true);
                    var deps = dependencies.getItemDependents(batchItem);
                    for (var dependent : deps) if (needsUpdate.add(dependent)) dependentsToAdd.add(dependent);
                }
            });

            updateQueue.addAll(dependentsToAdd);
        }

        return iterations;
    }

    private boolean updateItemComplexity(Item item) {
        var oldComplexity = itemComplexities.getOrDefault(item, Double.POSITIVE_INFINITY);
        var sourceCost = calculateSourceCost(item);
        var craftResult = calculateCraftingCost(item);
        var newComplexity = Math.min(sourceCost, craftResult.complexity());

        if (hasSignificantChange(oldComplexity, newComplexity)) {
            itemComplexities.put(item, newComplexity);
            if (craftResult.recipe() != null && !Double.isInfinite(craftResult.complexity())
                    && craftResult.complexity() + EPSILON < sourceCost) {
                optimalRecipes.put(item, craftResult.recipe());
            } else {
                optimalRecipes.remove(item);
            }
            cache.invalidateItem(item);
            return true;
        }
        return false;
    }

    private int solveFluidsParallel() {
        var toUpdate = new ObjectArrayList<Fluid>();
        for (var entry : fluidComplexities.reference2DoubleEntrySet()) {
            if (Double.isInfinite(entry.getDoubleValue())) toUpdate.add(entry.getKey());
        }

        if (toUpdate.isEmpty()) return 0;

        int iterations = 0;
        var changed = new AtomicBoolean(true);

        while (iterations < MAX_ITERATIONS / 2 && changed.get()) {
            iterations++;
            changed.set(false);

            toUpdate.parallelStream().forEach(fluid -> {
                if (updateFluidComplexity(fluid)) changed.set(true);
            });
        }

        return iterations;
    }

    private static final ObjectSet<String> PROTECTED_FLUIDS = new ObjectOpenHashSet<>(new String[]{
            "minecraft:water",
            "minecraft:lava"
    });

    private boolean isProtectedFluid(Fluid fluid) {
        return PROTECTED_FLUIDS.contains(BuiltInRegistries.FLUID.getKey(fluid).toString());
    }

    private boolean updateFluidComplexity(Fluid fluid) {
        if (isProtectedFluid(fluid)) return false;

        var oldComplexity = fluidComplexities.getOrDefault(fluid, Double.POSITIVE_INFINITY);
        var newComplexity = calculateFluidComplexity(fluid);

        if (hasSignificantChange(oldComplexity, newComplexity)) {
            fluidComplexities.put(fluid, newComplexity);
            cache.invalidateFluid(fluid);
            for (var item : graph.getItemsUsingFluid(fluid)) updateItemComplexity(item);
            return true;
        }
        return false;
    }

    private double calculateFluidComplexity(Fluid fluid) {
        if (isProtectedFluid(fluid)) return 1.0;

        var stack = fluidCalculationStack.get();
        if (stack.contains(fluid)) return Double.POSITIVE_INFINITY;

        stack.add(fluid);
        try {
            var producers = graph.getRecipesProducingFluid(fluid);
            if (producers.isEmpty()) return Double.POSITIVE_INFINITY;

            var minCost = Double.POSITIVE_INFINITY;

            for (var recipe : producers) {
                var recipeCost = calculateRecipeCost(recipe, true);

                if (Double.isInfinite(recipeCost)) {
                    var rawRecipe = recipe.getRawRecipeRef();
                    if (rawRecipe != null) {
                        var chemInputs = AdaptiveRecipeConverter.extractChemicalInputs(rawRecipe);
                        for (var chemInput : chemInputs) {
                            var chemComplexity = chemicalManager.getComplexity(chemInput.id());
                            if (!Double.isInfinite(chemComplexity)) {
                                recipeCost = chemComplexity;
                                break;
                            }
                        }
                    }
                    if (Double.isInfinite(recipeCost)) continue;
                }

                var outputAmount = 0;
                for (var s : recipe.getFluidOutputs()) if (s.getFluid().equals(fluid)) outputAmount += s.getAmount();

                if (outputAmount <= 0) outputAmount = 1000;
                var costPerUnit = (recipeCost * recipe.getRecipeMultiplier()) / (outputAmount / 1000.0);
                minCost = Math.min(minCost, costPerUnit);
            }

            var fluidId = BuiltInRegistries.FLUID.getKey(fluid);
            if (chemicalManager.getAllChemicals().contains(fluidId)) {
                var chemComplexity = chemicalManager.getComplexity(fluidId);
                if (!Double.isInfinite(chemComplexity) && chemComplexity < minCost) minCost = chemComplexity;
            }

            return minCost;
        } finally {
            stack.remove(fluid);
        }
    }

    private int refinementPassParallel() {
        int iterations = 0;
        var changed = new AtomicBoolean(true);

        var items = new ObjectArrayList<>(graph.getCorpus());
        var fluids = new ObjectArrayList<>(graph.getAllUsedFluids());
        var chemicals = new ObjectArrayList<>(chemicalManager.getAllChemicals());

        while (changed.get() && iterations < 100) {
            iterations++;
            changed.set(false);

            items.parallelStream().forEach(item -> {
                if (updateItemComplexity(item)) changed.set(true);
            });

            fluids.parallelStream().forEach(fluid -> {
                if (updateFluidComplexity(fluid)) changed.set(true);
            });

            chemicals.parallelStream().forEach(chemId -> {
                if (updateChemicalComplexity(chemId)) changed.set(true);
            });
        }

        return iterations;
    }

    private double calculateSourceCost(Item item) {
        var allSources = sourceManager.findAllSources(item);
        if (allSources.isEmpty()) return Double.POSITIVE_INFINITY;

        var bestCost = Double.POSITIVE_INFINITY;
        for (var data : allSources) {
            var cost = data.getBaseFactor();
            var sourceItems = data.getSourceItems();

            if (sourceItems.isEmpty()) {
                bestCost = Math.min(bestCost, cost);
            } else {
                var hasInfiniteDep = false;
                for (var entry : sourceItems.reference2DoubleEntrySet()) {
                    var depComplexity = itemComplexities.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
                    if (Double.isInfinite(depComplexity)) {
                        hasInfiniteDep = true;
                        break;
                    }
                    cost += depComplexity * entry.getDoubleValue();
                }
                if (!hasInfiniteDep) bestCost = Math.min(bestCost, cost);
            }
        }
        return bestCost;
    }

    private ComplexityResult calculateCraftingCost(Item item) {
        var recipes = graph.getRecipes(item);
        if (recipes.isEmpty()) return new ComplexityResult(Double.POSITIVE_INFINITY, null);

        var minCost = Double.POSITIVE_INFINITY;
        var bestRecipe = (RecipeNode) null;

        for (var recipe : recipes) {
            var cost = calculateRecipeCost(recipe, false);
            if (cost < minCost) {
                minCost = cost;
                bestRecipe = recipe;
            }
        }
        return new ComplexityResult(minCost, bestRecipe);
    }

    private double calculateRecipeCost(RecipeNode recipe, boolean allowInfiniteMachines) {
        recipeCostCalculations.incrementAndGet();

        if (!allowInfiniteMachines) {
            var cached = cache.getRecipeCost(recipe);
            if (cached != null && cached.isValid(itemComplexities, fluidComplexities)) {
                cacheHits.incrementAndGet();
                return cached.cost;
            }
        }

        var totalCost = 0.0;
        var usedItems = new Reference2DoubleOpenHashMap<Item>();
        var usedFluids = new Reference2DoubleOpenHashMap<Fluid>();

        for (var slot : recipe.getIngredients()) {
            var variants = slot.getVariants();
            var slotCost = getMinComplexity(variants, itemComplexities);
            if (Double.isInfinite(slotCost)) return Double.POSITIVE_INFINITY;
            totalCost += slotCost * slot.getCount();

            var bestVariant = getBestVariant(variants, itemComplexities);
            if (bestVariant != null) usedItems.put(bestVariant, slotCost);
        }

        for (var slot : recipe.getFluidIngredients()) {
            var variants = slot.getFluidVariants();
            var slotCost = getMinComplexity(variants, fluidComplexities);

            if (Double.isInfinite(slotCost)) if (allowInfiniteMachines) {
                var bestFluid = getBestVariant(variants, fluidComplexities);
                if (bestFluid != null) {
                    var fluidId = BuiltInRegistries.FLUID.getKey(bestFluid);
                    var chemComplexity = chemicalManager.getComplexity(fluidId);
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

            totalCost += slotCost * (slot.getAmount() / 1000.0) * ComplexityConfig.getFluidNormalizationFactor();
            var bestFluid = getBestVariant(variants, fluidComplexities);
            if (bestFluid != null) usedFluids.put(bestFluid, slotCost);
        }

        if (machineRegistry != null) {
            var machineItem = machineRegistry.getMachineForRecipe(recipe.getRecipeType());
            if (machineItem != null) {
                var machineComplexity = itemComplexities.getOrDefault(machineItem, 0.0);

                if (isZeroCostMachine(recipe.getRecipeType())) {
                    machineComplexity = 0.0;
                } else if (Double.isInfinite(machineComplexity)) {
                    if (allowInfiniteMachines) {
                        machineComplexity = ComplexityConfig.getMachineBaseComplexity();
                    } else {
                        return Double.POSITIVE_INFINITY;
                    }
                }

                if (machineComplexity > 0.0) {
                    totalCost += machineComplexity * ComplexityConfig.getMachineTaxMultiplier();
                }
            }
        }

        var multiplier = recipe.getRecipeMultiplier();
        var resultCount = recipe.getResultCount();
        if (resultCount <= 0 || Double.isInfinite(multiplier)) return Double.POSITIVE_INFINITY;

        var finalCost = (totalCost * multiplier) / resultCount;

        if (!allowInfiniteMachines) cache.putRecipeCost(recipe, new RecipeCostCache(finalCost, usedItems, usedFluids));

        return finalCost;
    }

    private boolean isZeroCostMachine(RecipeType<?> recipeType) {
        if (recipeType == null) return false;
        var typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);
        if (typeId == null) return false;
        return typeId.equals(BuiltInRegistries.RECIPE_TYPE.getKey(RecipeType.CRAFTING));
    }

    private <T> double getMinComplexity(ObjectList<T> variants, Reference2DoubleMap<T> complexities) {
        if (variants.isEmpty()) return Double.POSITIVE_INFINITY;
        var min = Double.POSITIVE_INFINITY;
        for (var v : variants) {
            var c = complexities.getOrDefault(v, Double.POSITIVE_INFINITY);
            if (c < min) min = c;
        }
        return min;
    }

    private <T> @Nullable T getBestVariant(ObjectList<T> variants, Reference2DoubleMap<T> complexities) {
        if (variants.isEmpty()) return null;
        var min = Double.POSITIVE_INFINITY;
        var best = (T) null;
        for (var v : variants) {
            var c = complexities.getOrDefault(v, Double.POSITIVE_INFINITY);
            if (c < min) {
                min = c;
                best = v;
            }
        }
        return best;
    }

    private boolean hasSignificantChange(double oldValue, double newValue) {
        if (Double.isInfinite(oldValue) && Double.isInfinite(newValue)) return false;
        if (Double.isInfinite(oldValue) || Double.isInfinite(newValue)) return true;
        var delta = Math.abs(oldValue - newValue);
        return delta > CONVERGENCE_THRESHOLD && (oldValue <= EPSILON ? delta : delta / oldValue) > CONVERGENCE_THRESHOLD;
    }

    private void logPhaseResults(String phase, int iterations, int elementsProcessed) {
        ComplexityAnalyzer.LOGGER.info("  ✅ {} complete: {} iterations, {} elements", phase, iterations, elementsProcessed);
        totalIterations.addAndGet(iterations);
    }

    private void logFinalStatistics(long totalTime) {
        var finiteItems = 0L;
        for (var v : itemComplexities.values()) if (!Double.isInfinite(v)) finiteItems++;

        var infiniteFluids = 0L;
        for (var v : fluidComplexities.values()) if (Double.isInfinite(v)) infiniteFluids++;

        var finiteChemicals = 0L;
        for (var id : chemicalManager.getAllChemicals()) {
            if (!Double.isInfinite(chemicalManager.getComplexity(id))) finiteChemicals++;
        }

        var totalIter = totalIterations.get();
        var recipeCosts = recipeCostCalculations.get();
        var hits = cacheHits.get();

        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        ComplexityAnalyzer.LOGGER.info("🎯 SOLVER RESULTS (parallelism: {})", parallelism);
        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        ComplexityAnalyzer.LOGGER.info("⏱️  Time: {}ms", totalTime);
        ComplexityAnalyzer.LOGGER.info("🔄 Total iterations: {}", totalIter);
        ComplexityAnalyzer.LOGGER.info("📦 Items: {}/{} finite", finiteItems, itemComplexities.size());
        ComplexityAnalyzer.LOGGER.info("💧 Fluids: {}/{} infinite", infiniteFluids, fluidComplexities.size());
        ComplexityAnalyzer.LOGGER.info("🧪 Chemicals: {}/{} finite", finiteChemicals, chemicalManager.size());

        if (recipeCosts > 0) ComplexityAnalyzer.LOGGER.info("💾 Cache: {} calculations, {} hits ({}%), {} invalidations",
                recipeCosts, hits,
                String.format("%.1f", 100.0 * hits / recipeCosts),
                cache.getInvalidationCount());
    }

    private static class DependencyGraph {
        private final Reference2ObjectMap<Item, ReferenceSet<Item>> itemDependents = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

        void build(RecipeGraph graph, SourceManager sourceManager) {
            for (var recipe : graph.getAllRecipes()) {
                var result = recipe.getResultItem();
                for (var slot : recipe.getIngredients()) {
                    for (var ingredient : slot.getVariants()) {
                        itemDependents.computeIfAbsent(ingredient, k -> ReferenceSets.synchronize(new ReferenceOpenHashSet<>())).add(result);
                    }
                }
            }

            for (var item : graph.getCorpus()) {
                for (var source : sourceManager.findAllSources(item)) {
                    for (var sourceItem : source.getSourceItems().keySet()) {
                        itemDependents.computeIfAbsent(sourceItem, k -> ReferenceSets.synchronize(new ReferenceOpenHashSet<>())).add(item);
                    }
                }
            }
        }

        ReferenceSet<Item> getItemDependents(Item item) {
            return itemDependents.getOrDefault(item, ReferenceSets.emptySet());
        }
    }

    private static class SolverCache {
        private final Reference2ObjectMap<RecipeNode, RecipeCostCache> recipeCosts = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        private final AtomicInteger invalidationCount = new AtomicInteger(0);

        @Nullable
        RecipeCostCache getRecipeCost(RecipeNode recipe) {
            return recipeCosts.get(recipe);
        }

        void putRecipeCost(RecipeNode recipe, RecipeCostCache cache) {
            recipeCosts.put(recipe, cache);
        }

        void invalidateItem(Item item) {
            var removedCount = new AtomicInteger(0);
            recipeCosts.entrySet().removeIf(e -> {
                if (e.getValue().dependsOnItem(item)) {
                    removedCount.incrementAndGet();
                    return true;
                }
                for (var slot : e.getKey().getIngredients()) {
                    if (slot.getVariants().contains(item)) {
                        removedCount.incrementAndGet();
                        return true;
                    }
                }
                return false;
            });
            invalidationCount.addAndGet(removedCount.get());
        }

        void invalidateFluid(Fluid fluid) {
            var removedCount = new AtomicInteger(0);
            recipeCosts.entrySet().removeIf(e -> {
                if (e.getValue().dependsOnFluid(fluid)) {
                    removedCount.incrementAndGet();
                    return true;
                }
                for (var slot : e.getKey().getFluidIngredients()) {
                    if (slot.fluidVariants().contains(fluid)) {
                        removedCount.incrementAndGet();
                        return true;
                    }
                }
                return false;
            });
            invalidationCount.addAndGet(removedCount.get());
        }

        void clear() {
            recipeCosts.clear();
        }

        int getInvalidationCount() {
            return invalidationCount.get();
        }
    }

    private static class RecipeCostCache {
        final double cost;
        final Reference2DoubleMap<Item> usedItems;
        final Reference2DoubleMap<Fluid> usedFluids;

        RecipeCostCache(double cost, Reference2DoubleMap<Item> usedItems, Reference2DoubleMap<Fluid> usedFluids) {
            this.cost = cost;
            this.usedItems = new Reference2DoubleOpenHashMap<>(usedItems);
            this.usedFluids = new Reference2DoubleOpenHashMap<>(usedFluids);
        }

        boolean isValid(Reference2DoubleMap<Item> currentItems, Reference2DoubleMap<Fluid> currentFluids) {
            return isValidMap(usedItems, currentItems) && isValidMap(usedFluids, currentFluids);
        }

        private <T> boolean isValidMap(Reference2DoubleMap<T> cached, Reference2DoubleMap<T> current) {
            for (var entry : cached.reference2DoubleEntrySet()) {
                var k = entry.getKey();
                if (Math.abs(entry.getDoubleValue() - current.getOrDefault(k, Double.POSITIVE_INFINITY)) > EPSILON)
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

    private record ComplexityResult(double complexity, @Nullable RecipeNode recipe) {
    }
}