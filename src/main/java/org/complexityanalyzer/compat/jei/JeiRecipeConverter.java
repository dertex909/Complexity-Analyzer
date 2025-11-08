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

package org.complexityanalyzer.compat.jei;

import mezz.jei.api.recipe.RecipeType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class JeiRecipeConverter {

    public static List<RecipeNode> convertAll(Map<RecipeType<?>, List<?>> recipesByType, Level level) {
        List<RecipeNode> result = new ArrayList<>();

        for (Map.Entry<RecipeType<?>, List<?>> entry : recipesByType.entrySet()) {
            RecipeType<?> type = entry.getKey();
            List<?> recipes = entry.getValue();

            int failed = 0;

            for (Object recipe : recipes) {
                try {
                    RecipeNode node = convert(recipe, level);
                    if (node != null) {
                        result.add(node);
                    } else {
                        failed++;
                        if (failed <= 3) {
                            String className = recipe.getClass().getSimpleName();
                            ComplexityAnalyzer.LOGGER.debug("    Failed to convert recipe: {} (type: {})",
                                    className, type.getUid());
                        }
                    }
                } catch (Exception e) {
                    failed++;
                    if (failed <= 3) {
                        ComplexityAnalyzer.LOGGER.debug("    Exception converting recipe: {}", e.getMessage());
                    }
                }
            }
        }

        return result;
    }

    private static RecipeNode convert(Object recipe, Level level) {
        List<ItemStack> itemOutputs = AdaptiveRecipeConverter.extractOutputs(recipe, level);
        List<FluidStack> fluidOutputs = AdaptiveRecipeConverter.extractFluidOutputs(recipe, level);
        List<AdaptiveRecipeConverter.ChemicalStack> chemicalOutputs = AdaptiveRecipeConverter.extractChemicalOutputs(recipe, level);

        if (itemOutputs.isEmpty() && fluidOutputs.isEmpty() && chemicalOutputs.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("      No outputs found for {}",
                    recipe.getClass().getSimpleName());
            return null;
        }

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
                builder.placeholderId(chemicalOutputs.getFirst().getChemical().getFullId());
            } else if (!fluidOutputs.isEmpty()) {
                builder.placeholderId(BuiltInRegistries.FLUID.getKey(fluidOutputs.getFirst().getFluid()).toString());
            }
        }

        builder.category(RecipeCategory.JEI_IMPORTED)
                .itemOutputs(itemOutputs)
                .fluidOutputs(fluidOutputs)
                .chemicalOutputs(chemicalOutputs);

        builder.priority(900);

        net.minecraft.world.item.crafting.RecipeType<?> recipeType = AdaptiveRecipeConverter.extractRecipeType(recipe);
        if (recipeType == null) {
            ComplexityAnalyzer.LOGGER.debug("      Skipping {} because recipe type is unknown",
                    recipe.getClass().getSimpleName());
            return null;
        }
        builder.recipeType(recipeType);

        List<List<ItemStack>> itemInputs = AdaptiveRecipeConverter.extractInputs(recipe, level);
        List<List<FluidStack>> fluidInputs = AdaptiveRecipeConverter.extractFluidInputs(recipe, level);
        List<List<AdaptiveRecipeConverter.ChemicalStack>> chemicalInputs = AdaptiveRecipeConverter.extractChemicalInputs(recipe, level);

        if (itemInputs.isEmpty() && fluidInputs.isEmpty() && chemicalInputs.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("      No inputs found for {} -> {}",
                    recipe.getClass().getSimpleName(), resultItem);
        }

        for (List<ItemStack> inputVariants : itemInputs) {
            if (inputVariants.isEmpty()) continue;

            List<Item> items = inputVariants.stream()
                    .map(ItemStack::getItem)
                    .distinct()
                    .toList();

            int count = inputVariants.getFirst().getCount();
            builder.addIngredient(items, count);
        }

        for (List<FluidStack> inputVariants : fluidInputs) {
            if (inputVariants.isEmpty()) continue;

            List<net.minecraft.world.level.material.Fluid> fluids = inputVariants.stream()
                    .map(FluidStack::getFluid)
                    .distinct()
                    .toList();

            int amount = inputVariants.getFirst().getAmount();
            builder.addFluidIngredient(fluids, amount);
        }

        for (List<AdaptiveRecipeConverter.ChemicalStack> inputVariants : chemicalInputs) {
            if (inputVariants.isEmpty()) continue;

            List<org.complexityanalyzer.graph.Chemical> chemicals = inputVariants.stream()
                    .map(AdaptiveRecipeConverter.ChemicalStack::getChemical)
                    .distinct()
                    .toList();

            long amount = inputVariants.getFirst().getAmount();
            builder.addChemicalIngredient(chemicals, amount);
        }

        RecipeNode node = builder.build();

        if (node.getIngredients().isEmpty() && node.getFluidIngredients().isEmpty() && node.getChemicalIngredients().isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("      Recipe has no ingredients after conversion: {}",
                    recipe.getClass().getSimpleName());
            return null;
        }

        return node;
    }
}