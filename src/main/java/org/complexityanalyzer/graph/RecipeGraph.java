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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.world.item.Items.AIR;
import static net.minecraft.world.level.material.Fluids.EMPTY;
import static org.complexityanalyzer.util.FluidNormalizer.normalize;

public class RecipeGraph {
    private final ConcurrentHashMap<Item, ObjectList<RecipeNode>> recipesByItem;
    private final ConcurrentHashMap<Item, ObjectList<Item>> usageMap;
    private final ConcurrentHashMap<Item, RecipeNode> bestRecipeCache;
    private final ConcurrentHashMap<ResourceLocation, ObjectList<RecipeNode>> recipesByFluid;
    private final ConcurrentHashMap<Fluid, ObjectList<RecipeNode>> recipesByFluidOutput;
    private final ConcurrentHashMap<Fluid, ObjectList<Item>> fluidUsageMap;
    private final ConcurrentHashMap<Integer, RecipeNode> allRecipesMap;
    private final AtomicInteger recipeCounter;

    public RecipeGraph() {
        this.recipesByItem = new ConcurrentHashMap<>(16384);
        this.usageMap = new ConcurrentHashMap<>(16384);
        this.bestRecipeCache = new ConcurrentHashMap<>(16384);
        this.recipesByFluid = new ConcurrentHashMap<>(1024);
        this.recipesByFluidOutput = new ConcurrentHashMap<>(1024);
        this.fluidUsageMap = new ConcurrentHashMap<>(1024);
        this.allRecipesMap = new ConcurrentHashMap<>(16384);
        this.recipeCounter = new AtomicInteger(0);
    }

    public ObjectList<RecipeNode> getAllRecipes() {
        int size = recipeCounter.get();
        var list = new ObjectArrayList<RecipeNode>(size);
        for (int i = 0; i < size; i++) {
            var node = allRecipesMap.get(i);
            if (node != null) list.add(node);
        }
        return list;
    }

    private void appendToAllRecipes(RecipeNode node) {
        int idx = recipeCounter.getAndIncrement();
        node.setListIndex(idx);
        allRecipesMap.put(idx, node);
    }

    private void replaceOrAddInAllRecipes(RecipeNode existing, RecipeNode node) {
        int allIdx = existing.getListIndex();
        if (allIdx != -1) {
            node.setListIndex(allIdx);
            allRecipesMap.put(allIdx, node);
        } else {
            appendToAllRecipes(node);
        }
    }

    public void addRecipe(RecipeNode node) {
        var result = node.getResultItem();
        if (result == AIR) return;

        boolean[] isDuplicate = {false};
        recipesByItem.compute(result, (item, list) -> {
            if (list == null) list = new ObjectArrayList<>();
            int dupIdx = list.indexOf(node);
            if (dupIdx != -1) {
                isDuplicate[0] = true;
                var existing = list.get(dupIdx);
                boolean nodeIsBetter = node.getFluidIngredients().size() > existing.getFluidIngredients().size()
                        || node.getItemOutputs().size() > existing.getItemOutputs().size()
                        || node.getFluidOutputs().size() > existing.getFluidOutputs().size();
                if (nodeIsBetter) {
                    var newList = new ObjectArrayList<>(list);
                    newList.set(dupIdx, node);
                    replaceOrAddInAllRecipes(existing, node);
                    registerFluidIngredientsUsage(node, result);
                    registerFluidOutputs(node);
                    bestRecipeCache.remove(result);
                    return newList;
                }
                return list;
            } else {
                var newList = new ObjectArrayList<>(list);
                newList.add(node);
                return newList;
            }
        });

        if (isDuplicate[0]) return;

        if (node.isPlaceholder() && node.getPlaceholderId() != null && !node.getPlaceholderId().isEmpty()) try {
            var fluidId = ResourceLocation.parse(node.getPlaceholderId());
            boolean[] isPlaceholderDuplicate = {false};
            recipesByFluid.compute(fluidId, (id, list) -> {
                if (list == null) list = new ObjectArrayList<>();
                int dupIdx = list.indexOf(node);
                if (dupIdx != -1) {
                    isPlaceholderDuplicate[0] = true;
                    var existing = list.get(dupIdx);
                    if (node.getFluidOutputs().size() > existing.getFluidOutputs().size()) {
                        var newList = new ObjectArrayList<>(list);
                        newList.set(dupIdx, node);
                        replaceOrAddInAllRecipes(existing, node);
                        registerFluidOutputs(node);
                        return newList;
                    }
                    return list;
                } else {
                    var newList = new ObjectArrayList<>(list);
                    newList.add(node);
                    return newList;
                }
            });
            if (isPlaceholderDuplicate[0]) return;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("Invalid placeholder ID: {}", node.getPlaceholderId());
        }

        appendToAllRecipes(node);
        registerItemIngredientsUsage(node, result);
        registerFluidIngredientsUsage(node, result);
        registerFluidOutputs(node);
        bestRecipeCache.remove(result);
    }

    private void registerFluidOutputs(RecipeNode node) {
        for (var stack : node.getFluidOutputs()) {
            var normalized = normalize(stack.getFluid());
            if (normalized != EMPTY) recipesByFluidOutput.compute(normalized, (f, foList) -> {
                if (foList == null) foList = new ObjectArrayList<>();
                if (!foList.contains(node)) {
                    var newFoList = new ObjectArrayList<>(foList);
                    newFoList.add(node);
                    return newFoList;
                }
                return foList;
            });
        }
    }

    private void registerFluidIngredientsUsage(RecipeNode node, Item result) {
        if (result == AIR) return;
        for (var slot : node.getFluidIngredients()) {
            for (var variant : slot.getFluidVariants()) {
                var normalized = normalize(variant);
                if (normalized != EMPTY) addFluidUsage(normalized, result);
            }
        }
    }

    private void registerItemIngredientsUsage(RecipeNode node, Item result) {
        if (result == AIR) return;
        for (var slot : node.getIngredients()) {
            for (var ingredientStack : slot.getVariants()) {
                var ingredient = ingredientStack.getItem();
                if (ingredient != AIR) addItemUsage(ingredient, result);
            }
        }
    }

    private <K> void addUsage(ConcurrentHashMap<K, ObjectList<Item>> map, K key, Item result) {
        map.compute(key, (k, list) -> {
            if (list == null) {
                var newList = new ObjectArrayList<Item>(2);
                newList.add(result);
                return newList;
            }
            if (!list.contains(result)) {
                var newList = new ObjectArrayList<Item>(list.size() + 1);
                newList.addAll(list);
                newList.add(result);
                return newList;
            }
            return list;
        });
    }

    private void addItemUsage(Item ingredient, Item result) {
        addUsage(usageMap, ingredient, result);
    }

    private void addFluidUsage(Fluid fluid, Item result) {
        addUsage(fluidUsageMap, fluid, result);
    }

    public ObjectList<RecipeNode> getRecipes(Item item) {
        return recipesByItem.getOrDefault(item, ObjectLists.emptyList());
    }

    public RecipeNode getBestRecipe(Item item) {
        return bestRecipeCache.computeIfAbsent(item, this::findBestRecipe);
    }

    private RecipeNode findBestRecipe(Item item) {
        var recipes = getRecipes(item);
        if (recipes.isEmpty()) return RecipeNode.empty(item);

        var best = recipes.getFirst();
        for (int i = 1; i < recipes.size(); i++) {
            var current = recipes.get(i);
            if (current.getPriority() > best.getPriority()) best = current;
        }
        return best;
    }

    public boolean hasRecipe(Item item) {
        var recipes = recipesByItem.get(item);
        return recipes != null && !recipes.isEmpty();
    }

    public ObjectList<RecipeNode> getFluidRecipes(Fluid fluid) {
        return recipesByFluidOutput.getOrDefault(normalize(fluid), ObjectLists.emptyList());
    }

    public boolean hasFluidRecipe(Fluid fluid) {
        var recipes = recipesByFluidOutput.get(normalize(fluid));
        return recipes != null && !recipes.isEmpty();
    }

    public int getFluidUsageCount(Fluid fluid) {
        var users = fluidUsageMap.get(normalize(fluid));
        return users != null ? users.size() : 0;
    }

    public ObjectList<Item> getItemsUsingFluid(Fluid fluid) {
        return fluidUsageMap.getOrDefault(normalize(fluid), ObjectLists.emptyList());
    }

    public int getUsageCount(Item item) {
        var users = usageMap.get(item);
        return users != null ? users.size() : 0;
    }

    public ObjectList<Item> getItemsUsingIngredient(Item ingredient) {
        return usageMap.getOrDefault(ingredient, ObjectLists.emptyList());
    }

    public ReferenceSet<Item> getAllItems() {
        return new ReferenceOpenHashSet<>(recipesByItem.keySet());
    }

    public int getTotalRecipeCount() {
        return recipeCounter.get();
    }

    public void clear() {
        recipesByItem.clear();
        usageMap.clear();
        bestRecipeCache.clear();
        recipesByFluid.clear();
        recipesByFluidOutput.clear();
        fluidUsageMap.clear();
        allRecipesMap.clear();
        recipeCounter.set(0);
        IngredientSlot.clearCache();
        FluidIngredientSlot.clearCache();
        ItemStackCanonicalizer.clear();
        ComplexityAnalyzer.LOGGER.info("Recipe graph cleared");
    }

    public GraphStats getStats() {
        return new GraphStats(recipesByItem.size(), getTotalRecipeCount(), usageMap.size());
    }

    public ReferenceSet<Item> getCorpus() {
        var allItems = new ReferenceOpenHashSet<>(recipesByItem.keySet());
        allItems.addAll(usageMap.keySet());
        return allItems;
    }

    public ReferenceSet<Fluid> getAllUsedFluids() {
        var fluids = new ReferenceOpenHashSet<Fluid>();
        for (var recipe : getAllRecipes()) {
            for (var slot : recipe.getFluidIngredients()) {
                for (var f : slot.getFluidVariants()) fluids.add(normalize(f));
            }
            for (var stack : recipe.getFluidOutputs()) fluids.add(normalize(stack.getFluid()));
        }
        return fluids;
    }

    public record GraphStats(int itemsWithRecipes, int totalRecipes, int itemsUsedAsIngredients) {
        @Override
        public @NotNull String toString() {
            return String.format("GraphStats{items=%d, recipes=%d, ingredients=%d}", itemsWithRecipes, totalRecipes, itemsUsedAsIngredients);
        }
    }
}