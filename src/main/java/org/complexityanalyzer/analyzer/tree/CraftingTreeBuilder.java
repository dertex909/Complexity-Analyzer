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

package org.complexityanalyzer.analyzer.tree;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.analyzer.DepthAnalyzer;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.CraftingTreeData;
import org.complexityanalyzer.data.CraftingTreeData.*;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;
import org.jetbrains.annotations.Nullable;

public class CraftingTreeBuilder {

    private final AnalysisEngine engine;
    private final DepthAnalyzer depthAnalyzer;
    @Nullable
    private final SourceManager sourceManager;

    public CraftingTreeBuilder(AnalysisEngine engine) {
        this.engine = engine;
        this.depthAnalyzer = engine.getDepthAnalyzer();
        if (this.depthAnalyzer == null) throw new IllegalStateException("DepthAnalyzer not initialized");
        this.sourceManager = engine.getSourceManager();
    }

    public CraftingTreeData build(Item item, DisplayMode mode, int maxDepth) {
        var baseResources = new Reference2DoubleLinkedOpenHashMap<Item>();
        var uniqueItems = new ReferenceOpenHashSet<Item>();
        var stats = new TreeStatistics();

        var root = buildNode(
                item,
                ItemStack.EMPTY,
                1.0,
                0,
                new ReferenceOpenHashSet<>(),
                baseResources,
                mode,
                maxDepth,
                uniqueItems,
                stats
        );

        stats.setUniqueItems(uniqueItems.size());

        return new CraftingTreeData(item, root, baseResources, stats, mode, maxDepth);
    }

    private TreeNode buildNode(
            Item item,
            ItemStack requestedStack,
            double neededAmount,
            int depth,
            ReferenceSet<Item> visitedOnPath,
            Reference2DoubleMap<Item> baseResources,
            DisplayMode displayMode,
            int maxDepth,
            ReferenceSet<Item> uniqueItems,
            TreeStatistics stats
    ) {
        uniqueItems.add(item);
        stats.incrementTotalNodes();

        var complexityResult = engine.getComplexityResult(item);
        if (complexityResult == null) return TreeNode.builder()
                .type(NodeType.NO_DATA)
                .item(item)
                .itemStack(requestedStack)
                .neededAmount(neededAmount)
                .complexity(0)
                .build();

        double complexity = complexityResult.getComplexity();

        if (depth >= maxDepth) {
            calculateBaseResourcesFor(item, neededAmount, visitedOnPath, baseResources, displayMode);
            return TreeNode.builder()
                    .type(NodeType.MAX_DEPTH_REACHED)
                    .item(item)
                    .itemStack(requestedStack)
                    .neededAmount(neededAmount)
                    .complexity(complexity)
                    .build();
        }

        double amountToAdd = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ? Math.ceil(neededAmount) : neededAmount;

        if (!visitedOnPath.add(item)) {
            stats.incrementCycles();
            stats.incrementBaseResources();
            baseResources.merge(item, amountToAdd, Double::sum);

            return TreeNode.builder()
                    .type(NodeType.CYCLE)
                    .item(item)
                    .itemStack(requestedStack)
                    .neededAmount(neededAmount)
                    .complexity(complexity)
                    .build();
        }

        RecipeNode recipe = null;
        var solverResult = engine.getSolverResult();
        if (solverResult != null) recipe = solverResult.getOptimalRecipe(item);

        if (recipe == null) recipe = depthAnalyzer.getRecipeToFollow(item);

        BaseResourceData sourceData = null;
        if (sourceManager != null) sourceData = sourceManager.analyze(item);

        boolean hasBaseSource = (sourceData != null && !isUnobtainable(sourceData));

        if (recipe == null && hasBaseSource) {
            stats.incrementBaseResources();
            baseResources.merge(item, amountToAdd, Double::sum);

            var builder = TreeNode.builder()
                    .type(NodeType.BASE_RESOURCE)
                    .item(item)
                    .itemStack(requestedStack)
                    .neededAmount(neededAmount)
                    .complexity(complexity)
                    .addMetadata("sourceTypeName", sourceData.getSourceType().getDisplayName())
                    .addMetadata("sourceSpecifier", sourceData.getSourceSpecifier());

            visitedOnPath.remove(item);
            return builder.build();
        }

        if (recipe == null || recipe.isBaseRecipe()) {
            var graph = engine.getGraph();
            if (graph != null && graph.hasRecipe(item)) {
                var recipes = graph.getRecipes(item);
                RecipeNode bestCraft = null;
                for (var r : recipes) {
                    if (r.isBaseRecipe()) continue;
                    var cat = r.getCategory();
                    if (cat == RecipeCategory.PRIMARY || cat == RecipeCategory.PROCESSING) {
                        if (bestCraft == null || r.getPriority() < bestCraft.getPriority()) bestCraft = r;
                    }
                }
                if (bestCraft != null) recipe = bestCraft;
            }
        }

        boolean wouldCreateCycle = false;
        if (recipe != null && !recipe.isBaseRecipe()) for (var slot : recipe.getIngredients()) {
            for (var variant : slot.getVariants()) {
                if (visitedOnPath.contains(variant.getItem())) {
                    wouldCreateCycle = true;
                    break;
                }
            }
            if (wouldCreateCycle) break;
        }

        if (recipe == null || recipe.isBaseRecipe() || wouldCreateCycle) {
            stats.incrementBaseResources();
            baseResources.merge(item, amountToAdd, Double::sum);

            var builder = TreeNode.builder()
                    .type(NodeType.BASE_RESOURCE)
                    .item(item)
                    .itemStack(requestedStack)
                    .neededAmount(neededAmount)
                    .complexity(complexity)
                    .addMetadata("wouldCreateCycle", wouldCreateCycle);

            if (sourceManager != null && !wouldCreateCycle && sourceData != null) {
                builder.addMetadata("sourceTypeName", sourceData.getSourceType().getDisplayName());
                builder.addMetadata("sourceSpecifier", sourceData.getSourceSpecifier());
            }

            visitedOnPath.remove(item);
            return builder.build();
        }

        stats.incrementCraftingSteps();

        var registry = engine.getMachineRegistry();
        var machine = (registry != null) ? registry.getMachineForRecipe(recipe.getRecipeType()) : null;
        var machineName = (machine != null) ? machine.getDescription() : Component.translatable("complexityanalyzer.command.tree.crafting_table");

        double craftOperations = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ?
                Math.ceil(neededAmount / recipe.getResultCount()) :
                neededAmount / recipe.getResultCount();

        var nodeBuilder = TreeNode.builder()
                .type(NodeType.CRAFTING)
                .item(item)
                .itemStack(requestedStack)
                .neededAmount(neededAmount)
                .complexity(complexity)
                .recipe(recipe)
                .machineType(machineName);

        var ingredientsForOneCraft = new Reference2ObjectOpenHashMap<Item, IngredientChoice>();
        for (var slot : recipe.getIngredients()) {
            Item bestVariant = null;
            var bestVariantStack = ItemStack.EMPTY;
            double minComplexity = Double.POSITIVE_INFINITY;
            for (var v : slot.getVariants()) {
                double c = engine.getComplexity(v.getItem());
                if (c < minComplexity) {
                    minComplexity = c;
                    bestVariant = v.getItem();
                    bestVariantStack = v.copyWithCount(slot.getCount());
                }
            }
            if (bestVariant != null) {
                var existing = ingredientsForOneCraft.get(bestVariant);
                if (existing == null) {
                    ingredientsForOneCraft.put(bestVariant, new IngredientChoice(bestVariantStack, slot.getCount()));
                } else {
                    existing.count += slot.getCount();
                }
            }
        }

        for (var entry : Reference2ObjectMaps.fastIterable(ingredientsForOneCraft)) {
            var ingredientItem = entry.getKey();
            var choice = entry.getValue();
            int countForOneCraft = choice.count;
            double totalIngredientNeeded = craftOperations * countForOneCraft;

            var childNode = buildNode(ingredientItem, choice.stack, totalIngredientNeeded, depth + 1,
                    visitedOnPath, baseResources, displayMode, maxDepth, uniqueItems, stats);
            nodeBuilder.addItemChild(childNode);
        }

        for (var slot : recipe.getFluidIngredients()) {
            var primaryFluid = slot.getPrimaryFluid();
            if (primaryFluid != null) {
                var fluidName = primaryFluid.getFluidType().getDescription();
                double amount = slot.getAmount() * craftOperations;
                nodeBuilder.addFluidChild(new FluidNode(fluidName, amount));
            }
        }

        visitedOnPath.remove(item);
        return nodeBuilder.build();
    }

    private boolean isUnobtainable(BaseResourceData data) {
        if (data == null) return true;
        if (Double.isInfinite(data.getBaseFactor())) return true;
        return data.getSourceType() == BaseResourceData.ResourceSourceType.UNOBTAINABLE;
    }

    private static class IngredientChoice {
        private final ItemStack stack;
        private int count;

        private IngredientChoice(ItemStack stack, int count) {
            this.stack = stack != null ? stack.copy() : ItemStack.EMPTY;
            this.count = count;
        }
    }

    private void calculateBaseResourcesFor(
            Item item,
            double neededAmount,
            ReferenceSet<Item> visited,
            Reference2DoubleMap<Item> baseResources,
            DisplayMode displayMode
    ) {
        if (!visited.add(item)) return;

        var recipe = depthAnalyzer.getRecipeToFollow(item);

        if (recipe == null || recipe.isBaseRecipe()) {
            double amountToAdd = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ? Math.ceil(neededAmount) : neededAmount;
            baseResources.merge(item, amountToAdd, Double::sum);
            visited.remove(item);
            return;
        }

        double craftOperations;
        if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
            int neededPlayerAmount = (int) Math.ceil(neededAmount);
            craftOperations = Math.ceil((double) neededPlayerAmount / recipe.getResultCount());
        } else {
            craftOperations = neededAmount / recipe.getResultCount();
        }

        for (var slot : recipe.getIngredients()) {
            Item bestVariant = null;
            double minComp = Double.POSITIVE_INFINITY;
            for (var v : slot.getVariants()) {
                double c = engine.getComplexity(v.getItem());
                if (c < minComp) {
                    minComp = c;
                    bestVariant = v.getItem();
                }
            }

            if (bestVariant != null) {
                double totalIngredientNeeded = craftOperations * slot.getCount();
                calculateBaseResourcesFor(bestVariant, totalIngredientNeeded, visited, baseResources, displayMode);
            }
        }
        visited.remove(item);
    }
}
