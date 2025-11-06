/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.graph;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RecipeGraph {
    private final Map<Item, List<RecipeNode>> recipesByItem;

    private final Map<Item, Set<Item>> usageMap;

    private final Map<Item, RecipeNode> bestRecipeCache;

    public RecipeGraph() {
        this.recipesByItem = new ConcurrentHashMap<>();
        this.usageMap = new ConcurrentHashMap<>();
        this.bestRecipeCache = new ConcurrentHashMap<>();
    }

    public Collection<RecipeNode> getAllRecipes() {
        return recipesByItem.values().stream().flatMap(List::stream).toList();
    }

    public void addRecipe(RecipeNode node) {
        Item result = node.getResultItem();

        recipesByItem.computeIfAbsent(result, k -> new ArrayList<>()).add(node);

        for (IngredientSlot slot : node.getIngredients()) {
            for (Item ingredient : slot.getVariants()) {
                usageMap.computeIfAbsent(ingredient, k -> new HashSet<>()).add(result);
            }
        }

        bestRecipeCache.remove(result);
    }

    public List<RecipeNode> getRecipes(Item item) {
        return recipesByItem.getOrDefault(item, Collections.emptyList());
    }

    public RecipeNode getBestRecipe(Item item) {
        return bestRecipeCache.computeIfAbsent(item, this::findBestRecipe);
    }

    private RecipeNode findBestRecipe(Item item) {
        List<RecipeNode> recipes = getRecipes(item);

        if (recipes.isEmpty()) {
            return RecipeNode.empty(item);
        }

        if (recipes.size() == 1) {
            return recipes.getFirst();
        }

        List<RecipeNode> primaryRecipes = recipes.stream()
                .filter(r -> r.getCategory() == RecipeCategory.PRIMARY)
                .toList();

        if (!primaryRecipes.isEmpty()) {
            return primaryRecipes.stream()
                    .max(Comparator.comparingInt(RecipeNode::getPriority))
                    .orElse(primaryRecipes.getFirst());
        }

        List<RecipeNode> goodRecipes = recipes.stream()
                .filter(r -> r.getCategory() != RecipeCategory.STORAGE_DECOMPRESSION
                        && r.getCategory() != RecipeCategory.RECYCLING
                        && r.getCategory() != RecipeCategory.UNPROCESSABLE)
                .toList();

        if (!goodRecipes.isEmpty()) {
            return goodRecipes.stream()
                    .max(Comparator.comparingInt(RecipeNode::getPriority))
                    .orElse(goodRecipes.getFirst());
        }

        return recipes.stream()
                .max(Comparator.comparingInt(RecipeNode::getPriority))
                .orElse(recipes.getFirst());
    }

    public int reclassifyRecipesBasedOnComplexity(Map<Item, Double> complexities) {
        int reclassified = 0;

        TagKey<Item> oresTag = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ores"));
        TagKey<Item> rawMaterialsTag = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:raw_materials"));
        TagKey<Item> storageBlocksTag = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:storage_blocks"));

        for (Item item : getAllItems()) {
            if (!hasRecipe(item)) continue;

            List<RecipeNode> recipes = getRecipes(item);
            Double resultComplexity = complexities.get(item);

            if (resultComplexity == null || Double.isInfinite(resultComplexity)) {
                continue;
            }

            for (RecipeNode recipe : recipes) {
                if (recipe.getCategory() != RecipeCategory.PRIMARY) {
                    continue;
                }

                boolean isReverseRecipe = false;
                boolean hasRawMaterial = false;

                for (IngredientSlot slot : recipe.getIngredients()) {
                    for (Item ingredient : slot.getVariants()) {
                        ItemStack ingredientStack = new ItemStack(ingredient);

                        if (ingredientStack.is(oresTag) ||
                                ingredientStack.is(rawMaterialsTag) ||
                                isRawStorageBlock(ingredientStack, storageBlocksTag)) {
                            hasRawMaterial = true;
                            break;
                        }

                        Double ingredientComplexity = complexities.get(ingredient);
                        if (ingredientComplexity == null || Double.isInfinite(ingredientComplexity)) {
                            continue;
                        }

                        if (resultComplexity < ingredientComplexity * 0.95) {
                            isReverseRecipe = true;
                        }
                    }
                    if (hasRawMaterial) break;
                }

                if (hasRawMaterial) {
                    continue;
                }

                if (isReverseRecipe) {
                    recipe.setCategory(RecipeCategory.PROCESSING);
                    reclassified++;
                }
            }
        }

        return reclassified;
    }

    private boolean isRawStorageBlock(ItemStack stack, TagKey<Item> storageBlocksTag) {
        if (!stack.is(storageBlocksTag)) {
            return false;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return itemId.contains("raw_") || itemId.contains("crude_");
    }

    public boolean hasRecipe(Item item) {
        List<RecipeNode> recipes = recipesByItem.get(item);
        return recipes != null && !recipes.isEmpty();
    }

    public int getRecipeCount(Item item) {
        return recipesByItem.getOrDefault(item, Collections.emptyList()).size();
    }

    public int getUsageCount(Item item) {
        Set<Item> users = usageMap.get(item);
        return users != null ? users.size() : 0;
    }

    public Set<Item> getItemsUsingIngredient(Item ingredient) {
        return usageMap.getOrDefault(ingredient, Collections.emptySet());
    }

    public Set<Item> getAllItems() {
        return recipesByItem.keySet();
    }

    public int getTotalRecipeCount() {
        return recipesByItem.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    public void clear() {
        recipesByItem.clear();
        usageMap.clear();
        bestRecipeCache.clear();
        ComplexityAnalyzer.LOGGER.info("Recipe graph cleared");
    }

    public GraphStats getStats() {
        return new GraphStats(
                recipesByItem.size(),
                getTotalRecipeCount(),
                usageMap.size()
        );
    }

    public Set<Item> getCorpus() {
        Set<Item> allItems = new HashSet<>(recipesByItem.keySet());
        allItems.addAll(usageMap.keySet());
        return allItems;
    }

    public record GraphStats(
            int itemsWithRecipes,
            int totalRecipes,
            int itemsUsedAsIngredients
    ) {
        @Override
        public @NotNull String toString() {
            return String.format("GraphStats{items=%d, recipes=%d, ingredients=%d}",
                    itemsWithRecipes, totalRecipes, itemsUsedAsIngredients);
        }
    }
}