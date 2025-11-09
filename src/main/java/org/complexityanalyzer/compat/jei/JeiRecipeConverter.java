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
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class JeiRecipeConverter {
    private static final RecipeGraph graph = new RecipeGraph();

    public static List<RecipeNode> convertAll(Map<RecipeType<?>, List<?>> recipesByType, Level level) {
        List<RecipeNode> result = new ArrayList<>();

        for (Map.Entry<RecipeType<?>, List<?>> entry : recipesByType.entrySet()) {
            RecipeType<?> jeiType = entry.getKey();
            List<?> recipes = entry.getValue();

            // Получаем ID типа из JEI
            ResourceLocation jeiTypeId = jeiType.getUid();

            for (Object recipe : recipes) {
                try {
                    RecipeNode node = convert(recipe, level, jeiTypeId);

                    if (node != null) {
                        result.add(node);
                    }
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.debug("Exception converting recipe: {}", e.getMessage());
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("Processing recipes from RecipeManager...");

        RecipeManager recipeManager = level.getRecipeManager();

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            net.minecraft.world.item.crafting.RecipeType<?> mcType = recipe.getType();

            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(mcType);
            if (typeId == null) {
                continue;
            }

            if (typeId.getNamespace().equals("minecraft")) {
                continue;
            }

            try {
                RecipeNode node = convert(recipe, level, typeId);
                if (node != null) {
                    result.add(node);
                }
            } catch (Exception ignored) {}
        }

        return result;
    }

    public static RecipeNode convert(Object recipe, Level level, ResourceLocation jeiTypeId) {
        List<ItemStack> itemOutputs = AdaptiveRecipeConverter.extractOutputs(recipe, level);
        List<FluidStack> fluidOutputs = AdaptiveRecipeConverter.extractFluidOutputs(recipe, level);
        List<AdaptiveRecipeConverter.ChemicalOutput> chemicalOutputs =
                AdaptiveRecipeConverter.extractChemicalOutputs(recipe, level);

        // Если нет никакого выхода, рецепт бесполезен
        if (itemOutputs.isEmpty() && fluidOutputs.isEmpty() && chemicalOutputs.isEmpty()) {
            return null;
        }

        // --- Определяем результат и строим узел ---
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
                builder.placeholderId(BuiltInRegistries.FLUID.getKey(fluidOutputs.getFirst().getFluid()).toString());
            }
        }

        // --- Собираем узел рецепта ---
        builder.category(RecipeCategory.JEI_IMPORTED)
                .itemOutputs(itemOutputs)
                .fluidOutputs(fluidOutputs)
                .chemicalOutputs(chemicalOutputs)
                .rawRecipe(recipe)
                .priority(900);

        // Извлекаем тип рецепта
        net.minecraft.world.item.crafting.RecipeType<?> recipeType = AdaptiveRecipeConverter.extractRecipeType(recipe);

        if (recipeType == null && jeiTypeId != null) {
            recipeType = BuiltInRegistries.RECIPE_TYPE.get(jeiTypeId);

            if (recipeType == null) {
                final String typeId = jeiTypeId.toString();
                recipeType = new net.minecraft.world.item.crafting.RecipeType<net.minecraft.world.item.crafting.Recipe<?>>() {
                    @Override
                    public String toString() {
                        return typeId;
                    }
                };
            }
        }

        if (recipeType == null) {
            return null;
        }

        builder.recipeType(recipeType);

        List<List<ItemStack>> itemInputs = AdaptiveRecipeConverter.extractInputs(recipe, level);
        List<List<FluidStack>> fluidInputs = AdaptiveRecipeConverter.extractFluidInputs(recipe, level);
        List<AdaptiveRecipeConverter.ChemicalOutput> chemicalInputs =
                AdaptiveRecipeConverter.extractChemicalInputs(recipe);

        for (List<ItemStack> inputVariants : itemInputs) {
            if (inputVariants.isEmpty()) continue;
            List<Item> items = inputVariants.stream()
                    .map(ItemStack::getItem)
                    .distinct()
                    .toList();
            int count = inputVariants.getFirst().getCount();
            builder.addIngredient(items, count);
        }

        // Добавляем входы в виде жидкостей
        for (List<FluidStack> inputVariants : fluidInputs) {
            if (inputVariants.isEmpty()) continue;
            List<net.minecraft.world.level.material.Fluid> fluids = inputVariants.stream()
                    .map(FluidStack::getFluid)
                    .distinct()
                    .toList();
            int amount = inputVariants.getFirst().getAmount();
            builder.addFluidIngredient(fluids, amount);
        }

        // ✅ ДОБАВЛЯЕМ CHEMICAL INPUTS!
        for (AdaptiveRecipeConverter.ChemicalOutput chemInput : chemicalInputs) {
            builder.addChemicalIngredient(chemInput.id(), (int) chemInput.amount());
        }

        // ✅ ИСПРАВЛЕНО: Строим node ПЕРЕД проверкой!
        RecipeNode node = builder.build();

        // Теперь проверяем входы
        if (node.getIngredients().isEmpty() &&
                node.getFluidIngredients().isEmpty() &&
                node.getChemicalIngredients().isEmpty()) {
            return null;
        }

        graph.addRecipe(node);
        return node;
    }
}