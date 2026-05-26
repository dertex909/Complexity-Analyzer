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

package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.NotNull;

public class RecipeGraph {
    private final Reference2ObjectMap<Item, ObjectList<RecipeNode>> recipesByItem;
    private final Reference2ObjectMap<Item, ReferenceSet<Item>> usageMap;
    private final Reference2ObjectMap<Item, RecipeNode> bestRecipeCache;
    private final Object2ObjectMap<ResourceLocation, ObjectList<RecipeNode>> recipesByFluid;
    private final Reference2ObjectMap<Fluid, ObjectList<RecipeNode>> recipesByFluidOutput;
    private final Reference2ObjectMap<Fluid, ReferenceSet<Item>> fluidUsageMap;
    private final ObjectList<RecipeNode> allRecipesList;

    public RecipeGraph() {
        this.recipesByItem = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        this.usageMap = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        this.bestRecipeCache = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        this.recipesByFluid = Object2ObjectMaps.synchronize(new Object2ObjectOpenHashMap<>());
        this.recipesByFluidOutput = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        this.fluidUsageMap = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
        this.allRecipesList = ObjectLists.synchronize(new ObjectArrayList<>());
    }

    public ObjectList<RecipeNode> getAllRecipes() {
        return new ObjectArrayList<>(allRecipesList);
    }

    public void addRecipe(RecipeNode node) {
        Item result = node.getResultItem();
        if (result == Items.AIR && !node.isPlaceholder() && node.getFluidOutputs().isEmpty() && node.getChemicalOutputs().isEmpty()) {
            return;
        }

        if (result != Items.AIR) {
            var list = recipesByItem.computeIfAbsent(result, k -> new ObjectArrayList<>());
            int dupIdx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).equals(node)) {
                    dupIdx = i;
                    break;
                }
            }
            if (dupIdx != -1) {
                RecipeNode existing = list.get(dupIdx);
                boolean nodeIsBetter = node.getFluidIngredients().size() > existing.getFluidIngredients().size()
                        || node.getItemOutputs().size() > existing.getItemOutputs().size()
                        || node.getFluidOutputs().size() > existing.getFluidOutputs().size();
                if (nodeIsBetter) {
                    list.set(dupIdx, node);
                    int allIdx = allRecipesList.indexOf(existing);
                    if (allIdx != -1) {
                        allRecipesList.set(allIdx, node);
                    } else {
                        allRecipesList.add(node);
                    }
                    for (var slot : node.getFluidIngredients()) {
                        for (var variant : slot.getFluidVariants()) {
                            Fluid normalized = normalizeFluid(variant);
                            if (normalized != Fluids.EMPTY) {
                                fluidUsageMap.computeIfAbsent(normalized, k -> ReferenceSets.synchronize(new ReferenceOpenHashSet<>())).add(result);
                            }
                        }
                    }
                    for (var stack : node.getFluidOutputs()) {
                        Fluid normalized = normalizeFluid(stack.getFluid());
                        if (normalized != Fluids.EMPTY) {
                            var foList = recipesByFluidOutput.computeIfAbsent(normalized, k -> new ObjectArrayList<>());
                            if (!foList.contains(node)) foList.add(node);
                        }
                    }
                    bestRecipeCache.remove(result);
                }
                return;
            } else {
                list.add(node);
            }
        }

        if (node.isPlaceholder() && node.getPlaceholderId() != null && !node.getPlaceholderId().isEmpty()) try {
            var fluidId = ResourceLocation.parse(node.getPlaceholderId());
            var list = recipesByFluid.computeIfAbsent(fluidId, k -> new ObjectArrayList<>());
            int dupIdx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).equals(node)) {
                    dupIdx = i;
                    break;
                }
            }
            if (dupIdx != -1) {
                RecipeNode existing = list.get(dupIdx);
                boolean nodeIsBetter = node.getFluidOutputs().size() > existing.getFluidOutputs().size();
                if (nodeIsBetter) {
                    list.set(dupIdx, node);
                    int allIdx = allRecipesList.indexOf(existing);
                    if (allIdx != -1) {
                        allRecipesList.set(allIdx, node);
                    } else {
                        allRecipesList.add(node);
                    }
                    for (var stack : node.getFluidOutputs()) {
                        Fluid normalized = normalizeFluid(stack.getFluid());
                        if (normalized != Fluids.EMPTY) {
                            var foList = recipesByFluidOutput.computeIfAbsent(normalized, k -> new ObjectArrayList<>());
                            if (!foList.contains(node)) foList.add(node);
                        }
                    }
                }
                return;
            } else {
                list.add(node);
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("Invalid placeholder ID: {}", node.getPlaceholderId());
        }

        allRecipesList.add(node);

        for (var slot : node.getIngredients()) {
            for (var ingredient : slot.getVariants()) {
                if (result != Items.AIR) {
                    usageMap.computeIfAbsent(ingredient, k -> ReferenceSets.synchronize(new ReferenceOpenHashSet<>())).add(result);
                }
            }
        }

        for (var slot : node.getFluidIngredients()) {
            for (var variant : slot.getFluidVariants()) {
                Fluid normalized = normalizeFluid(variant);
                if (normalized != Fluids.EMPTY) if (result != Items.AIR) {
                    fluidUsageMap.computeIfAbsent(normalized, k -> ReferenceSets.synchronize(new ReferenceOpenHashSet<>())).add(result);
                }
            }
        }

        for (var stack : node.getFluidOutputs()) {
            Fluid normalized = normalizeFluid(stack.getFluid());
            if (normalized != Fluids.EMPTY) {
                var foList = recipesByFluidOutput.computeIfAbsent(normalized, k -> new ObjectArrayList<>());
                if (!foList.contains(node)) foList.add(node);
            }
        }

        bestRecipeCache.remove(result);
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

        for (RecipeNode r : recipes) {
            if (r.getCategory() == RecipeCategory.PRIMARY) if (best == null || r.getPriority() > best.getPriority())
                best = r;
        }
        if (best != null) return best;

        for (RecipeNode r : recipes) {
            var cat = r.getCategory();
            if (cat != RecipeCategory.STORAGE_DECOMPRESSION && cat != RecipeCategory.RECYCLING && cat !=
                    RecipeCategory.UNPROCESSABLE) if (best == null || r.getPriority() > best.getPriority()) best = r;
        }
        if (best != null) return best;

        for (RecipeNode r : recipes) if (best == null || r.getPriority() > best.getPriority()) best = r;

        return best != null ? best : recipes.getFirst();
    }

    public int reclassifyRecipesBasedOnComplexity(Reference2DoubleMap<Item> complexities) {
        int reclassified = 0;

        var oresTag = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ores"));
        var rawMaterialsTag = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:raw_materials"));
        var storageBlocksTag = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:storage_blocks"));
        var dusts = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:dusts"));
        var crushed = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:crushed"));

        for (var item : getAllItems()) {
            if (!hasRecipe(item)) continue;

            var recipes = getRecipes(item);
            var resultComplexity = complexities.getOrDefault(item, Double.POSITIVE_INFINITY);

            if (Double.isInfinite(resultComplexity)) continue;

            for (var recipe : recipes) {
                if (recipe.getCategory() != RecipeCategory.PRIMARY) continue;

                var recipeType = recipe.getRecipeType().toString();
                if (!isVanillaRecipeType(recipeType)) continue;

                var isReverseRecipe = false;
                var hasRawMaterial = false;

                for (var slot : recipe.getIngredients()) {
                    for (var ingredient : slot.getVariants()) {
                        var ingredientStack = new ItemStack(ingredient);

                        if (ingredientStack.is(oresTag) || ingredientStack.is(rawMaterialsTag)
                                || ingredientStack.is(dusts) || ingredientStack.is(crushed)
                                || isRawStorageBlock(ingredientStack, storageBlocksTag)) {
                            hasRawMaterial = true;
                            break;
                        }

                        var ingredientComplexity = complexities.getOrDefault(ingredient, Double.POSITIVE_INFINITY);
                        if (Double.isInfinite(ingredientComplexity)) continue;

                        if (resultComplexity < ingredientComplexity * 0.95) isReverseRecipe = true;
                    }
                    if (hasRawMaterial) break;
                }

                if (hasRawMaterial) continue;

                if (isReverseRecipe) {
                    recipe.setCategory(RecipeCategory.PROCESSING);
                    reclassified++;
                }
            }
        }

        return reclassified;
    }

    private static boolean isVanillaRecipeType(String recipeType) {
        return recipeType.equals("minecraft:crafting") || recipeType.equals("crafting") ||
                recipeType.equals("minecraft:smelting") || recipeType.equals("smelting") ||
                recipeType.equals("minecraft:blasting") || recipeType.equals("blasting") ||
                recipeType.equals("minecraft:smoking") || recipeType.equals("smoking") ||
                recipeType.equals("minecraft:campfire_cooking") || recipeType.equals("campfire_cooking") ||
                recipeType.equals("minecraft:stonecutting") || recipeType.equals("stonecutting") ||
                recipeType.equals("minecraft:smithing") || recipeType.equals("smithing");
    }

    private boolean isRawStorageBlock(ItemStack stack, TagKey<Item> storageBlocksTag) {
        if (!stack.is(storageBlocksTag)) return false;
        var itemId = GameRegistryManager.getItemId(stack.getItem()).toString();
        return itemId.contains("raw_") || itemId.contains("crude_");
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

    public ReferenceSet<Item> getItemsUsingFluid(Fluid fluid) {
        return fluidUsageMap.getOrDefault(normalizeFluid(fluid), ReferenceSets.emptySet());
    }

    public int getUsageCount(Item item) {
        var users = usageMap.get(item);
        return users != null ? users.size() : 0;
    }

    public ReferenceSet<Item> getItemsUsingIngredient(Item ingredient) {
        return usageMap.getOrDefault(ingredient, ReferenceSets.emptySet());
    }

    public ReferenceSet<Item> getAllItems() {
        return recipesByItem.keySet();
    }

    public int getTotalRecipeCount() {
        return allRecipesList.size();
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

    public void clear() {
        recipesByItem.clear();
        usageMap.clear();
        bestRecipeCache.clear();
        recipesByFluidOutput.clear();
        fluidUsageMap.clear();
        allRecipesList.clear();
        ComplexityAnalyzer.LOGGER.info("Recipe graph cleared");
    }

    public GraphStats getStats() {
        return new GraphStats(
                recipesByItem.size(),
                getTotalRecipeCount(),
                usageMap.size()
        );
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