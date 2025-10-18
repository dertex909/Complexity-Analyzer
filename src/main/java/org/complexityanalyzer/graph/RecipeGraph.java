package org.complexityanalyzer.graph;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

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

        return recipes.stream()
                .max(Comparator.comparingInt(RecipeNode::getPriority))
                .orElse(recipes.getFirst());
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