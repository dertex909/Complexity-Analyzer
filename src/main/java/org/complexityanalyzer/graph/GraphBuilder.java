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

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
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
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.harvest.inspector.ItemStackIdentity;
import org.complexityanalyzer.harvest.engine.RegistryHarvestService;
import org.complexityanalyzer.mixin.SmithingTransformRecipeAccessor;
import org.complexityanalyzer.util.ComplexityComparators;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.world.item.Items.AIR;

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
                ComplexityAnalyzer.LOGGER.info("Loaded recipe graph from cache: {} recipes (scan skipped).", cached.getTotalRecipeCount());
                return cached;
            }
        }

        var allRecipes = new ObjectArrayList<>(recipeManager.getRecipes());
        var processedCount = new AtomicInteger(0);
        var skippedCount = new AtomicInteger(0);
        var processedNodes = new ConcurrentLinkedQueue<RecipeNode>();

        int recipeCount = allRecipes.size();

        if (recipeCount == 1) {
            processSingleRecipe(allRecipes.getFirst(), level, processedNodes, processedCount, skippedCount);
        } else if (recipeCount > 1) {
            ThreadPoolManager.getInstance().invokeParallel(() -> new ProcessRecipesTask(allRecipes, level, processedNodes, processedCount, skippedCount, 0, recipeCount).invoke());
        }

        var graph = new RecipeGraph();

        RecipeNode node;
        while ((node = processedNodes.poll()) != null) graph.addRecipe(node);

        ComplexityAnalyzer.LOGGER.info("Recipe graph built: {} recipes processed, {} skipped", processedCount.get(), skippedCount.get());
        new RegistryHarvestService().harvestInto(graph, level, level.getServer().getWorldPath(LevelResource.ROOT));

        if (cacheEnabled && cacheFile != null) RecipeGraphCache.INSTANCE.save(graph, cacheFile, fingerprint, level);
        return graph;
    }

    private static void processSingleRecipe(RecipeHolder<?> holder, Level level,
                                            ConcurrentLinkedQueue<RecipeNode> processedNodes,
                                            AtomicInteger processedCount, AtomicInteger skippedCount) {
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
    }

    private static RecipeNode buildSmithingNode(SmithingTransformRecipe recipe, ItemStack resultStack) {
        var resultItem = resultStack.getItem();
        var accessor = (SmithingTransformRecipeAccessor) recipe;
        var template = accessor.getTemplate();
        var base = accessor.getBase();
        var addition = accessor.getAddition();

        if (template == null || base == null || addition == null) return null;

        var builder = new RecipeNode.Builder(resultItem).recipeType(RecipeType.SMITHING).resultCount(1).rawRecipe();
        builder.itemOutputs(ObjectArrayList.of(ItemStackCanonicalizer.canonicalize(resultStack)));

        addSmithingIngredient(builder, template);
        addSmithingIngredient(builder, base);
        addSmithingIngredient(builder, addition);

        return builder.build();
    }

    private static void addSmithingIngredient(RecipeNode.Builder builder, Ingredient ingredient) {
        if (ingredient != null && !ingredient.isEmpty()) {
            var variants = extractVariants(ingredient);
            if (!variants.isEmpty()) builder.addIngredient(variants, 1);
        }
    }

    private static ObjectList<ItemStack> extractVariants(Ingredient ingredient) {
        var items = new ObjectArrayList<ItemStack>();
        if (ingredient == null || ingredient.isEmpty()) return items;
        var stacks = ingredient.getItems();
        for (var stack : stacks) {
            if (stack == null || stack.isEmpty() || stack.getItem() == AIR) continue;
            if (!containsSameStackData(items, stack)) items.add(ItemStackCanonicalizer.canonicalize(stack));
        }
        return items;
    }

    private static RecipeNode buildNode(Recipe<?> recipe, Level level) {
        var resultStack = recipe.getResultItem(level.registryAccess());
        if (resultStack.isEmpty()) return null;

        var resultItem = resultStack.getItem();
        if (recipe instanceof SmithingTransformRecipe smithing) return buildSmithingNode(smithing, resultStack);
        var ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;

        if (isUnprocessable(recipe, resultItem, ingredients)) return null;

        var builder = new RecipeNode.Builder(resultItem)
                .recipeType(recipe.getType())
                .resultCount(resultStack.getCount())
                .rawRecipe();
        builder.itemOutputs(ObjectArrayList.of(ItemStackCanonicalizer.canonicalize(resultStack)));

        var merged = new Object2IntLinkedOpenHashMap<ObjectList<ItemStack>>();
        int limit = ComplexityConfig.MAX_INGREDIENT_VARIANTS.get();
        var comparator = ComplexityComparators.createDeepItemStackComparator(level.registryAccess());

        for (var ingredient : ingredients) {
            var variants = extractVariants(ingredient);
            if (!variants.isEmpty()) {
                if (variants.size() > 1) variants.sort(comparator);
                if (variants.size() > limit) variants.removeElements(limit, variants.size());
                merged.addTo(variants, 1);
            }
        }

        for (var entry : Object2IntMaps.fastIterable(merged)) {
            builder.addIngredient(entry.getKey(), entry.getIntValue());
        }
        return builder.build();
    }

    private static boolean containsSameStackData(ObjectList<ItemStack> stacks, ItemStack candidate) {
        for (var stack : stacks) if (ItemStackIdentity.sameItemData(stack, candidate)) return true;
        return false;
    }

    private static boolean isUnprocessable(Recipe<?> recipe, Item resultItem, Iterable<Ingredient> ingredients) {
        if (resultItem.getDefaultInstance().isDamageableItem()) for (var ing : ingredients) {
            var stacks = ing.getItems();
            for (var stack : stacks) if (stack.getItem() == resultItem) return true;
        }
        return recipe instanceof TippedArrowRecipe || recipe instanceof MapCloningRecipe || recipe instanceof ArmorDyeRecipe || recipe instanceof BannerDuplicateRecipe;
    }

    private static final class ProcessRecipesTask extends RecursiveAction {
        private final ObjectArrayList<RecipeHolder<?>> allRecipes;
        private final Level level;
        private final ConcurrentLinkedQueue<RecipeNode> processedNodes;
        private final AtomicInteger processedCount;
        private final AtomicInteger skippedCount;
        private final int start;
        private final int end;

        ProcessRecipesTask(ObjectArrayList<RecipeHolder<?>> allRecipes, Level level,
                           ConcurrentLinkedQueue<RecipeNode> processedNodes, AtomicInteger processedCount,
                           AtomicInteger skippedCount, int start, int end) {
            this.allRecipes = allRecipes;
            this.level = level;
            this.processedNodes = processedNodes;
            this.processedCount = processedCount;
            this.skippedCount = skippedCount;
            this.start = start;
            this.end = end;
        }

        @Override
        protected void compute() {
            int length = end - start;
            if (length <= 16) {
                for (int i = start; i < end; i++) {
                    processSingleRecipe(allRecipes.get(i), level, processedNodes, processedCount, skippedCount);
                }
            } else {
                int mid = (start + end) >>> 1;
                invokeAll(new ProcessRecipesTask(allRecipes, level, processedNodes, processedCount, skippedCount, start, mid), new ProcessRecipesTask(allRecipes, level, processedNodes, processedCount, skippedCount, mid, end));
            }
        }
    }
}