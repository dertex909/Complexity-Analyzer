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

import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.world.item.Items.AIR;
import static net.minecraft.world.level.material.Fluids.EMPTY;
import static org.complexityanalyzer.util.FluidNormalizer.normalize;

public class RecipeGraph {
    private final Reference2ObjectOpenHashMap<Item, ObjectList<RecipeNode>> recipesByItem;
    private final Reference2ObjectOpenHashMap<Item, ReferenceSet<Item>> usageMap;
    private final ConcurrentHashMap<Item, RecipeNode> bestRecipeCache;
    private final Object2ObjectOpenHashMap<ResourceLocation, ObjectList<RecipeNode>> recipesByFluid;
    private final Reference2ObjectOpenHashMap<Fluid, ObjectList<RecipeNode>> recipesByFluidOutput;
    private final Reference2ObjectOpenHashMap<Fluid, ReferenceSet<Item>> fluidUsageMap;
    private final ObjectArrayList<RecipeNode> allRecipes;

    public RecipeGraph() {
        this.recipesByItem = new Reference2ObjectOpenHashMap<>(16384);
        this.usageMap = new Reference2ObjectOpenHashMap<>(16384);
        this.bestRecipeCache = new ConcurrentHashMap<>(4096);
        this.recipesByFluid = new Object2ObjectOpenHashMap<>(1024);
        this.recipesByFluidOutput = new Reference2ObjectOpenHashMap<>(1024);
        this.fluidUsageMap = new Reference2ObjectOpenHashMap<>(1024);
        this.allRecipes = new ObjectArrayList<>(16384);
    }

    public ObjectList<RecipeNode> getAllRecipes() {
        return allRecipes;
    }

    private void appendToAllRecipes(RecipeNode node) {
        int idx = allRecipes.size();
        node.setListIndex(idx);
        allRecipes.add(node);
    }

    private void replaceOrAddInAllRecipes(RecipeNode existing, RecipeNode node) {
        int allIdx = existing.getListIndex();
        if (allIdx != -1 && allIdx < allRecipes.size()) {
            node.setListIndex(allIdx);
            allRecipes.set(allIdx, node);
        } else {
            appendToAllRecipes(node);
        }
    }

    public void addRecipe(RecipeNode node) {
        var outputs = node.getItemOutputs();
        var primaryResult = node.getResultItem();
        var allOutputItems = new ReferenceOpenHashSet<Item>(Math.max(2, outputs.size() + 1));

        if (primaryResult != null && primaryResult != AIR) allOutputItems.add(primaryResult);
        for (var stack : outputs) if (!stack.isEmpty() && stack.getItem() != AIR) allOutputItems.add(stack.getItem());
        if (allOutputItems.isEmpty() && !node.isPlaceholder()) return;

        boolean isDuplicate = false;
        for (var outItem : allOutputItems) {
            var list = recipesByItem.computeIfAbsent(outItem, k -> new ObjectArrayList<>(2));
            int dupIdx = list.indexOf(node);
            if (dupIdx != -1) {
                isDuplicate = true;
                var existing = list.get(dupIdx);
                boolean nodeIsBetter = node.getFluidIngredients().size() > existing.getFluidIngredients().size()
                        || node.getItemOutputs().size() > existing.getItemOutputs().size()
                        || node.getFluidOutputs().size() > existing.getFluidOutputs().size();
                if (nodeIsBetter) {
                    list.set(dupIdx, node);
                    replaceOrAddInAllRecipes(existing, node);
                    registerFluidIngredientsUsage(node, outItem);
                    registerFluidOutputs(node);
                    bestRecipeCache.remove(outItem);
                }
            } else {
                list.add(node);
            }
        }

        if (isDuplicate) return;

        if (node.isPlaceholder() && node.getPlaceholderId() != null && !node.getPlaceholderId().isEmpty()) {
            try {
                var fluidId = ResourceLocation.parse(node.getPlaceholderId());
                var list = recipesByFluid.computeIfAbsent(fluidId, k -> new ObjectArrayList<>(2));
                int dupIdx = list.indexOf(node);
                if (dupIdx != -1) {
                    var existing = list.get(dupIdx);
                    if (node.getFluidOutputs().size() > existing.getFluidOutputs().size()) {
                        list.set(dupIdx, node);
                        replaceOrAddInAllRecipes(existing, node);
                        registerFluidOutputs(node);
                    }
                    return;
                } else {
                    list.add(node);
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Invalid placeholder ID: {}", node.getPlaceholderId());
            }
        }

        appendToAllRecipes(node);
        for (var outItem : allOutputItems) {
            registerItemIngredientsUsage(node, outItem);
            registerFluidIngredientsUsage(node, outItem);
            bestRecipeCache.remove(outItem);
        }
        registerFluidOutputs(node);
    }

    private void registerFluidOutputs(RecipeNode node) {
        for (var stack : node.getFluidOutputs()) {
            var normalized = normalize(stack.getFluid());
            if (normalized != EMPTY) {
                var foList = recipesByFluidOutput.computeIfAbsent(normalized, k -> new ObjectArrayList<>(2));
                if (!foList.contains(node)) foList.add(node);
            }
        }
    }

    private void registerFluidIngredientsUsage(RecipeNode node, Item result) {
        if (result == AIR) return;
        for (var slot : node.getFluidIngredients()) {
            for (var variant : slot.getFluidVariants()) {
                var normalized = normalize(variant);
                if (normalized != EMPTY) addUsage(fluidUsageMap, normalized, result);
            }
        }
    }

    private void registerItemIngredientsUsage(RecipeNode node, Item result) {
        if (result == AIR) return;
        for (var slot : node.getIngredients()) {
            for (var ingredientStack : slot.getVariants()) {
                var ingredient = ingredientStack.getItem();
                if (ingredient != AIR) addUsage(usageMap, ingredient, result);
            }
        }
    }

    private <K> void addUsage(Reference2ObjectOpenHashMap<K, ReferenceSet<Item>> map, K key, Item result) {
        map.computeIfAbsent(key, k -> new ReferenceOpenHashSet<>(4)).add(result);
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

    public ReferenceSet<Item> getItemsUsingFluid(Fluid fluid) {
        return fluidUsageMap.getOrDefault(normalize(fluid), ReferenceSets.emptySet());
    }

    public int getUsageCount(Item item) {
        var users = usageMap.get(item);
        return users != null ? users.size() : 0;
    }

    public ReferenceSet<Item> getItemsUsingIngredient(Item ingredient) {
        return usageMap.getOrDefault(ingredient, ReferenceSets.emptySet());
    }

    public ReferenceSet<Item> getAllItems() {
        return new ReferenceOpenHashSet<>(recipesByItem.keySet());
    }

    public int getTotalRecipeCount() {
        return allRecipes.size();
    }

    public void clear() {
        recipesByItem.clear();
        usageMap.clear();
        bestRecipeCache.clear();
        recipesByFluid.clear();
        recipesByFluidOutput.clear();
        fluidUsageMap.clear();
        allRecipes.clear();
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
        for (var recipe : allRecipes) {
            for (var slot : recipe.getFluidIngredients()) {
                for (var f : slot.getFluidVariants()) fluids.add(normalize(f));
            }
            for (var stack : recipe.getFluidOutputs()) fluids.add(normalize(stack.getFluid()));
        }
        return fluids;
    }

    public record GraphStats(int itemsWithRecipes, int totalRecipes, int itemsUsedAsIngredients) {
    }
}