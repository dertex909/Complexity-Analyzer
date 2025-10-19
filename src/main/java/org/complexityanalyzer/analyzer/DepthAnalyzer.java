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

    private static final int CYCLE_DEPTH = Integer.MAX_VALUE; // Используем большое число, а не -1
    private static final int IN_PROGRESS = -999; // Специальный маркер, что расчет уже идет

    public DepthAnalyzer(RecipeGraph graph, SourceManager ignoredSourceManager) {
        this.graph = graph;
        this.cache = new ConcurrentHashMap<>();
    }

    public int getDepth(Item item) {
        // Мы больше не используем computeIfAbsent, чтобы избежать deadlock
        Integer cachedDepth = cache.get(item);
        if (cachedDepth != null) {
            return cachedDepth;
        }
        // Запускаем расчет
        return calculateDepth(item);
    }

    private int calculateDepth(Item item) {
        // Проверяем кеш еще раз, на случай если другой поток уже начал считать
        Integer cached = cache.get(item);
        if (cached != null) {
            // Если мы наткнулись на маркер "в процессе", значит мы нашли цикл.
            if (cached == IN_PROGRESS) {
                return CYCLE_DEPTH;
            }
            return cached;
        }

        // Ставим маркер, что мы начали расчет для этого предмета
        cache.put(item, IN_PROGRESS);

        Optional<RecipeNode> recipeOpt = getRecipeToFollow(item);
        if (recipeOpt.isEmpty()) {
            // Базовый ресурс, глубина 0
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
            // Убедимся, что мы не переполняем Integer
            long calculatedDepth = 1L + maxIngredientDepth;
            finalDepth = (int) Math.min(calculatedDepth, CYCLE_DEPTH);
        }

        int limitedDepth = Math.min(finalDepth, ComplexityConfig.MAX_DEPTH.get());

        // Записываем финальный результат в кеш
        cache.put(item, limitedDepth);
        return limitedDepth;
    }

    private int calculateSlotDepth(IngredientSlot slot) {
        if (slot.getVariants().isEmpty()) {
            return 0;
        }

        int minDepth = CYCLE_DEPTH;
        for (Item variant : slot.getVariants()) {
            int variantDepth = getDepth(variant); // ВАЖНО: вызываем публичный getDepth, который работает с кешем
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