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
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.world.item.Items.AIR;
import static net.minecraft.world.level.material.Fluids.EMPTY;

public class RecipeGraph {
    private final ConcurrentHashMap<Item, ObjectList<RecipeNode>> recipesByItem;
    private final ConcurrentHashMap<Item, Set<Item>> usageMap;
    private final ConcurrentHashMap<Item, RecipeNode> bestRecipeCache;
    private final ConcurrentHashMap<ResourceLocation, ObjectList<RecipeNode>> recipesByFluid;
    private final ConcurrentHashMap<Fluid, ObjectList<RecipeNode>> recipesByFluidOutput;
    private final ConcurrentHashMap<Fluid, Set<Item>> fluidUsageMap;
    private final ObjectList<RecipeNode> allRecipesList;

    public RecipeGraph() {
        this.recipesByItem = new ConcurrentHashMap<>(16384);
        this.usageMap = new ConcurrentHashMap<>(16384);
        this.bestRecipeCache = new ConcurrentHashMap<>(16384);
        this.recipesByFluid = new ConcurrentHashMap<>(1024);
        this.recipesByFluidOutput = new ConcurrentHashMap<>(1024);
        this.fluidUsageMap = new ConcurrentHashMap<>(1024);
        this.allRecipesList = ObjectLists.synchronize(new ObjectArrayList<>());
    }

    private static Fluid normalizeFluid(Fluid fluid) {
        var id = GameRegistryManager.getFluidId(fluid);
        if (id == null) return fluid;
        var fluidName = id.toString();

        if (fluidName.contains("flowing_")) {
            var staticName = fluidName.replace("flowing_", "");
            var staticId = ResourceLocation.parse(staticName);
            var staticFluid = GameRegistryManager.getFluid(staticId);
            if (staticFluid != null) return staticFluid;
        }

        return fluid;
    }

    public ObjectList<RecipeNode> getAllRecipes() {
        synchronized (allRecipesList) {
            return new ObjectArrayList<>(allRecipesList);
        }
    }

    private void appendToAllRecipes(RecipeNode node) {
        synchronized (allRecipesList) {
            node.setListIndex(allRecipesList.size());
            allRecipesList.add(node);
        }
    }

    private void replaceOrAddInAllRecipes(RecipeNode existing, RecipeNode node) {
        synchronized (allRecipesList) {
            int allIdx = existing.getListIndex();
            if (allIdx != -1 && allIdx < allRecipesList.size() && allRecipesList.get(allIdx) == existing) {
                node.setListIndex(allIdx);
                allRecipesList.set(allIdx, node);
            } else {
                node.setListIndex(allRecipesList.size());
                allRecipesList.add(node);
            }
        }
    }

    public void addRecipe(RecipeNode node) {
        var result = node.getResultItem();
        if (result == AIR && !node.isPlaceholder() && node.getFluidOutputs().isEmpty() && node.getChemicalOutputs().isEmpty()) {
            return;
        }

        if (result != AIR) synchronized (result) {
            var list = recipesByItem.computeIfAbsent(result, k -> ObjectLists.synchronize(new ObjectArrayList<>()));
            int dupIdx = -1;
            synchronized (list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).equals(node)) {
                        dupIdx = i;
                        break;
                    }
                }
                if (dupIdx != -1) {
                    var existing = list.get(dupIdx);
                    boolean nodeIsBetter = node.getFluidIngredients().size() > existing.getFluidIngredients().size()
                            || node.getItemOutputs().size() > existing.getItemOutputs().size()
                            || node.getFluidOutputs().size() > existing.getFluidOutputs().size();
                    if (nodeIsBetter) {
                        list.set(dupIdx, node);
                        replaceOrAddInAllRecipes(existing, node);
                        for (var slot : node.getFluidIngredients()) {
                            for (var variant : slot.getFluidVariants()) {
                                var normalized = normalizeFluid(variant);
                                if (normalized != EMPTY) {
                                    fluidUsageMap.computeIfAbsent(normalized, k -> ConcurrentHashMap.newKeySet()).add(result);
                                }
                            }
                        }
                        for (var stack : node.getFluidOutputs()) {
                            var normalized = normalizeFluid(stack.getFluid());
                            if (normalized != EMPTY) {
                                var foList = recipesByFluidOutput.computeIfAbsent(normalized, k -> ObjectLists.synchronize(new ObjectArrayList<>()));
                                synchronized (foList) {
                                    if (!foList.contains(node)) foList.add(node);
                                }
                            }
                        }
                        bestRecipeCache.remove(result);
                    }
                    return;
                } else {
                    list.add(node);
                }
            }
        }

        if (node.isPlaceholder() && node.getPlaceholderId() != null && !node.getPlaceholderId().isEmpty()) try {
            var fluidId = ResourceLocation.parse(node.getPlaceholderId());
            synchronized (node.getPlaceholderId().intern()) {
                var list = recipesByFluid.computeIfAbsent(fluidId, k -> ObjectLists.synchronize(new ObjectArrayList<>()));
                int dupIdx = -1;
                synchronized (list) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).equals(node)) {
                            dupIdx = i;
                            break;
                        }
                    }
                    if (dupIdx != -1) {
                        var existing = list.get(dupIdx);
                        boolean nodeIsBetter = node.getFluidOutputs().size() > existing.getFluidOutputs().size();
                        if (nodeIsBetter) {
                            list.set(dupIdx, node);
                            replaceOrAddInAllRecipes(existing, node);
                            for (var stack : node.getFluidOutputs()) {
                                var normalized = normalizeFluid(stack.getFluid());
                                if (normalized != EMPTY) {
                                    var foList = recipesByFluidOutput.computeIfAbsent(normalized, k -> ObjectLists.synchronize(new ObjectArrayList<>()));
                                    synchronized (foList) {
                                        if (!foList.contains(node)) foList.add(node);
                                    }
                                }
                            }
                        }
                        return;
                    } else {
                        list.add(node);
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("Invalid placeholder ID: {}", node.getPlaceholderId());
        }

        appendToAllRecipes(node);

        for (var slot : node.getIngredients()) {
            for (var ingredientStack : slot.getVariants()) {
                var ingredient = ingredientStack.getItem();
                if (result != AIR) {
                    usageMap.computeIfAbsent(ingredient, k -> ConcurrentHashMap.newKeySet()).add(result);
                }
            }
        }

        for (var slot : node.getFluidIngredients()) {
            for (var variant : slot.getFluidVariants()) {
                var normalized = normalizeFluid(variant);
                if (normalized != EMPTY) if (result != AIR) {
                    fluidUsageMap.computeIfAbsent(normalized, k -> ConcurrentHashMap.newKeySet()).add(result);
                }
            }
        }

        for (var stack : node.getFluidOutputs()) {
            var normalized = normalizeFluid(stack.getFluid());
            if (normalized != EMPTY) {
                var foList = recipesByFluidOutput.computeIfAbsent(normalized, k -> ObjectLists.synchronize(new ObjectArrayList<>()));
                synchronized (foList) {
                    if (!foList.contains(node)) foList.add(node);
                }
            }
        }

        bestRecipeCache.remove(result);
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
        if (recipes.size() == 1) return recipes.getFirst();

        RecipeNode best = null;
        for (var r : recipes) if (best == null || r.getPriority() > best.getPriority()) best = r;
        return best != null ? best : recipes.getFirst();
    }

    public boolean hasRecipe(Item item) {
        var recipes = recipesByItem.get(item);
        return recipes != null && !recipes.isEmpty();
    }

    public ObjectList<RecipeNode> getFluidRecipes(Fluid fluid) {
        return recipesByFluidOutput.getOrDefault(normalizeFluid(fluid), ObjectLists.emptyList());
    }

    public boolean hasFluidRecipe(Fluid fluid) {
        var recipes = recipesByFluidOutput.get(normalizeFluid(fluid));
        return recipes != null && !recipes.isEmpty();
    }

    public int getFluidUsageCount(Fluid fluid) {
        var users = fluidUsageMap.get(normalizeFluid(fluid));
        return users != null ? users.size() : 0;
    }

    public ReferenceSet<Item> getItemsUsingFluid(Fluid fluid) {
        var users = fluidUsageMap.get(normalizeFluid(fluid));
        return users != null ? new ReferenceOpenHashSet<>(users) : ReferenceSets.emptySet();
    }

    public int getUsageCount(Item item) {
        var users = usageMap.get(item);
        return users != null ? users.size() : 0;
    }

    public ReferenceSet<Item> getItemsUsingIngredient(Item ingredient) {
        var users = usageMap.get(ingredient);
        return users != null ? new ReferenceOpenHashSet<>(users) : ReferenceSets.emptySet();
    }

    public ReferenceSet<Item> getAllItems() {
        return new ReferenceOpenHashSet<>(recipesByItem.keySet());
    }

    public int getTotalRecipeCount() {
        synchronized (allRecipesList) {
            return allRecipesList.size();
        }
    }

    public void clear() {
        recipesByItem.clear();
        usageMap.clear();
        bestRecipeCache.clear();
        recipesByFluid.clear();
        recipesByFluidOutput.clear();
        fluidUsageMap.clear();
        synchronized (allRecipesList) {
            allRecipesList.clear();
        }
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
                for (var f : slot.getFluidVariants()) fluids.add(normalizeFluid(f));
            }
            for (var stack : recipe.getFluidOutputs()) fluids.add(normalizeFluid(stack.getFluid()));
        }
        return fluids;
    }

    public record GraphStats(
            int itemsWithRecipes,
            int totalRecipes,
            int itemsUsedAsIngredients
    ) {
        @Override
        public @NotNull String toString() {
            return String.format("GraphStats{items=%d, recipes=%d, ingredients=%d}", itemsWithRecipes, totalRecipes, itemsUsedAsIngredients);
        }
    }
}