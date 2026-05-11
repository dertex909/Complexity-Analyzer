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

package org.complexityanalyzer.compat.jei;

import it.unimi.dsi.fastutil.objects.*;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;
import org.complexityanalyzer.core.GameRegistryManager;

public class JeiRecipeConverter {
    public static ObjectList<RecipeNode> convertAllFromJei(Reference2ObjectMap<RecipeType<?>, ObjectList<?>> recipesByType, Level level) {
        int totalRecipes = 0;
        for (var list : recipesByType.values()) totalRecipes += list.size();

        Reference2ObjectMap<Class<?>, ObjectList<RecipeWithType>> recipesByClass = new Reference2ObjectOpenHashMap<>();

        for (var entry : recipesByType.reference2ObjectEntrySet()) {
            RecipeType<?> jeiType = entry.getKey();
            ObjectList<?> recipes = entry.getValue();
            if (recipes.isEmpty()) continue;

            ResourceLocation jeiTypeId = jeiType.getUid();
            for (Object recipe : recipes) {
                recipesByClass.computeIfAbsent(recipe.getClass(), k -> new ObjectArrayList<>())
                        .add(new RecipeWithType(recipe, jeiTypeId));
            }
        }

        long learnStart = System.currentTimeMillis();
        for (var list : recipesByClass.values()) {
            if (list.isEmpty()) continue;
            Object sampleRecipe = list.getFirst().recipe();
            AdaptiveRecipeConverter.warmupClass(sampleRecipe);
        }
        long learnTime = System.currentTimeMillis() - learnStart;
        if (learnTime > 500) {
            ComplexityAnalyzer.LOGGER.debug("Adapter learning took {}ms for {} classes",
                    learnTime, recipesByClass.size());
        }

        long convertStart = System.currentTimeMillis();
        ObjectList<RecipeWithType> allRecipes = new ObjectArrayList<>(totalRecipes);
        for (var list : recipesByClass.values()) allRecipes.addAll(list);

        ObjectList<RecipeNode> result = new ObjectArrayList<>(AdaptiveRecipeConverter.convertJeiBatch(allRecipes, level));

        long convertTime = System.currentTimeMillis() - convertStart;

        if (convertTime > 1000) {
            ComplexityAnalyzer.LOGGER.info("JEI batch conversion: {}ms for {} recipes ({} nodes)",
                    convertTime, totalRecipes, result.size());
        }

        return result;
    }

    public record RecipeWithType(Object recipe, ResourceLocation jeiTypeId) {
    }

    public static ObjectList<RecipeNode> convertAllFromRecipeManager(Level level) {
        ComplexityAnalyzer.LOGGER.info("Processing recipes from RecipeManager...");

        RecipeManager recipeManager = level.getRecipeManager();
        ObjectList<Recipe<?>> moddedRecipes = new ObjectArrayList<>();
        int skipped = 0;

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            net.minecraft.world.item.crafting.RecipeType<?> mcType = recipe.getType();

            ResourceLocation typeId = GameRegistryManager.getRecipeTypeId(mcType);
            if (typeId == null || typeId.getNamespace().equals("minecraft")) {
                skipped++;
                continue;
            }

            moddedRecipes.add(recipe);
        }

        ComplexityAnalyzer.LOGGER.info("Found {} modded recipes to process (skipped {} vanilla)",
                moddedRecipes.size(), skipped);

        if (moddedRecipes.isEmpty()) {
            return ObjectLists.emptyList();
        }

        ObjectList<RecipeNode> result = AdaptiveRecipeConverter.convertRecipesBatch(moddedRecipes, level);

        ComplexityAnalyzer.LOGGER.info(
                "RecipeManager processing complete: {} converted, {} skipped",
                result.size(), skipped
        );

        return result;
    }

    public static RecipeNode convert(Object recipe, Level level, ResourceLocation jeiTypeId) {
        ObjectList<ItemStack> itemOutputs = AdaptiveRecipeConverter.extractOutputs(recipe, level);
        ObjectList<FluidStack> fluidOutputs = AdaptiveRecipeConverter.extractFluidOutputs(recipe, level);
        ObjectList<AdaptiveRecipeConverter.ChemicalOutput> chemicalOutputs =
                AdaptiveRecipeConverter.extractChemicalOutputs(recipe, level);

        if (itemOutputs.isEmpty() && fluidOutputs.isEmpty() && chemicalOutputs.isEmpty()) return null;

        Item resultItem;
        RecipeNode.Builder builder;

        if (!itemOutputs.isEmpty()) {
            ItemStack primaryOutput = itemOutputs.getFirst();
            resultItem = primaryOutput.getItem();
            builder = new RecipeNode.Builder(resultItem)
                    .resultCount(primaryOutput.getCount());
        } else {
            resultItem = Items.BARRIER;
            builder = new RecipeNode.Builder(resultItem)
                    .isPlaceholder(true)
                    .resultCount(1);

            if (!chemicalOutputs.isEmpty()) {
                builder.placeholderId(chemicalOutputs.getFirst().id().toString());
            } else if (!fluidOutputs.isEmpty()) {
                builder.placeholderId(GameRegistryManager.getFluidId(fluidOutputs.getFirst().getFluid()).toString());
            }
        }

        builder.category(RecipeCategory.JEI_IMPORTED)
                .itemOutputs(itemOutputs)
                .fluidOutputs(fluidOutputs)
                .chemicalOutputs(chemicalOutputs)
                .rawRecipe(recipe)
                .priority(900);

        net.minecraft.world.item.crafting.RecipeType<?> recipeType = AdaptiveRecipeConverter.extractRecipeType(recipe);

        if (recipeType == null && jeiTypeId != null) {
            recipeType = GameRegistryManager.getRecipeType(jeiTypeId);

            if (recipeType == null) {
                final String typeIdStr = jeiTypeId.toString();
                recipeType = new net.minecraft.world.item.crafting.RecipeType<>() {
                    @Override
                    public String toString() {
                        return typeIdStr;
                    }
                };
            }
        }

        if (recipeType == null) return null;

        builder.recipeType(recipeType);

        ObjectList<ObjectList<ItemStack>> itemInputs = AdaptiveRecipeConverter.extractInputs(recipe, level);
        ObjectList<ObjectList<FluidStack>> fluidInputs = AdaptiveRecipeConverter.extractFluidInputs(recipe, level);
        ObjectList<AdaptiveRecipeConverter.ChemicalOutput> chemicalInputs =
                AdaptiveRecipeConverter.extractChemicalInputs(recipe);

        for (ObjectList<ItemStack> inputVariants : itemInputs) {
            if (inputVariants.isEmpty()) continue;

            ObjectList<Item> items = new ObjectArrayList<>();
            for (ItemStack stack : inputVariants) {
                Item item = stack.getItem();
                if (!items.contains(item)) items.add(item);
            }

            int count = inputVariants.getFirst().getCount();
            builder.addIngredient(items, count);
        }

        for (ObjectList<FluidStack> inputVariants : fluidInputs) {
            if (inputVariants.isEmpty()) continue;

            ObjectList<net.minecraft.world.level.material.Fluid> fluids = new ObjectArrayList<>();
            for (FluidStack stack : inputVariants) {
                net.minecraft.world.level.material.Fluid fluid = stack.getFluid();
                if (!fluids.contains(fluid)) fluids.add(fluid);
            }

            int amount = inputVariants.getFirst().getAmount();
            builder.addFluidIngredient(fluids, amount);
        }

        for (AdaptiveRecipeConverter.ChemicalOutput chemInput : chemicalInputs) {
            builder.addChemicalIngredient(chemInput.id(), (int) chemInput.amount());
        }

        RecipeNode node = builder.build();

        if (node.getIngredients().isEmpty() &&
                node.getFluidIngredients().isEmpty() &&
                node.getChemicalIngredients().isEmpty()) {
            return null;
        }

        return node;
    }
}