package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.world.item.Items.AIR;
import static net.minecraft.world.level.material.Fluids.EMPTY;

public class RecipeGraph {
    private final ConcurrentHashMap<Item, ObjectList<RecipeNode>> recipesByItem;
    private final ConcurrentHashMap<Item, ObjectList<Item>> usageMap;
    private final ConcurrentHashMap<Item, RecipeNode> bestRecipeCache;
    private final ConcurrentHashMap<ResourceLocation, ObjectList<RecipeNode>> recipesByFluid;
    private final ConcurrentHashMap<Fluid, ObjectList<RecipeNode>> recipesByFluidOutput;
    private final ConcurrentHashMap<Fluid, ObjectList<Item>> fluidUsageMap;
    private final ConcurrentHashMap<Integer, RecipeNode> allRecipesMap;
    private final AtomicInteger recipeCounter;

    public RecipeGraph() {
        this.recipesByItem = new ConcurrentHashMap<>(16384);
        this.usageMap = new ConcurrentHashMap<>(16384);
        this.bestRecipeCache = new ConcurrentHashMap<>(16384);
        this.recipesByFluid = new ConcurrentHashMap<>(1024);
        this.recipesByFluidOutput = new ConcurrentHashMap<>(1024);
        this.fluidUsageMap = new ConcurrentHashMap<>(1024);
        this.allRecipesMap = new ConcurrentHashMap<>(16384);
        this.recipeCounter = new AtomicInteger(0);
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
        int size = recipeCounter.get();
        var list = new ObjectArrayList<RecipeNode>(size);
        for (int i = 0; i < size; i++) {
            var node = allRecipesMap.get(i);
            if (node != null) list.add(node);
        }
        return list;
    }

    private void appendToAllRecipes(RecipeNode node) {
        int idx = recipeCounter.getAndIncrement();
        node.setListIndex(idx);
        allRecipesMap.put(idx, node);
    }

    private void replaceOrAddInAllRecipes(RecipeNode existing, RecipeNode node) {
        int allIdx = existing.getListIndex();
        if (allIdx != -1) {
            node.setListIndex(allIdx);
            allRecipesMap.put(allIdx, node);
        } else {
            int idx = recipeCounter.getAndIncrement();
            node.setListIndex(idx);
            allRecipesMap.put(idx, node);
        }
    }

    public void addRecipe(RecipeNode node) {
        var result = node.getResultItem();
        if (result == AIR && !node.isPlaceholder() && node.getFluidOutputs().isEmpty() && node.getChemicalOutputs().isEmpty()) {
            return;
        }

        boolean[] isDuplicate = {false};
        if (result != AIR) {
            recipesByItem.compute(result, (item, list) -> {
                if (list == null) list = new ObjectArrayList<>();
                int dupIdx = -1;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).equals(node)) {
                        dupIdx = i;
                        break;
                    }
                }
                if (dupIdx != -1) {
                    isDuplicate[0] = true;
                    var existing = list.get(dupIdx);
                    boolean nodeIsBetter = node.getFluidIngredients().size() > existing.getFluidIngredients().size()
                            || node.getItemOutputs().size() > existing.getItemOutputs().size()
                            || node.getFluidOutputs().size() > existing.getFluidOutputs().size();
                    if (nodeIsBetter) {
                        var newList = new ObjectArrayList<>(list);
                        newList.set(dupIdx, node);
                        replaceOrAddInAllRecipes(existing, node);
                        for (var slot : node.getFluidIngredients()) {
                            for (var variant : slot.getFluidVariants()) {
                                var normalized = normalizeFluid(variant);
                                if (normalized != EMPTY) addFluidUsage(normalized, result);
                            }
                        }
                        for (var stack : node.getFluidOutputs()) {
                            var normalized = normalizeFluid(stack.getFluid());
                            if (normalized != EMPTY) recipesByFluidOutput.compute(normalized, (f, foList) -> {
                                if (foList == null) foList = new ObjectArrayList<>();
                                if (!foList.contains(node)) {
                                    var newFoList = new ObjectArrayList<>(foList);
                                    newFoList.add(node);
                                    return newFoList;
                                }
                                return foList;
                            });
                        }
                        bestRecipeCache.remove(result);
                        return newList;
                    }
                    return list;
                } else {
                    var newList = new ObjectArrayList<>(list);
                    newList.add(node);
                    return newList;
                }
            });
        }

        if (isDuplicate[0]) return;

        if (node.isPlaceholder() && node.getPlaceholderId() != null && !node.getPlaceholderId().isEmpty()) try {
            var fluidId = ResourceLocation.parse(node.getPlaceholderId());
            boolean[] isPlaceholderDuplicate = {false};
            recipesByFluid.compute(fluidId, (id, list) -> {
                if (list == null) list = new ObjectArrayList<>();
                int dupIdx = -1;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).equals(node)) {
                        dupIdx = i;
                        break;
                    }
                }
                if (dupIdx != -1) {
                    isPlaceholderDuplicate[0] = true;
                    var existing = list.get(dupIdx);
                    boolean nodeIsBetter = node.getFluidOutputs().size() > existing.getFluidOutputs().size();
                    if (nodeIsBetter) {
                        var newList = new ObjectArrayList<>(list);
                        newList.set(dupIdx, node);
                        replaceOrAddInAllRecipes(existing, node);
                        for (var stack : node.getFluidOutputs()) {
                            var normalized = normalizeFluid(stack.getFluid());
                            if (normalized != EMPTY) recipesByFluidOutput.compute(normalized, (f, foList) -> {
                                if (foList == null) foList = new ObjectArrayList<>();
                                if (!foList.contains(node)) {
                                    ObjectList<RecipeNode> newFoList = new ObjectArrayList<>(foList);
                                    newFoList.add(node);
                                    return newFoList;
                                    }
                                return foList;
                            });
                        }
                        return newList;
                    }
                    return list;
                } else {
                    var newList = new ObjectArrayList<>(list);
                    newList.add(node);
                    return newList;
                }
            });
            if (isPlaceholderDuplicate[0]) return;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("Invalid placeholder ID: {}", node.getPlaceholderId());
        }

        appendToAllRecipes(node);

        for (var slot : node.getIngredients()) {
            for (var ingredientStack : slot.getVariants()) {
                var ingredient = ingredientStack.getItem();
                if (result != AIR) addItemUsage(ingredient, result);
            }
        }

        for (var slot : node.getFluidIngredients()) {
            for (var variant : slot.getFluidVariants()) {
                var normalized = normalizeFluid(variant);
                if (normalized != EMPTY && result != AIR) addFluidUsage(normalized, result);
            }
        }

        for (var stack : node.getFluidOutputs()) {
            var normalized = normalizeFluid(stack.getFluid());
            if (normalized != EMPTY) recipesByFluidOutput.compute(normalized, (f, foList) -> {
                if (foList == null) foList = new ObjectArrayList<>();
                if (!foList.contains(node)) {
                    var newFoList = new ObjectArrayList<>(foList);
                    newFoList.add(node);
                    return newFoList;
                }
                return foList;
            });
        }

        bestRecipeCache.remove(result);
    }

    private void addItemUsage(Item ingredient, Item result) {
        usageMap.compute(ingredient, (k, list) -> {
            if (list == null) {
                var newList = new ObjectArrayList<Item>(2);
                newList.add(result);
                return newList;
            }
            if (!list.contains(result)) {
                var newList = new ObjectArrayList<Item>(list.size() + 1);
                newList.addAll(list);
                newList.add(result);
                return newList;
            }
            return list;
        });
    }

    private void addFluidUsage(Fluid fluid, Item result) {
        fluidUsageMap.compute(fluid, (k, list) -> {
            if (list == null) {
                var newList = new ObjectArrayList<Item>(2);
                newList.add(result);
                return newList;
            }
            if (!list.contains(result)) {
                var newList = new ObjectArrayList<Item>(list.size() + 1);
                newList.addAll(list);
                newList.add(result);
                return newList;
            }
            return list;
        });
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

    public ObjectList<Item> getItemsUsingFluid(Fluid fluid) {
        return fluidUsageMap.getOrDefault(normalizeFluid(fluid), ObjectLists.emptyList());
    }

    public int getUsageCount(Item item) {
        var users = usageMap.get(item);
        return users != null ? users.size() : 0;
    }

    public ObjectList<Item> getItemsUsingIngredient(Item ingredient) {
        return usageMap.getOrDefault(ingredient, ObjectLists.emptyList());
    }

    public ReferenceSet<Item> getAllItems() {
        return new ReferenceOpenHashSet<>(recipesByItem.keySet());
    }

    public int getTotalRecipeCount() {
        return recipeCounter.get();
    }

    public void clear() {
        recipesByItem.clear();
        usageMap.clear();
        bestRecipeCache.clear();
        recipesByFluid.clear();
        recipesByFluidOutput.clear();
        fluidUsageMap.clear();
        allRecipesMap.clear();
        recipeCounter.set(0);
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

    public record GraphStats(int itemsWithRecipes, int totalRecipes, int itemsUsedAsIngredients) {
        @Override
        public @NotNull String toString() {
            return String.format("GraphStats{items=%d, recipes=%d, ingredients=%d}", itemsWithRecipes, totalRecipes, itemsUsedAsIngredients);
        }
    }
}