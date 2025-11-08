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

package org.complexityanalyzer.analyzer.tree;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.DepthAnalyzer;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.CraftingTreeData;
import org.complexityanalyzer.data.CraftingTreeData.*;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.graph.*;

import java.util.*;

/**
 * Строитель дерева крафта - содержит только логику анализа.
 * Не зависит от UI, команд или способа отображения.
 */
public class CraftingTreeBuilder {

    private final AnalysisEngine engine;
    private final DepthAnalyzer depthAnalyzer;
    private final SourceManager sourceManager;

    public CraftingTreeBuilder(AnalysisEngine engine) {
        this.engine = engine;
        this.depthAnalyzer = engine.getDepthAnalyzer().orElseThrow(
                () -> new IllegalStateException("DepthAnalyzer not initialized"));
        this.sourceManager = engine.getSourceManager().orElse(null);
    }

    /**
     * Строит дерево крафта для указанного предмета
     */
    public CraftingTreeData build(Item item, DisplayMode mode, int maxDepth) {
        Map<Item, Double> baseResources = new LinkedHashMap<>();
        Set<Item> uniqueItems = new HashSet<>();
        TreeStatistics stats = new TreeStatistics();

        double initialAmount = (mode == DisplayMode.PLAYER_INSTRUCTION) ? Math.ceil(1.0) : 1.0;

        TreeNode root = buildNode(
                item,
                initialAmount,
                0,
                new HashSet<>(),
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
            double neededAmount,
            int depth,
            Set<Item> visitedOnPath,
            Map<Item, Double> baseResources,
            DisplayMode displayMode,
            int maxDepth,
            Set<Item> uniqueItems,
            TreeStatistics stats
    ) {
        uniqueItems.add(item);
        stats.incrementTotalNodes();

        Optional<ItemComplexity> complexityOpt = engine.getComplexityResult(item);
        if (complexityOpt.isEmpty()) {
            return TreeNode.builder()
                    .type(NodeType.NO_DATA)
                    .item(item)
                    .neededAmount(neededAmount)
                    .complexity(0)
                    .build();
        }

        ItemComplexity complexityData = complexityOpt.get();
        double complexity = complexityData.getComplexity();

        // Проверка максимальной глубины
        if (depth >= maxDepth) {
            calculateBaseResourcesFor(item, neededAmount, new HashSet<>(visitedOnPath),
                    baseResources, displayMode);
            return TreeNode.builder()
                    .type(NodeType.MAX_DEPTH_REACHED)
                    .item(item)
                    .neededAmount(neededAmount)
                    .complexity(complexity)
                    .build();
        }

        double amountToAdd = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ?
                Math.ceil(neededAmount) : neededAmount;

        // Проверка циклов
        if (!visitedOnPath.add(item)) {
            stats.incrementCycles();
            stats.incrementBaseResources();
            baseResources.merge(item, amountToAdd, Double::sum);

            return TreeNode.builder()
                    .type(NodeType.CYCLE)
                    .item(item)
                    .neededAmount(neededAmount)
                    .complexity(complexity)
                    .build();
        }

        Optional<RecipeNode> recipeOpt = engine.getSolverResult()
                .flatMap(result -> result.getOptimalRecipe(item))
                .or(() -> depthAnalyzer.getRecipeToFollow(item));

        boolean wouldCreateCycle = false;
        if (recipeOpt.isPresent() && !recipeOpt.get().isBaseRecipe()) {
            RecipeNode recipe = recipeOpt.get();
            for (IngredientSlot slot : recipe.getIngredients()) {
                if (slot.getVariants().stream().anyMatch(visitedOnPath::contains)) {
                    wouldCreateCycle = true;
                    break;
                }
            }
        }

        // Базовый ресурс
        if (recipeOpt.isEmpty() || recipeOpt.get().isBaseRecipe() || wouldCreateCycle) {
            stats.incrementBaseResources();
            baseResources.merge(item, amountToAdd, Double::sum);

            TreeNode.Builder builder = TreeNode.builder()
                    .type(NodeType.BASE_RESOURCE)
                    .item(item)
                    .neededAmount(neededAmount)
                    .complexity(complexity)
                    .addMetadata("wouldCreateCycle", wouldCreateCycle);

            if (sourceManager != null && !wouldCreateCycle) {
                Optional<BaseResourceData> sourceData = sourceManager.analyze(item);
                sourceData.ifPresent(data -> {
                    builder.addMetadata("sourceTypeName", data.getSourceType().getDisplayName());
                    builder.addMetadata("sourceSpecifier", data.getSourceSpecifier());
                });
            }

            visitedOnPath.remove(item);
            return builder.build();
        }

        // Узел крафта
        stats.incrementCraftingSteps();

        RecipeNode recipe = recipeOpt.get();
        String machineName = engine.getMachineRegistry()
                .flatMap(registry -> registry.getMachineForRecipe(recipe.getRecipeType()))
                .map(m -> m.getDescription().getString())
                .orElse("Crafting Table");

        double craftOperations = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ?
                Math.ceil(neededAmount / recipe.getResultCount()) :
                neededAmount / recipe.getResultCount();

        TreeNode.Builder nodeBuilder = TreeNode.builder()
                .type(NodeType.CRAFTING)
                .item(item)
                .neededAmount(neededAmount)
                .complexity(complexity)
                .recipe(recipe)
                .machineType(machineName);

        // Ингредиенты-предметы
        Map<Item, Integer> ingredientsForOneCraft = new LinkedHashMap<>();
        for (IngredientSlot slot : recipe.getIngredients()) {
            slot.getVariants().stream()
                    .min(Comparator.comparingDouble(engine::getComplexity))
                    .ifPresent(bestVariant ->
                            ingredientsForOneCraft.merge(bestVariant, slot.getCount(), Integer::sum));
        }

        for (Map.Entry<Item, Integer> entry : ingredientsForOneCraft.entrySet()) {
            Item ingredientItem = entry.getKey();
            int countForOneCraft = entry.getValue();
            double totalIngredientNeeded = craftOperations * countForOneCraft;

            TreeNode childNode = buildNode(ingredientItem, totalIngredientNeeded, depth + 1,
                    visitedOnPath, baseResources, displayMode, maxDepth, uniqueItems, stats);
            nodeBuilder.addItemChild(childNode);
        }

        // Ингредиенты-жидкости
        for (FluidIngredientSlot slot : recipe.getFluidIngredients()) {
            var primaryFluid = slot.getPrimaryFluid();
            if (primaryFluid != null) {
                String fluidName = BuiltInRegistries.FLUID.getKey(primaryFluid).toString();
                double amount = slot.getAmount() * craftOperations;
                nodeBuilder.addFluidChild(new FluidNode(fluidName, amount));
            }
        }

        visitedOnPath.remove(item);
        return nodeBuilder.build();
    }

    private void calculateBaseResourcesFor(
            Item item,
            double neededAmount,
            Set<Item> visited,
            Map<Item, Double> baseResources,
            DisplayMode displayMode
    ) {
        if (!visited.add(item)) return;

        Optional<RecipeNode> recipeOpt = depthAnalyzer.getRecipeToFollow(item);

        if (recipeOpt.isEmpty() || recipeOpt.get().isBaseRecipe()) {
            double amountToAdd = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ?
                    Math.ceil(neededAmount) : neededAmount;
            baseResources.merge(item, amountToAdd, Double::sum);
            visited.remove(item);
            return;
        }

        RecipeNode recipe = recipeOpt.get();
        double craftOperations;
        if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
            int neededPlayerAmount = (int) Math.ceil(neededAmount);
            craftOperations = Math.ceil((double) neededPlayerAmount / recipe.getResultCount());
        } else {
            craftOperations = neededAmount / recipe.getResultCount();
        }

        for (IngredientSlot slot : recipe.getIngredients()) {
            Item bestVariant = slot.getVariants().stream()
                    .min(Comparator.comparingDouble(engine::getComplexity))
                    .orElse(null);

            if (bestVariant != null) {
                double totalIngredientNeeded = craftOperations * slot.getCount();
                calculateBaseResourcesFor(bestVariant, totalIngredientNeeded, visited,
                        baseResources, displayMode);
            }
        }
        visited.remove(item);
    }
}