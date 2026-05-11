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

package org.complexityanalyzer.analyzer;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.*;

import org.jetbrains.annotations.Nullable;

public class DepthAnalyzer {
    private final RecipeGraph graph;
    private final Reference2IntMap<Item> cache;
    private final Reference2ObjectMap<Item, RecipeNode> recipeCache;
    private Reference2ObjectMap<Item, RecipeNode> optimalRecipes;
    private final SourceManager sourceManager;

    private static final int CYCLE_DEPTH = Integer.MAX_VALUE;
    private static final int IN_PROGRESS = -999;

    public DepthAnalyzer(RecipeGraph graph, SourceManager sourceManager) {
        this.graph = graph;
        this.cache = Reference2IntMaps.synchronize(new Reference2IntOpenHashMap<>());
        this.recipeCache = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        this.optimalRecipes = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        this.sourceManager = sourceManager;
        this.cache.defaultReturnValue(-1);
    }

    public void setOptimalRecipes(Reference2ObjectMap<Item, RecipeNode> optimalRecipes) {
        this.optimalRecipes = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>(optimalRecipes));
        this.recipeCache.clear();
        this.cache.clear();
    }

    public int getDepth(Item item) {
        int cachedDepth = cache.getInt(item);
        if (cachedDepth != -1) return cachedDepth;
        return calculateDepth(item);
    }

    private int calculateDepth(Item item) {
        int cached = cache.getInt(item);
        if (cached != -1) {
            if (cached == IN_PROGRESS) return CYCLE_DEPTH;
            return cached;
        }

        cache.put(item, IN_PROGRESS);

        RecipeNode recipeToFollow = getRecipeToFollow(item);
        if (recipeToFollow == null) {
            cache.put(item, 0);
            return 0;
        }

        int maxIngredientDepth = 0;
        boolean cycleDetected = false;

        for (var slot : recipeToFollow.getIngredients()) {
            int slotDepth = calculateSlotDepth(slot);
            if (slotDepth == CYCLE_DEPTH) {
                cycleDetected = true;
                break;
            }
            if (slotDepth > maxIngredientDepth) maxIngredientDepth = slotDepth;
        }

        int finalDepth;
        if (cycleDetected) {
            finalDepth = CYCLE_DEPTH;
        } else {
            long calculatedDepth = 1L + maxIngredientDepth;
            finalDepth = (int) Math.min(calculatedDepth, CYCLE_DEPTH);
        }

        int limitedDepth = Math.min(finalDepth, ComplexityConfig.MAX_DEPTH.get());
        cache.put(item, limitedDepth);
        return limitedDepth;
    }

    private int calculateSlotDepth(IngredientSlot slot) {
        var variants = slot.getVariants();
        if (variants.isEmpty()) return 0;

        int minDepth = CYCLE_DEPTH;
        for (var variant : variants) {
            int variantDepth = getDepth(variant);
            if (variantDepth < minDepth) minDepth = variantDepth;
        }

        return minDepth;
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
                if (bestFromGraph.getCategory() == RecipeCategory.STORAGE_DECOMPRESSION && hasFiniteBaseSource(item)) {
                    return null;
                }
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