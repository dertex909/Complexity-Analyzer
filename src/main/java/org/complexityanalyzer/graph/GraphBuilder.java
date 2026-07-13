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

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.RecipeGraphCache;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.harvest.ItemStackIdentity;
import org.complexityanalyzer.harvest.RegistryHarvestService;
import org.complexityanalyzer.mixin.SmithingTransformRecipeAccessor;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class GraphBuilder {

    public static RecipeGraph buildFromWorld(Level level) {
        var recipeManager = level.getRecipeManager();
        var cacheFile = RecipeGraphCache.INSTANCE.file(level.getServer());
        boolean cacheEnabled = ComplexityConfig.ENABLE_CACHE.get();
        RecipeGraphCache.Fingerprint fingerprint = null;
        if (cacheEnabled && cacheFile != null) {
            fingerprint = RecipeGraphCache.INSTANCE.computeFingerprint(recipeManager, level.registryAccess());
            var cached = RecipeGraphCache.INSTANCE.tryLoad(cacheFile, fingerprint, level);
            if (cached != null) {
                ComplexityAnalyzer.LOGGER.info("Loaded recipe graph from cache: {} recipes (scan skipped).",
                        cached.getTotalRecipeCount());
                return cached;
            }
        }

        ComplexityAnalyzer.LOGGER.info("Building recipe graph with advanced classification ({} threads)...",
                ThreadPoolManager.getInstance().getParallelism());

        var graph = new RecipeGraph();
        var allRecipes = new ObjectArrayList<>(recipeManager.getRecipes());

        var processedCount = new AtomicInteger(0);
        var skippedCount = new AtomicInteger(0);
        var processedNodes = new ConcurrentLinkedQueue<RecipeNode>();

        ThreadPoolManager.getInstance().invokeParallel(() -> allRecipes.parallelStream().forEach(holder -> {
            try {
                var node = buildNode(holder.value(), level);
                if (node != null) {
                    processedNodes.add(node);
                    processedCount.incrementAndGet();
                } else {
                    skippedCount.incrementAndGet();
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to process recipe {}: {}", holder.id(), e.getMessage());
                skippedCount.incrementAndGet();
            }
        }));

        for (var node : processedNodes) graph.addRecipe(node);

        ComplexityAnalyzer.LOGGER.info("Recipe graph built: {} recipes processed, {} skipped",
                processedCount.get(), skippedCount.get());

        new RegistryHarvestService().harvestInto(graph, level, level.getServer().getWorldPath(LevelResource.ROOT));

        if (cacheEnabled && cacheFile != null) RecipeGraphCache.INSTANCE.save(graph, cacheFile, fingerprint, level);

        return graph;
    }

    private static RecipeNode buildSmithingNode(SmithingTransformRecipe recipe, ItemStack resultStack) {
        var resultItem = resultStack.getItem();
        var accessor = (SmithingTransformRecipeAccessor) recipe;
        var template = accessor.getTemplate();
        var base = accessor.getBase();
        var addition = accessor.getAddition();

        if (template == null || base == null || addition == null) return null;

        var builder = new RecipeNode.Builder(resultItem).recipeType(RecipeType.SMITHING).resultCount(1).rawRecipe();
        builder.itemOutputs(ObjectArrayList.of(resultStack.copy()));

        if (!template.isEmpty()) {
            var variants = extractVariants(template);
            if (!variants.isEmpty()) builder.addIngredient(variants, 1);
        }

        if (!base.isEmpty()) {
            var variants = extractVariants(base);
            if (!variants.isEmpty()) builder.addIngredient(variants, 1);
        }

        if (!addition.isEmpty()) {
            var variants = extractVariants(addition);
            if (!variants.isEmpty()) builder.addIngredient(variants, 1);
        }

        return builder.build();
    }

    private static ObjectList<ItemStack> extractVariants(Ingredient ingredient) {
        var items = new ObjectArrayList<ItemStack>();
        var stacks = ingredient.getItems();
        for (var stack : stacks) {
            if (stack.isEmpty()) continue;
            if (containsSameStackData(items, stack)) continue;
            items.add(stack.copyWithCount(1));
        }
        return items;
    }

    private static RecipeNode buildNode(Recipe<?> recipe, Level level) {
        var resultStack = recipe.getResultItem(level.registryAccess());
        if (resultStack.isEmpty()) return null;

        var resultItem = resultStack.getItem();
        var ingredients = new ObjectArrayList<>(recipe.getIngredients());

        if (recipe instanceof SmithingTransformRecipe smithing) return buildSmithingNode(smithing, resultStack);
        if (ingredients.isEmpty()) return null;

        if (isUnprocessable(recipe, resultItem, ingredients)) return null;

        var builder = new RecipeNode.Builder(resultItem).recipeType(recipe.getType()).resultCount(resultStack.getCount()).rawRecipe();
        builder.itemOutputs(ObjectArrayList.of(resultStack.copy()));

        var merged = new Object2ObjectLinkedOpenHashMap<ObjectList<ItemStack>, Integer>();
        int limit = ComplexityConfig.MAX_INGREDIENT_VARIANTS.get();
        for (var ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            var variants = new ObjectArrayList<ItemStack>();
            for (var stack : ingredient.getItems()) {
                if (stack.isEmpty()) continue;
                if (!containsSameStackData(variants, stack)) variants.add(stack.copyWithCount(1));
            }
            if (!variants.isEmpty()) {
                variants.sort((a, b) -> {
                    var idA = GameRegistryManager.getItemId(a.getItem());
                    var idB = GameRegistryManager.getItemId(b.getItem());
                    int byId = idA.compareTo(idB);
                    if (byId != 0) return byId;
                    return ItemStackIdentity.dataKey(a, level.registryAccess())
                            .compareTo(ItemStackIdentity.dataKey(b, level.registryAccess()));
                });
                if (variants.size() > limit) variants.removeElements(limit, variants.size());
                var key = new ObjectArrayList<>(variants);
                merged.put(key, merged.getOrDefault(key, 0) + 1);
            }
        }
        for (var entry : merged.entrySet()) {
            builder.addIngredient(new ObjectArrayList<>(entry.getKey()), entry.getValue());
        }
        return builder.build();
    }

    private static boolean containsSameStackData(ObjectList<ItemStack> stacks, ItemStack candidate) {
        for (var stack : stacks) if (ItemStackIdentity.sameItemData(stack, candidate)) return true;
        return false;
    }

    private static boolean isUnprocessable(Recipe<?> recipe, Item resultItem, ObjectList<Ingredient> ingredients) {
        if (new ItemStack(resultItem).isDamageableItem()) for (var ing : ingredients) {
            for (var stack : ing.getItems()) if (stack.getItem() == resultItem) return true;
        }
        return recipe instanceof TippedArrowRecipe || recipe instanceof MapCloningRecipe || recipe instanceof ArmorDyeRecipe || recipe instanceof BannerDuplicateRecipe;
    }
}