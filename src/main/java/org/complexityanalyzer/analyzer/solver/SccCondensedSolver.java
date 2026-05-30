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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.analyzer.solver;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.MachineRegistry;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.Arrays;

public final class SccCondensedSolver {

    private static final double EPSILON = 1e-12;
    private static final double CONVERGENCE_THRESHOLD = ComplexityConfig.CONVERGENCE_THRESHOLD.get();
    private static final int MAX_FIXPOINT_ITERATIONS = ComplexityConfig.MAX_ITERATIONS.get();
    private static final byte F_SOURCE = 0;
    private static final byte F_ITEM_RECIPE = 1;
    private static final byte F_FLUID_RECIPE = 2;
    private static final byte F_CHEM_RECIPE = 3;
    private static final byte F_PROTECTED_FLUID = 4;
    private static final byte F_CHEM_BRIDGE = 5;
    private static final byte K_ITEM = 0;
    private static final byte K_FLUID = 1;
    private static final byte K_CHEMICAL = 2;

    private final RecipeGraph graph;
    private final SourceManager sourceManager;
    private final MachineRegistry machineRegistry;

    public SccCondensedSolver(RecipeGraph graph, SourceManager sourceManager, MachineRegistry machineRegistry) {
        this.graph = graph;
        this.sourceManager = sourceManager;
        this.machineRegistry = machineRegistry;
    }

    public SolverResult solve() {
        long startTime = System.currentTimeMillis();
        ComplexityAnalyzer.LOGGER.info("🚀 Starting SCC/DAG solver...");

        var compiled = new CompileBuilder(graph, sourceManager, machineRegistry).build();
        ComplexityAnalyzer.LOGGER.info("📐 IR compiled: {} nodes ({} items, {} fluids, {} chemicals), {} formulas, {} edges",
                compiled.nodeCount, compiled.itemCount, compiled.fluidCount, compiled.chemicalCount,
                compiled.formulaCount, compiled.adjNode.length);

        var solution = solveCompiled(compiled);
        ComplexityAnalyzer.LOGGER.info("🌀 Solved {} components ({} cyclic) in {} fixpoint iterations",
                solution.componentCount, solution.cyclicComponents, solution.fixpointIterations);

        var materialized = materialize(compiled, solution);

        int reclassified = graph.reclassifyRecipesBasedOnComplexity(materialized.optimalComplexities());
        if (reclassified > 0)
            ComplexityAnalyzer.LOGGER.info("🔄 Reclassified {} recipes (costs are category-independent; no re-solve)", reclassified);

        long totalTime = System.currentTimeMillis() - startTime;
        logFinalStatistics(compiled, solution, totalTime);

        return new SolverResult(
                materialized.optimalComplexities(),
                materialized.optimalRecipes(),
                materialized.optimalFluidComplexities(),
                materialized.optimalFluidRecipes(),
                solution.fixpointIterations,
                totalTime,
                true
        );
    }

    private void logFinalStatistics(CompiledModel m, Solution sol, long totalTime) {
        long finiteItems = 0;
        long finiteFluids = 0;
        long finiteChems = 0;
        for (int n = 0; n < m.nodeCount; n++) {
            if (Double.isInfinite(sol.costs[n])) continue;
            switch (m.nodeKind[n]) {
                case K_ITEM -> finiteItems++;
                case K_FLUID -> finiteFluids++;
                case K_CHEMICAL -> finiteChems++;
                default -> {
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        ComplexityAnalyzer.LOGGER.info("🎯 SOLVER RESULTS (single-pass DAG solver)");
        ComplexityAnalyzer.LOGGER.info("════════════════════════════════════════");
        ComplexityAnalyzer.LOGGER.info("⏱️  Time: {}ms", totalTime);
        ComplexityAnalyzer.LOGGER.info("📦 Items: {}/{} finite", finiteItems, m.itemCount);
        ComplexityAnalyzer.LOGGER.info("💧 Fluids: {}/{} finite", finiteFluids, m.fluidCount);
        ComplexityAnalyzer.LOGGER.info("🧪 Chemicals: {}/{} finite", finiteChems, m.chemicalCount);
        ComplexityAnalyzer.LOGGER.info("🌀 Components: {} ({} cyclic)", sol.componentCount, sol.cyclicComponents);
        ComplexityAnalyzer.LOGGER.info("🔁 Fixpoint iterations: {}", sol.fixpointIterations);
    }

    private Solution solveCompiled(CompiledModel m) {
        var tarjan = tarjanScc(m.nodeCount, m.adjStart, m.adjNode);
        int[] componentOf = tarjan.componentOf;
        int componentCount = tarjan.componentCount;

        int[] componentSize = new int[componentCount];
        for (int n = 0; n < m.nodeCount; n++) componentSize[componentOf[n]]++;

        int[] componentFormulaCount = new int[componentCount];
        for (int f = 0; f < m.formulaCount; f++) componentFormulaCount[componentOf[m.formulaTarget[f]]]++;

        int[] componentFormulasStart = new int[componentCount + 1];
        for (int c = 0; c < componentCount; c++)
            componentFormulasStart[c + 1] = componentFormulasStart[c] + componentFormulaCount[c];
        int[] componentFormulas = new int[m.formulaCount];
        {
            int[] cursor = componentFormulasStart.clone();
            for (int f = 0; f < m.formulaCount; f++) {
                int c = componentOf[m.formulaTarget[f]];
                componentFormulas[cursor[c]++] = f;
            }
        }

        var sol = new Solution(m.nodeCount, m.itemCount, m.fluidCount);

        int totalFixpointIterations = 0;
        int cyclicComponents = 0;

        for (int c = componentCount - 1; c >= 0; c--) {
            int fStart = componentFormulasStart[c];
            int fEnd = componentFormulasStart[c + 1];
            if (fStart == fEnd) continue;

            boolean cyclic = componentSize[c] > 1;
            if (cyclic) cyclicComponents++;

            int iterations = 0;
            boolean changed = true;
            while (changed && iterations < MAX_FIXPOINT_ITERATIONS) {
                iterations++;
                changed = false;
                for (int fi = fStart; fi < fEnd; fi++) {
                    int formulaIdx = componentFormulas[fi];
                    double newCost = evalFormula(formulaIdx, m, sol.costs);
                    if (Double.isInfinite(newCost)) continue;

                    int target = m.formulaTarget[formulaIdx];
                    byte fType = m.formulaType[formulaIdx];

                    double oldCost = sol.costs[target];
                    if (significantlyLower(oldCost, newCost)) {
                        sol.costs[target] = newCost;
                        changed = true;
                    }

                    int itemIdx = m.itemIdxByNode[target];
                    if (itemIdx >= 0) if (fType == F_ITEM_RECIPE) {
                        if (newCost < sol.itemBestRecipeCost[itemIdx]) {
                            sol.itemBestRecipeCost[itemIdx] = newCost;
                            sol.itemBestRecipeFormulaIdx[itemIdx] = formulaIdx;
                        }
                    } else if (fType == F_SOURCE) {
                        if (newCost < sol.itemBestSourceCost[itemIdx]) sol.itemBestSourceCost[itemIdx] = newCost;
                    }

                    int fluidIdx = m.fluidIdxByNode[target];
                    if (fluidIdx >= 0) if (fType == F_FLUID_RECIPE || fType == F_PROTECTED_FLUID) {
                        if (newCost < sol.fluidBestRecipeCost[fluidIdx]) {
                            sol.fluidBestRecipeCost[fluidIdx] = newCost;
                            sol.fluidBestRecipeFormulaIdx[fluidIdx] = formulaIdx;
                        }
                    }
                }
            }

            totalFixpointIterations += iterations;
        }

        sol.componentCount = componentCount;
        sol.cyclicComponents = cyclicComponents;
        sol.fixpointIterations = totalFixpointIterations;
        return sol;
    }

    private static boolean significantlyLower(double oldCost, double newCost) {
        if (Double.isInfinite(oldCost)) return !Double.isInfinite(newCost);
        if (Double.isInfinite(newCost)) return false;
        if (newCost >= oldCost) return false;
        double delta = oldCost - newCost;
        if (delta <= CONVERGENCE_THRESHOLD) return false;
        if (oldCost <= EPSILON) return delta > CONVERGENCE_THRESHOLD;
        return delta / oldCost > CONVERGENCE_THRESHOLD;
    }

    private static double evalFormula(int f, CompiledModel m, double[] costs) {
        double sum = 0.0;

        int isStart = m.formulaItemSlotStart[f];
        int isEnd = isStart + m.formulaItemSlotCount[f];
        for (int s = isStart; s < isEnd; s++) {
            int vStart = m.itemSlotVariantStart[s];
            int vEnd = vStart + m.itemSlotVariantCount[s];
            double minCost = Double.POSITIVE_INFINITY;
            for (int v = vStart; v < vEnd; v++) {
                double c = costs[m.itemVariantNode[v]];
                if (c < minCost) minCost = c;
            }
            if (Double.isInfinite(minCost)) return Double.POSITIVE_INFINITY;
            sum += minCost * m.itemSlotAmount[s];
        }

        int fsStart = m.formulaFluidSlotStart[f];
        int fsEnd = fsStart + m.formulaFluidSlotCount[f];
        for (int s = fsStart; s < fsEnd; s++) {
            int vStart = m.fluidSlotVariantStart[s];
            int vEnd = vStart + m.fluidSlotVariantCount[s];
            double minCost = Double.POSITIVE_INFINITY;
            for (int v = vStart; v < vEnd; v++) {
                double c = costs[m.fluidVariantNode[v]];
                if (c < minCost) minCost = c;
            }
            if (Double.isInfinite(minCost)) return Double.POSITIVE_INFINITY;
            sum += minCost * m.fluidSlotAmount[s];
        }

        int cStart = m.formulaChemInputStart[f];
        int cEnd = cStart + m.formulaChemInputCount[f];
        for (int ci = cStart; ci < cEnd; ci++) {
            double c = costs[m.chemInputNode[ci]];
            if (Double.isInfinite(c)) return Double.POSITIVE_INFINITY;
            sum += c * m.chemInputAmount[ci];
        }

        int mStart = m.formulaMachineNode[f];
        int mCount = m.formulaMachineCount[f];
        if (mCount > 0) {
            double minMachineCost = Double.POSITIVE_INFINITY;
            for (int i = mStart; i < mStart + mCount; i++) {
                double c = costs[m.itemVariantNode[i]];
                if (c < minMachineCost) minMachineCost = c;
            }
            if (Double.isInfinite(minMachineCost)) {
                double fb = m.formulaMachineFallback[f];
                if (fb < 0) return Double.POSITIVE_INFINITY;
                minMachineCost = fb;
            }
            sum += minMachineCost * m.formulaMachineMul[f];
        }

        double divisor = m.formulaOutputDivisor[f];
        return m.formulaBaseCost[f] + (sum * m.formulaMultiplier[f]) / divisor;
    }

    private record TarjanResult(int[] componentOf, int componentCount) {
    }

    private static TarjanResult tarjanScc(int n, int[] adjStart, int[] adjNode) {
        int[] index = new int[n];
        int[] lowlink = new int[n];
        boolean[] onStack = new boolean[n];
        int[] sccStack = new int[n];
        int sccTop = 0;
        int[] componentOf = new int[n];
        int[] callStack = new int[n];
        int[] callIter = new int[n];

        Arrays.fill(index, -1);
        Arrays.fill(componentOf, -1);

        int idxCounter = 0;
        int compCounter = 0;

        for (int start = 0; start < n; start++) {
            if (index[start] != -1) continue;

            int callTop = 0;
            callStack[0] = start;
            callIter[start] = adjStart[start];
            index[start] = idxCounter;
            lowlink[start] = idxCounter;
            idxCounter++;
            sccStack[sccTop++] = start;
            onStack[start] = true;

            while (callTop >= 0) {
                int v = callStack[callTop];
                int it = callIter[v];
                int end = adjStart[v + 1];

                if (it < end) {
                    int w = adjNode[it];
                    callIter[v] = it + 1;
                    if (index[w] == -1) {
                        callTop++;
                        callStack[callTop] = w;
                        callIter[w] = adjStart[w];
                        index[w] = idxCounter;
                        lowlink[w] = idxCounter;
                        idxCounter++;
                        sccStack[sccTop++] = w;
                        onStack[w] = true;
                    } else if (onStack[w]) {
                        if (index[w] < lowlink[v]) lowlink[v] = index[w];
                    }
                } else {
                    if (lowlink[v] == index[v]) {
                        int popped;
                        do {
                            popped = sccStack[--sccTop];
                            onStack[popped] = false;
                            componentOf[popped] = compCounter;
                        } while (popped != v);
                        compCounter++;
                    }
                    callTop--;
                    if (callTop >= 0) {
                        int parent = callStack[callTop];
                        if (lowlink[v] < lowlink[parent]) lowlink[parent] = lowlink[v];
                    }
                }
            }
        }

        return new TarjanResult(componentOf, compCounter);
    }

    private record MaterializedResult(
            Reference2DoubleMap<Item> optimalComplexities,
            Reference2ObjectMap<Item, RecipeNode> optimalRecipes,
            Reference2DoubleMap<Fluid> optimalFluidComplexities,
            Reference2ObjectMap<Fluid, RecipeNode> optimalFluidRecipes
    ) {
    }

    private MaterializedResult materialize(CompiledModel m, Solution sol) {
        var itemComplexities = new Reference2DoubleOpenHashMap<Item>(m.itemCount);
        itemComplexities.defaultReturnValue(Double.POSITIVE_INFINITY);
        var optimalRecipes = new Reference2ObjectOpenHashMap<Item, RecipeNode>();

        for (int n = 0; n < m.nodeCount; n++) {
            if (m.nodeKind[n] != K_ITEM) continue;
            if (!m.itemInCorpus[n]) continue;
            var item = m.itemByNode[n];
            if (item == null) continue;

            double cost = sol.costs[n];
            itemComplexities.put(item, cost);

            int itemIdx = m.itemIdxByNode[n];
            if (itemIdx < 0) continue;

            double recipeBest = sol.itemBestRecipeCost[itemIdx];
            double sourceBest = sol.itemBestSourceCost[itemIdx];
            if (Double.isInfinite(recipeBest)) continue;
            if (recipeBest + EPSILON >= sourceBest) continue;

            int formulaIdx = sol.itemBestRecipeFormulaIdx[itemIdx];
            if (formulaIdx < 0) continue;

            RecipeNode recipe = m.formulaRecipe[formulaIdx];
            if (recipe != null) optimalRecipes.put(item, recipe);
        }

        var fluidComplexities = new Reference2DoubleOpenHashMap<Fluid>(m.fluidCount);
        fluidComplexities.defaultReturnValue(Double.POSITIVE_INFINITY);
        var optimalFluidRecipes = new Reference2ObjectOpenHashMap<Fluid, RecipeNode>();

        for (int n = 0; n < m.nodeCount; n++) {
            if (m.nodeKind[n] != K_FLUID) continue;
            var fluid = m.fluidByNode[n];
            if (fluid == null) continue;

            double cost = sol.costs[n];
            fluidComplexities.put(fluid, cost);

            int fluidIdx = m.fluidIdxByNode[n];
            if (fluidIdx < 0) continue;

            double recipeBest = sol.fluidBestRecipeCost[fluidIdx];
            if (Double.isInfinite(recipeBest)) continue;

            int formulaIdx = sol.fluidBestRecipeFormulaIdx[fluidIdx];
            if (formulaIdx < 0) continue;

            RecipeNode recipe = m.formulaRecipe[formulaIdx];
            if (recipe != null) optimalFluidRecipes.put(fluid, recipe);
        }

        return new MaterializedResult(itemComplexities, optimalRecipes, fluidComplexities, optimalFluidRecipes);
    }

    private static final class CompileBuilder {
        private final RecipeGraph graph;
        private final SourceManager sourceManager;
        private final MachineRegistry machineRegistry;
        private final ObjectArrayList<Item> itemByNode = new ObjectArrayList<>();
        private final ObjectArrayList<Fluid> fluidByNode = new ObjectArrayList<>();
        private final ObjectArrayList<ResourceLocation> chemicalByNode = new ObjectArrayList<>();
        private final ByteList nodeKind = new ByteList();
        private final BoolList itemInCorpus = new BoolList();
        private final IntArrayList itemIdxByNode = new IntArrayList();
        private final IntArrayList fluidIdxByNode = new IntArrayList();
        private final Reference2IntOpenHashMap<Item> itemToNode = new Reference2IntOpenHashMap<>();
        private final Reference2IntOpenHashMap<Fluid> fluidToNode = new Reference2IntOpenHashMap<>();
        private final Object2IntOpenHashMap<ResourceLocation> chemicalToNode = new Object2IntOpenHashMap<>();

        private int itemCount = 0;
        private int fluidCount = 0;
        private int chemicalCount = 0;

        private final ByteList formulaType = new ByteList();
        private final IntArrayList formulaTarget = new IntArrayList();
        private final DoubleArrayList formulaBaseCost = new DoubleArrayList();
        private final DoubleArrayList formulaMultiplier = new DoubleArrayList();
        private final DoubleArrayList formulaOutputDivisor = new DoubleArrayList();
        private final IntArrayList formulaItemSlotStart = new IntArrayList();
        private final IntArrayList formulaItemSlotCount = new IntArrayList();
        private final IntArrayList formulaFluidSlotStart = new IntArrayList();
        private final IntArrayList formulaFluidSlotCount = new IntArrayList();
        private final IntArrayList formulaChemInputStart = new IntArrayList();
        private final IntArrayList formulaChemInputCount = new IntArrayList();
        private final IntArrayList formulaMachineNode = new IntArrayList();
        private final IntArrayList formulaMachineCount = new IntArrayList();
        private final DoubleArrayList formulaMachineMul = new DoubleArrayList();
        private final DoubleArrayList formulaMachineFallback = new DoubleArrayList();
        private final ObjectArrayList<RecipeNode> formulaRecipe = new ObjectArrayList<>();
        private final IntArrayList itemSlotVariantStart = new IntArrayList();
        private final IntArrayList itemSlotVariantCount = new IntArrayList();
        private final DoubleArrayList itemSlotAmount = new DoubleArrayList();
        private final IntArrayList itemVariantNode = new IntArrayList();
        private final IntArrayList fluidSlotVariantStart = new IntArrayList();
        private final IntArrayList fluidSlotVariantCount = new IntArrayList();
        private final DoubleArrayList fluidSlotAmount = new DoubleArrayList();
        private final IntArrayList fluidVariantNode = new IntArrayList();
        private final IntArrayList chemInputNode = new IntArrayList();
        private final DoubleArrayList chemInputAmount = new DoubleArrayList();
        private final IntArrayList edgeFrom = new IntArrayList();
        private final IntArrayList edgeTo = new IntArrayList();

        CompileBuilder(RecipeGraph graph, SourceManager sourceManager, MachineRegistry machineRegistry) {
            this.itemToNode.defaultReturnValue(-1);
            this.fluidToNode.defaultReturnValue(-1);
            this.chemicalToNode.defaultReturnValue(-1);
            this.graph = graph;
            this.sourceManager = sourceManager;
            this.machineRegistry = machineRegistry;
        }

        CompiledModel build() {
            allocateNodes();
            compileSourceFormulas();
            compileRecipeFormulas();
            compileProtectedFluidFormulas();
            compileChemicalBridgeFormulas();
            return materialize();
        }

        private int allocateItemNode(Item item) {
            int existing = itemToNode.getInt(item);
            if (existing != -1) return existing;
            int node = nodeKind.size();
            itemToNode.put(item, node);
            itemByNode.add(item);
            fluidByNode.add(null);
            chemicalByNode.add(null);
            nodeKind.add(K_ITEM);
            itemInCorpus.addFalse();
            itemIdxByNode.add(itemCount);
            fluidIdxByNode.add(-1);
            itemCount++;
            return node;
        }

        private int allocateFluidNode(Fluid fluid) {
            int existing = fluidToNode.getInt(fluid);
            if (existing != -1) return existing;
            int node = nodeKind.size();
            fluidToNode.put(fluid, node);
            itemByNode.add(null);
            fluidByNode.add(fluid);
            chemicalByNode.add(null);
            nodeKind.add(K_FLUID);
            itemInCorpus.addFalse();
            itemIdxByNode.add(-1);
            fluidIdxByNode.add(fluidCount);
            fluidCount++;
            return node;
        }

        private int allocateChemicalNode(ResourceLocation chemId) {
            int existing = chemicalToNode.getInt(chemId);
            if (existing != -1) return existing;
            int node = nodeKind.size();
            chemicalToNode.put(chemId, node);
            itemByNode.add(null);
            fluidByNode.add(null);
            chemicalByNode.add(chemId);
            nodeKind.add(K_CHEMICAL);
            itemInCorpus.addFalse();
            itemIdxByNode.add(-1);
            fluidIdxByNode.add(-1);
            chemicalCount++;
            return node;
        }

        private void allocateNodes() {
            var corpus = graph.getCorpus();
            for (var item : corpus) {
                int node = allocateItemNode(item);
                itemInCorpus.setTrue(node);
            }

            for (var fluid : graph.getAllUsedFluids()) allocateFluidNode(fluid);

            allocateFluidNode(Fluids.WATER);
            allocateFluidNode(Fluids.LAVA);

            for (var recipe : graph.getAllRecipes()) {
                var resultItem = recipe.getResultItem();
                if (resultItem != null) allocateItemNode(resultItem);

                for (var slot : recipe.getIngredients()) {
                    for (var variant : slot.getVariants()) if (variant != null) allocateItemNode(variant);
                }
                for (var slot : recipe.getFluidIngredients()) {
                    for (var variant : slot.getFluidVariants()) {
                        var normalized = normalizeFluid(variant);
                        if (normalized != Fluids.EMPTY) allocateFluidNode(normalized);
                    }
                }
                for (var stack : recipe.getFluidOutputs()) {
                    var normalized = normalizeFluid(stack.getFluid());
                    if (normalized != Fluids.EMPTY) allocateFluidNode(normalized);
                }

                if (machineRegistry != null) {
                    var machineItems = machineRegistry.getMachinesForRecipe(recipe.getRecipeType());
                    if (machineItems != null) for (Item machineItem : machineItems) allocateItemNode(machineItem);
                }

                var chemOutputs = recipe.getChemicalOutputs();
                for (var chem : chemOutputs) if (chem != null && chem.id() != null) allocateChemicalNode(chem.id());

                for (var chem : recipe.getChemicalIngredients()) {
                    if (chem != null && chem.id() != null) allocateChemicalNode(chem.id());
                }
            }

            for (var item : GameRegistryManager.getAllItems()) {
                var sources = sourceManager.findAllSources(item);
                if (sources.isEmpty()) continue;

                int node = allocateItemNode(item);
                itemInCorpus.setTrue(node);

                for (var data : sources) {
                    if (data == null) continue;
                    var sourceItems = data.getSourceItems();
                    if (sourceItems.isEmpty()) continue;
                    for (var entry : sourceItems.reference2DoubleEntrySet()) {
                        var dep = entry.getKey();
                        if (dep != null) allocateItemNode(dep);
                    }
                }
            }

            for (var entry : chemicalToNode.object2IntEntrySet()) {
                var id = entry.getKey();
                var fluid = GameRegistryManager.getFluid(id);
                if (fluid != null && fluid != Fluids.EMPTY && !isProtectedFluid(fluid)) allocateFluidNode(fluid);
            }
        }

        private void compileSourceFormulas() {
            for (var item : GameRegistryManager.getAllItems()) {
                int targetNode = itemToNode.getInt(item);
                if (targetNode == -1) continue;

                var sources = sourceManager.findAllSources(item);
                if (sources.isEmpty()) continue;

                for (var data : sources) {
                    if (data == null) continue;
                    double base = data.getBaseFactor();
                    if (Double.isNaN(base) || Double.isInfinite(base)) continue;

                    int itemSlotStart = itemSlotVariantStart.size();
                    int itemSlotCnt = 0;
                    boolean depsOk = true;

                    var sourceItems = data.getSourceItems();
                    for (var entry : sourceItems.reference2DoubleEntrySet()) {
                        var dep = entry.getKey();
                        double amount = entry.getDoubleValue();
                        if (dep == null || amount == 0.0) continue;
                        if (dep == item) continue;
                        int depNode = itemToNode.getInt(dep);
                        if (depNode == -1) depNode = allocateItemNode(dep);
                        addItemSlotSingle(depNode, amount);
                        itemSlotCnt++;
                    }

                    if (!depsOk) {
                        truncateItemSlots(itemSlotStart);
                        continue;
                    }

                    int formulaId = emitFormulaShell(F_SOURCE, targetNode, base, 1.0, 1.0,
                            itemSlotStart, itemSlotCnt, fluidSlotVariantStart.size(), 0, chemInputNode.size(),
                            0, -1, 0, 0.0, -1.0, null);
                    addEdgesForFormula(formulaId);
                }
            }
        }

        private void compileRecipeFormulas() {
            double fluidNorm = ComplexityConfig.getFluidNormalizationFactor();
            double machineTax = ComplexityConfig.getMachineTaxMultiplier();
            double machineFallback = ComplexityConfig.getMachineBaseComplexity();

            for (var recipe : graph.getAllRecipes()) {
                double multiplier = recipe.getRecipeMultiplier();
                if (Double.isInfinite(multiplier) || Double.isNaN(multiplier)) continue;

                int machineNode = -1;
                int machineCount = 0;
                double machineMul = 0.0;
                boolean zeroCostMachine = isZeroCostRecipeType(recipe.getRecipeType());
                if (!zeroCostMachine && machineRegistry != null) {
                    var machineItems = machineRegistry.getMachinesForRecipe(recipe.getRecipeType());
                    if (machineItems != null) {
                        int start = itemVariantNode.size();
                        for (var machineItem : machineItems) {
                            int candidateNode = itemToNode.getInt(machineItem);
                            if (candidateNode != -1 && itemInCorpus.getBoolean(candidateNode)) {
                                itemVariantNode.add(candidateNode);
                                machineCount++;
                            }
                        }
                        if (machineCount > 0) {
                            machineNode = start;
                            machineMul = machineTax;
                        }
                    }
                }

                var resultItem = recipe.getResultItem();
                int itemTarget = (resultItem != null) ? itemToNode.getInt(resultItem) : -1;
                int resultCount = recipe.getResultCount();
                boolean validItemRecipe = itemTarget != -1 && resultCount > 0;
                int itemSlotsStart = itemSlotVariantStart.size();
                int fluidSlotsStart = fluidSlotVariantStart.size();
                int itemSlotsCount = 0;
                int fluidSlotsCount = 0;
                boolean inputsValid = true;

                for (var slot : recipe.getIngredients()) {
                    if (appendItemSlot(slot.getVariants(), slot.getCount())) {
                        inputsValid = false;
                        break;
                    }
                    itemSlotsCount++;
                }
                if (inputsValid) for (var slot : recipe.getFluidIngredients()) {
                    double amount = (slot.getAmount() / 1000.0) * fluidNorm;
                    if (appendFluidSlot(slot.getFluidVariants(), amount)) {
                        inputsValid = false;
                        break;
                    }
                    fluidSlotsCount++;
                }

                if (validItemRecipe && inputsValid) {
                    int formulaId = emitFormulaShell(F_ITEM_RECIPE, itemTarget, recipe.getPriority() + ComplexityConfig.BASE_COMPLEXITY.get(), multiplier, resultCount,
                            itemSlotsStart, itemSlotsCount, fluidSlotsStart, fluidSlotsCount, chemInputNode.size(), 0,
                            machineNode, machineCount, machineMul, -1.0, recipe);
                    addEdgesForFormula(formulaId);
                } else {
                    truncateItemSlots(itemSlotsStart);
                    truncateFluidSlots(fluidSlotsStart);
                }

                if (!recipe.getFluidOutputs().isEmpty()) {
                    Reference2DoubleOpenHashMap<Fluid> grouped = new Reference2DoubleOpenHashMap<>();
                    for (var stack : recipe.getFluidOutputs()) {
                        var normalized = normalizeFluid(stack.getFluid());
                        if (normalized == Fluids.EMPTY || isProtectedFluid(normalized)) continue;
                        grouped.addTo(normalized, stack.getAmount());
                    }

                    if (!grouped.isEmpty()) {
                        int sharedItemSlotStart = itemSlotVariantStart.size();
                        int sharedItemSlotCount = 0;
                        int sharedFluidSlotStart = fluidSlotVariantStart.size();
                        int sharedFluidSlotCount = 0;
                        boolean sharedValid = true;

                        for (var slot : recipe.getIngredients()) {
                            if (appendItemSlot(slot.getVariants(), slot.getCount())) {
                                sharedValid = false;
                                break;
                            }
                            sharedItemSlotCount++;
                        }
                        if (sharedValid) for (var slot : recipe.getFluidIngredients()) {
                            double amount = (slot.getAmount() / 1000.0) * fluidNorm;
                            if (appendFluidSlot(slot.getFluidVariants(), amount)) {
                                sharedValid = false;
                                break;
                            }
                            sharedFluidSlotCount++;
                        }

                        if (sharedValid) {
                            for (var entry : grouped.reference2DoubleEntrySet()) {
                                var fluid = entry.getKey();
                                int fluidNode = fluidToNode.getInt(fluid);
                                if (fluidNode == -1) fluidNode = allocateFluidNode(fluid);
                                double outputAmount = entry.getDoubleValue();
                                if (outputAmount <= 0) outputAmount = 1000.0;
                                double outputBuckets = outputAmount / 1000.0;
                                int formulaId = emitFormulaShell(F_FLUID_RECIPE, fluidNode, recipe.getPriority() + ComplexityConfig.BASE_COMPLEXITY.get(), multiplier, outputBuckets,
                                        sharedItemSlotStart, sharedItemSlotCount,
                                        sharedFluidSlotStart, sharedFluidSlotCount,
                                        chemInputNode.size(), 0,
                                        machineNode, machineCount, machineMul, machineCount > 0 ? machineFallback : -1.0,
                                        null);
                                addEdgesForFormula(formulaId);
                            }
                        } else {
                            truncateItemSlots(sharedItemSlotStart);
                            truncateFluidSlots(sharedFluidSlotStart);
                        }
                    }
                }

                var chemOutputs = recipe.getChemicalOutputs();
                if (!chemOutputs.isEmpty()) {
                    var structured = recipe.getChemicalIngredients();

                    int sharedItemSlotStart = itemSlotVariantStart.size();
                    int sharedItemSlotCount = 0;
                    int sharedFluidSlotStart = fluidSlotVariantStart.size();
                    int sharedFluidSlotCount = 0;
                    int sharedChemStart = chemInputNode.size();
                    int sharedChemCount = 0;
                    boolean sharedValid = true;

                    for (var slot : recipe.getIngredients()) {
                        if (appendItemSlot(slot.getVariants(), slot.getCount())) {
                            sharedValid = false;
                            break;
                        }
                        sharedItemSlotCount++;
                    }
                    if (sharedValid) for (var slot : recipe.getFluidIngredients()) {
                        double amount = slot.getAmount() / 1000.0;
                        if (appendFluidSlot(slot.getFluidVariants(), amount)) {
                            sharedValid = false;
                            break;
                        }
                        sharedFluidSlotCount++;
                    }
                    for (var chem : structured) {
                        if (chem == null || chem.id() == null) continue;
                        int chemNode = chemicalToNode.getInt(chem.id());
                        if (chemNode == -1) chemNode = allocateChemicalNode(chem.id());
                        chemInputNode.add(chemNode);
                        chemInputAmount.add(chem.amount() / 1000.0);
                        sharedChemCount++;
                    }

                    if (sharedValid) {
                        for (var output : chemOutputs) {
                            if (output == null || output.id() == null) continue;
                            int chemNode = chemicalToNode.getInt(output.id());
                            if (chemNode == -1) chemNode = allocateChemicalNode(output.id());
                            double outputBuckets = output.amount() / 1000.0;
                            if (outputBuckets <= 0) continue;
                            int formulaId = emitFormulaShell(F_CHEM_RECIPE, chemNode, recipe.getPriority() + ComplexityConfig.BASE_COMPLEXITY.get(), multiplier, outputBuckets,
                                    sharedItemSlotStart, sharedItemSlotCount,
                                    sharedFluidSlotStart, sharedFluidSlotCount,
                                    sharedChemStart, sharedChemCount,
                                    machineNode, machineCount, machineMul, machineCount > 0 ? machineFallback : -1.0,
                                    null);
                            addEdgesForFormula(formulaId);
                        }
                    } else {
                        truncateItemSlots(sharedItemSlotStart);
                        truncateFluidSlots(sharedFluidSlotStart);
                        truncateChemInputs(sharedChemStart);
                    }
                }
            }
        }

        private void compileProtectedFluidFormulas() {
            int waterNode = fluidToNode.getInt(Fluids.WATER);
            int lavaNode = fluidToNode.getInt(Fluids.LAVA);
            if (waterNode != -1) emitProtectedFluidFormula(waterNode);
            if (lavaNode != -1) emitProtectedFluidFormula(lavaNode);
        }

        private void compileChemicalBridgeFormulas() {
            for (var entry : chemicalToNode.object2IntEntrySet()) {
                var id = entry.getKey();
                int chemNode = entry.getIntValue();
                var fluid = GameRegistryManager.getFluid(id);
                if (fluid == null || fluid == Fluids.EMPTY) continue;
                if (isProtectedFluid(fluid)) continue;
                int fluidNode = fluidToNode.getInt(fluid);
                if (fluidNode == -1) continue;

                int chemStart = chemInputNode.size();
                chemInputNode.add(chemNode);
                chemInputAmount.add(1.0);

                int formulaId = emitFormulaShell(F_CHEM_BRIDGE, fluidNode, 0.0, 1.0, 1.0,
                        itemSlotVariantStart.size(), 0, fluidSlotVariantStart.size(), 0,
                        chemStart, 1, -1, 0, 0.0, -1.0, null);
                addEdgesForFormula(formulaId);
            }
        }

        private void emitProtectedFluidFormula(int target) {
            emitFormulaShell(F_PROTECTED_FLUID, target, 1.0, 1.0, 1.0, itemSlotVariantStart.size(),
                    0, fluidSlotVariantStart.size(), 0, chemInputNode.size(), 0,
                    -1, 0, 0.0, -1.0, null);
        }

        private int emitFormulaShell(byte type, int target, double base, double multiplier, double divisor,
                                     int itemSlotStart, int itemSlotCount, int fluidSlotStart, int fluidSlotCount,
                                     int chemStart, int chemCount, int machineNode, int machineCount, double machineMul,
                                     double machineFallback, RecipeNode recipe) {
            int formulaId = formulaType.size();
            formulaType.add(type);
            formulaTarget.add(target);
            formulaBaseCost.add(base);
            formulaMultiplier.add(multiplier);
            formulaOutputDivisor.add(divisor);
            formulaItemSlotStart.add(itemSlotStart);
            formulaItemSlotCount.add(itemSlotCount);
            formulaFluidSlotStart.add(fluidSlotStart);
            formulaFluidSlotCount.add(fluidSlotCount);
            formulaChemInputStart.add(chemStart);
            formulaChemInputCount.add(chemCount);
            formulaMachineNode.add(machineNode);
            formulaMachineCount.add(machineCount);
            formulaMachineMul.add(machineMul);
            formulaMachineFallback.add(machineFallback);
            formulaRecipe.add(recipe);
            return formulaId;
        }

        private boolean appendItemSlot(ObjectList<Item> variants, double amount) {
            if (variants.isEmpty()) return true;
            int variantStart = itemVariantNode.size();
            int added = 0;
            ReferenceOpenHashSet<Item> seen = null;
            for (var v : variants) {
                if (v == null) continue;
                if (seen == null) seen = new ReferenceOpenHashSet<>(variants.size());
                if (!seen.add(v)) continue;
                int node = itemToNode.getInt(v);
                if (node == -1) node = allocateItemNode(v);
                itemVariantNode.add(node);
                added++;
            }
            if (added == 0) return true;
            itemSlotVariantStart.add(variantStart);
            itemSlotVariantCount.add(added);
            itemSlotAmount.add(amount);
            return false;
        }

        private void addItemSlotSingle(int depNode, double amount) {
            int variantStart = itemVariantNode.size();
            itemVariantNode.add(depNode);
            itemSlotVariantStart.add(variantStart);
            itemSlotVariantCount.add(1);
            itemSlotAmount.add(amount);
        }

        private boolean appendFluidSlot(ObjectList<Fluid> variants, double amount) {
            if (variants.isEmpty()) return true;
            int variantStart = fluidVariantNode.size();
            int added = 0;
            ReferenceOpenHashSet<Fluid> seen = null;
            for (var v : variants) {
                if (v == null) continue;
                var normalized = normalizeFluid(v);
                if (normalized == Fluids.EMPTY) continue;
                if (seen == null) seen = new ReferenceOpenHashSet<>(variants.size());
                if (!seen.add(normalized)) continue;
                int node = fluidToNode.getInt(normalized);
                if (node == -1) node = allocateFluidNode(normalized);
                fluidVariantNode.add(node);
                added++;
            }
            if (added == 0) return true;
            fluidSlotVariantStart.add(variantStart);
            fluidSlotVariantCount.add(added);
            fluidSlotAmount.add(amount);
            return false;
        }

        private void truncateItemSlots(int slotStartInclusive) {
            int variantTrim = (slotStartInclusive < itemSlotVariantStart.size())
                    ? itemSlotVariantStart.getInt(slotStartInclusive) : itemVariantNode.size();
            while (itemSlotVariantStart.size() > slotStartInclusive) {
                int last = itemSlotVariantStart.size() - 1;
                itemSlotVariantStart.removeInt(last);
                itemSlotVariantCount.removeInt(last);
                itemSlotAmount.removeDouble(last);
            }
            while (itemVariantNode.size() > variantTrim) {
                itemVariantNode.removeInt(itemVariantNode.size() - 1);
            }
        }

        private void truncateFluidSlots(int slotStartInclusive) {
            int variantTrim = (slotStartInclusive < fluidSlotVariantStart.size())
                    ? fluidSlotVariantStart.getInt(slotStartInclusive) : fluidVariantNode.size();
            while (fluidSlotVariantStart.size() > slotStartInclusive) {
                int last = fluidSlotVariantStart.size() - 1;
                fluidSlotVariantStart.removeInt(last);
                fluidSlotVariantCount.removeInt(last);
                fluidSlotAmount.removeDouble(last);
            }
            while (fluidVariantNode.size() > variantTrim) fluidVariantNode.removeInt(fluidVariantNode.size() - 1);
        }

        private void truncateChemInputs(int chemStartInclusive) {
            while (chemInputNode.size() > chemStartInclusive) {
                int last = chemInputNode.size() - 1;
                chemInputNode.removeInt(last);
                chemInputAmount.removeDouble(last);
            }
        }

        private void addEdgesForFormula(int formulaId) {
            int target = formulaTarget.getInt(formulaId);

            int isStart = formulaItemSlotStart.getInt(formulaId);
            int isEnd = isStart + formulaItemSlotCount.getInt(formulaId);
            for (int s = isStart; s < isEnd; s++) {
                int vStart = itemSlotVariantStart.getInt(s);
                int vEnd = vStart + itemSlotVariantCount.getInt(s);
                for (int v = vStart; v < vEnd; v++) {
                    edgeFrom.add(itemVariantNode.getInt(v));
                    edgeTo.add(target);
                }
            }

            int fsStart = formulaFluidSlotStart.getInt(formulaId);
            int fsEnd = fsStart + formulaFluidSlotCount.getInt(formulaId);
            for (int s = fsStart; s < fsEnd; s++) {
                int vStart = fluidSlotVariantStart.getInt(s);
                int vEnd = vStart + fluidSlotVariantCount.getInt(s);
                for (int v = vStart; v < vEnd; v++) {
                    edgeFrom.add(fluidVariantNode.getInt(v));
                    edgeTo.add(target);
                }
            }

            int chemStart = formulaChemInputStart.getInt(formulaId);
            int chemEnd = chemStart + formulaChemInputCount.getInt(formulaId);
            for (int c = chemStart; c < chemEnd; c++) {
                edgeFrom.add(chemInputNode.getInt(c));
                edgeTo.add(target);
            }

            int mStart = formulaMachineNode.getInt(formulaId);
            int mCount = formulaMachineCount.getInt(formulaId);
            for (int i = mStart; i < mStart + mCount; i++) {
                edgeFrom.add(itemVariantNode.getInt(i));
                edgeTo.add(target);
            }
        }

        private CompiledModel materialize() {
            int n = nodeKind.size();

            int[] adjStart = new int[n + 1];
            int edgeCount = edgeFrom.size();
            for (int e = 0; e < edgeCount; e++) adjStart[edgeFrom.getInt(e) + 1]++;
            for (int i = 0; i < n; i++) adjStart[i + 1] += adjStart[i];
            int[] adjNode = new int[edgeCount];
            int[] cursor = Arrays.copyOf(adjStart, n);
            for (int e = 0; e < edgeCount; e++) {
                int src = edgeFrom.getInt(e);
                adjNode[cursor[src]++] = edgeTo.getInt(e);
            }

            CompiledModel m = new CompiledModel();
            m.nodeCount = n;
            m.itemCount = itemCount;
            m.fluidCount = fluidCount;
            m.chemicalCount = chemicalCount;
            m.nodeKind = nodeKind.toArray();
            m.itemByNode = itemByNode.toArray(new Item[0]);
            m.fluidByNode = fluidByNode.toArray(new Fluid[0]);
            m.chemicalByNode = chemicalByNode.toArray(new ResourceLocation[0]);
            m.itemInCorpus = itemInCorpus.toArray();
            m.itemIdxByNode = itemIdxByNode.toIntArray();
            m.fluidIdxByNode = fluidIdxByNode.toIntArray();

            m.formulaCount = formulaType.size();
            m.formulaType = formulaType.toArray();
            m.formulaTarget = formulaTarget.toIntArray();
            m.formulaBaseCost = formulaBaseCost.toDoubleArray();
            m.formulaMultiplier = formulaMultiplier.toDoubleArray();
            m.formulaOutputDivisor = formulaOutputDivisor.toDoubleArray();
            m.formulaItemSlotStart = formulaItemSlotStart.toIntArray();
            m.formulaItemSlotCount = formulaItemSlotCount.toIntArray();
            m.formulaFluidSlotStart = formulaFluidSlotStart.toIntArray();
            m.formulaFluidSlotCount = formulaFluidSlotCount.toIntArray();
            m.formulaChemInputStart = formulaChemInputStart.toIntArray();
            m.formulaChemInputCount = formulaChemInputCount.toIntArray();
            m.formulaMachineNode = formulaMachineNode.toIntArray();
            m.formulaMachineCount = formulaMachineCount.toIntArray();
            m.formulaMachineMul = formulaMachineMul.toDoubleArray();
            m.formulaMachineFallback = formulaMachineFallback.toDoubleArray();
            m.formulaRecipe = formulaRecipe.toArray(new RecipeNode[0]);

            m.itemSlotVariantStart = itemSlotVariantStart.toIntArray();
            m.itemSlotVariantCount = itemSlotVariantCount.toIntArray();
            m.itemSlotAmount = itemSlotAmount.toDoubleArray();
            m.itemVariantNode = itemVariantNode.toIntArray();

            m.fluidSlotVariantStart = fluidSlotVariantStart.toIntArray();
            m.fluidSlotVariantCount = fluidSlotVariantCount.toIntArray();
            m.fluidSlotAmount = fluidSlotAmount.toDoubleArray();
            m.fluidVariantNode = fluidVariantNode.toIntArray();

            m.chemInputNode = chemInputNode.toIntArray();
            m.chemInputAmount = chemInputAmount.toDoubleArray();

            m.adjStart = adjStart;
            m.adjNode = adjNode;
            return m;
        }
    }

    private static Fluid normalizeFluid(Fluid fluid) {
        if (fluid == null) return Fluids.EMPTY;
        var id = GameRegistryManager.getFluidId(fluid);
        if (id == null) return fluid;
        String fluidName = id.toString();
        if (!fluidName.contains("flowing_")) return fluid;
        var staticId = ResourceLocation.parse(fluidName.replace("flowing_", ""));
        var staticFluid = GameRegistryManager.getFluid(staticId);
        if (staticFluid == null || staticFluid == Fluids.EMPTY) return fluid;
        return staticFluid;
    }

    private static boolean isProtectedFluid(Fluid fluid) {
        return fluid == Fluids.WATER || fluid == Fluids.LAVA;
    }

    private static boolean isZeroCostRecipeType(RecipeType<?> recipeType) {
        if (recipeType == null) return false;
        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
        if (typeId == null) return false;
        return typeId.equals(GameRegistryManager.getRecipeTypeId(RecipeType.CRAFTING));
    }

    private static final class CompiledModel {
        int nodeCount;
        int itemCount;
        int fluidCount;
        int chemicalCount;
        byte[] nodeKind;
        Item[] itemByNode;
        Fluid[] fluidByNode;
        ResourceLocation[] chemicalByNode;
        boolean[] itemInCorpus;
        int[] itemIdxByNode;
        int[] fluidIdxByNode;
        int formulaCount;
        byte[] formulaType;
        int[] formulaTarget;
        double[] formulaBaseCost;
        double[] formulaMultiplier;
        double[] formulaOutputDivisor;
        int[] formulaItemSlotStart;
        int[] formulaItemSlotCount;
        int[] formulaFluidSlotStart;
        int[] formulaFluidSlotCount;
        int[] formulaChemInputStart;
        int[] formulaChemInputCount;
        int[] formulaMachineNode;
        int[] formulaMachineCount;
        double[] formulaMachineMul;
        double[] formulaMachineFallback;
        RecipeNode[] formulaRecipe;
        int[] itemSlotVariantStart;
        int[] itemSlotVariantCount;
        double[] itemSlotAmount;
        int[] itemVariantNode;
        int[] fluidSlotVariantStart;
        int[] fluidSlotVariantCount;
        double[] fluidSlotAmount;
        int[] fluidVariantNode;
        int[] chemInputNode;
        double[] chemInputAmount;
        int[] adjStart;
        int[] adjNode;
    }

    private static final class Solution {
        final double[] costs;
        final double[] itemBestRecipeCost;
        final double[] itemBestSourceCost;
        final int[] itemBestRecipeFormulaIdx;
        final double[] fluidBestRecipeCost;
        final int[] fluidBestRecipeFormulaIdx;
        int componentCount;
        int cyclicComponents;
        int fixpointIterations;

        Solution(int nodeCount, int itemCount, int fluidCount) {
            this.costs = new double[nodeCount];
            Arrays.fill(this.costs, Double.POSITIVE_INFINITY);
            this.itemBestRecipeCost = new double[itemCount];
            Arrays.fill(this.itemBestRecipeCost, Double.POSITIVE_INFINITY);
            this.itemBestSourceCost = new double[itemCount];
            Arrays.fill(this.itemBestSourceCost, Double.POSITIVE_INFINITY);
            this.itemBestRecipeFormulaIdx = new int[itemCount];
            Arrays.fill(this.itemBestRecipeFormulaIdx, -1);
            this.fluidBestRecipeCost = new double[fluidCount];
            Arrays.fill(this.fluidBestRecipeCost, Double.POSITIVE_INFINITY);
            this.fluidBestRecipeFormulaIdx = new int[fluidCount];
            Arrays.fill(this.fluidBestRecipeFormulaIdx, -1);
        }
    }

    private static final class ByteList {
        private byte[] data = new byte[64];
        private int size = 0;

        int size() {
            return size;
        }

        void add(byte v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }

        byte[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static final class BoolList {
        private boolean[] data = new boolean[64];
        private int size = 0;

        void addFalse() {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = false;
        }

        boolean getBoolean(int idx) {
            return data[idx];
        }

        void setTrue(int idx) {
            data[idx] = true;
        }

        boolean[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}