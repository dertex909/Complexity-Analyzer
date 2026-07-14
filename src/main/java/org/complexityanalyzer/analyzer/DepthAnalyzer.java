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

package org.complexityanalyzer.analyzer;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

public class DepthAnalyzer {
    private final RecipeGraph graph;
    private final ConcurrentHashMap<Item, Integer> cache;
    private final ConcurrentHashMap<Item, RecipeNode> recipeCache;
    private final SourceManager sourceManager;
    private final ThreadLocal<ReferenceSet<Item>> inProgress = ThreadLocal.withInitial(ReferenceOpenHashSet::new);
    private volatile ConcurrentHashMap<Item, RecipeNode> optimalRecipes;

    public DepthAnalyzer(RecipeGraph graph, SourceManager sourceManager) {
        this.graph = graph;
        this.cache = new ConcurrentHashMap<>();
        this.recipeCache = new ConcurrentHashMap<>();
        this.optimalRecipes = new ConcurrentHashMap<>();
        this.sourceManager = sourceManager;
    }

    public void setOptimalRecipes(Reference2ObjectMap<Item, RecipeNode> optimalRecipes) {
        this.optimalRecipes = new ConcurrentHashMap<>(optimalRecipes);
        this.recipeCache.clear();
        this.cache.clear();
    }

    public int getDepth(Item item) {
        Integer cachedDepth = cache.get(item);
        if (cachedDepth != null) return cachedDepth;
        return calculateDepth(item);
    }

    private int calculateDepth(Item item) {
        Integer cached = cache.get(item);
        if (cached != null) return cached;

        var visiting = inProgress.get();
        if (!visiting.add(item)) return 0;

        try {
            var recipeToFollow = getRecipeToFollow(item);
            if (recipeToFollow == null) {
                var source = sourceManager != null ? sourceManager.analyze(item) : null;
                if (source != null && !source.getSourceItems().isEmpty()) {
                    int maxSourceDepth = 0;
                    boolean hasValidDeps = false;
                    for (var entry : source.getSourceItems().reference2DoubleEntrySet()) {
                        var dep = entry.getKey();
                        if (dep != item) {
                            int depDepth = getDepth(dep);
                            if (depDepth > maxSourceDepth) maxSourceDepth = depDepth;
                            hasValidDeps = true;
                        }
                    }
                    if (hasValidDeps) {
                        int depthVal = 1 + maxSourceDepth;
                        cache.put(item, depthVal);
                        return depthVal;
                    }
                }
                cache.put(item, 0);
                return 0;
            }

            int maxIngredientDepth = 0;

            for (var slot : recipeToFollow.getIngredients()) {
                int slotDepth = calculateSlotDepth(slot);
                if (slotDepth > maxIngredientDepth) maxIngredientDepth = slotDepth;
            }

            int finalDepth = 1 + maxIngredientDepth;

            cache.put(item, finalDepth);
            return finalDepth;
        } finally {
            visiting.remove(item);
        }
    }

    private int calculateSlotDepth(IngredientSlot slot) {
        var variants = slot.getVariants();
        if (variants.isEmpty()) return 0;

        int minDepth = Integer.MAX_VALUE;
        for (var variant : variants) {
            int variantDepth = getDepth(variant.getItem());
            if (variantDepth < minDepth) minDepth = variantDepth;
        }

        return minDepth == Integer.MAX_VALUE ? 0 : minDepth;
    }

    @Nullable
    public RecipeNode getRecipeToFollow(Item item) {
        var cached = recipeCache.get(item);
        if (cached != null) return cached;

        var optimal = optimalRecipes.get(item);
        if (optimal != null) {
            recipeCache.put(item, optimal);
            return optimal;
        }

        if (hasFiniteBaseSource(item)) return null;

        if (graph != null && graph.hasRecipe(item)) {
            var bestFromGraph = graph.getBestRecipe(item);
            if (bestFromGraph != null && !bestFromGraph.isBaseRecipe()) {
                recipeCache.put(item, bestFromGraph);
                return bestFromGraph;
            }
        }

        return null;
    }

    private boolean hasFiniteBaseSource(Item item) {
        if (sourceManager == null) return false;
        var data = sourceManager.analyze(item);
        return data != null && !isUnobtainable(data);
    }

    private boolean isUnobtainable(BaseResourceData data) {
        if (data == null) return true;
        if (Double.isInfinite(data.getBaseFactor())) return true;
        return data.getSourceType() == BaseResourceData.ResourceSourceType.UNOBTAINABLE;
    }
}