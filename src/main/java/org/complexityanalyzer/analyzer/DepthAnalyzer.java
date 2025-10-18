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

    public DepthAnalyzer(RecipeGraph graph, SourceManager ignoredSourceManager) {
        this.graph = graph;
        this.cache = new ConcurrentHashMap<>();
    }

    public int getDepth(Item item) {
        return cache.computeIfAbsent(item, k -> calculateDepth(k, new HashSet<>()));
    }

    public Optional<RecipeNode> getRecipeToFollow(Item item) {
        return recipeCache.computeIfAbsent(item, key -> {
            List<RecipeNode> recipes = graph.getRecipes(key);
            if (recipes.isEmpty()) {
                return Optional.empty();
            }

            Optional<RecipeNode> primaryRecipe = recipes.stream()
                    .filter(r -> r.getCategory() == RecipeCategory.PRIMARY)
                    .max(Comparator.comparingInt(RecipeNode::getPriority));

            if (primaryRecipe.isPresent()) {
                return primaryRecipe;
            }

            return recipes.stream()
                    .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE)
                    .max(Comparator.comparingInt(RecipeNode::getPriority));
        });
    }

    private int calculateDepth(Item item, Set<Item> visiting) {
        if (!visiting.add(item)) {
            return 0;
        }

        if (!graph.hasRecipe(item)) {
            visiting.remove(item);
            return 0;
        }

        Optional<RecipeNode> recipeOpt = getRecipeToFollow(item);

        if (recipeOpt.isEmpty()) {
            visiting.remove(item);
            return 0;
        }

        RecipeNode recipeToFollow = recipeOpt.get();

        int maxIngredientDepth = 0;
        for (IngredientSlot slot : recipeToFollow.getIngredients()) {
            int slotDepth = calculateSlotDepth(slot, new HashSet<>(visiting));
            maxIngredientDepth = Math.max(maxIngredientDepth, slotDepth);
        }

        visiting.remove(item);

        int finalDepth = 1 + maxIngredientDepth;
        return Math.min(finalDepth, ComplexityConfig.MAX_DEPTH.get());
    }

    private int calculateSlotDepth(IngredientSlot slot, Set<Item> visiting) {
        if (slot.getVariants().isEmpty()) {
            return 0;
        }
        return slot.getVariants().stream()
                .mapToInt(variant -> calculateDepth(variant, visiting))
                .max()
                .orElse(0);
    }
}