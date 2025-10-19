package org.complexityanalyzer.analyzer;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DepthAnalyzer {
    private final RecipeGraph graph;
    private final Map<Item, Integer> cache;
    private final Map<Item, Optional<RecipeNode>> recipeCache = new ConcurrentHashMap<>();

    private static final int CYCLE_DEPTH = Integer.MAX_VALUE;
    private static final int IN_PROGRESS = -999;

    public DepthAnalyzer(RecipeGraph graph, SourceManager ignoredSourceManager) {
        this.graph = graph;
        this.cache = new ConcurrentHashMap<>();
    }

    public int getDepth(Item item) {
        Integer cachedDepth = cache.get(item);
        if (cachedDepth != null) {
            return cachedDepth;
        }
        return calculateDepth(item);
    }

    private int calculateDepth(Item item) {
        Integer cached = cache.get(item);
        if (cached != null) {
            if (cached == IN_PROGRESS) {
                return CYCLE_DEPTH;
            }
            return cached;
        }

        cache.put(item, IN_PROGRESS);

        Optional<RecipeNode> recipeOpt = getRecipeToFollow(item);
        if (recipeOpt.isEmpty()) {
            cache.put(item, 0);
            return 0;
        }

        RecipeNode recipeToFollow = recipeOpt.get();

        int maxIngredientDepth = 0;
        boolean cycleDetected = false;

        for (IngredientSlot slot : recipeToFollow.getIngredients()) {
            int slotDepth = calculateSlotDepth(slot);
            if (slotDepth == CYCLE_DEPTH) {
                cycleDetected = true;
                break;
            }
            maxIngredientDepth = Math.max(maxIngredientDepth, slotDepth);
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
        if (slot.getVariants().isEmpty()) {
            return 0;
        }

        int minDepth = CYCLE_DEPTH;
        for (Item variant : slot.getVariants()) {
            int variantDepth = getDepth(variant);
            minDepth = Math.min(minDepth, variantDepth);
        }

        return minDepth;
    }

    public Optional<RecipeNode> getRecipeToFollow(Item item) {
        return recipeCache.computeIfAbsent(item, key -> {
            List<RecipeNode> recipes = graph.getRecipes(key);
            if (recipes.isEmpty()) {
                return Optional.empty();
            }

            return recipes.stream()
                    .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE)
                    .max(Comparator.comparingInt((RecipeNode r) -> r.getCategory() == RecipeCategory.PRIMARY ? 1 : 0)
                            .thenComparingInt(RecipeNode::getPriority));
        });
    }
}