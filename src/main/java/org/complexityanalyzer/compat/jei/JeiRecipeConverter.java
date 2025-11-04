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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class JeiRecipeConverter {

    public static List<RecipeNode> convertAll(Map<RecipeType<?>, List<?>> recipesByType) {
        List<RecipeNode> result = new ArrayList<>();

        for (Map.Entry<RecipeType<?>, List<?>> entry : recipesByType.entrySet()) {
            RecipeType<?> type = entry.getKey();
            List<?> recipes = entry.getValue();

            int converted = 0;
            int failed = 0;

            for (Object recipe : recipes) {
                try {
                    RecipeNode node = convert(recipe);
                    if (node != null) {
                        result.add(node);
                        converted++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                }
            }

            if (converted > 0) {
                ComplexityAnalyzer.LOGGER.info("  Converted {}/{} recipes from type {}",
                        converted, recipes.size(), type.getUid());
            } else if (failed > 0) {
                ComplexityAnalyzer.LOGGER.debug("  Skipped {} non-item recipes from type {}",
                        failed, type.getUid());
            }
        }

        return result;
    }

    private static RecipeNode convert(Object recipe) {
        List<ItemStack> outputs = AdaptiveRecipeConverter.extractOutputs(recipe);

        if (outputs.isEmpty()) {
            return null;
        }

        ItemStack primaryOutput = outputs.getFirst();
        Item resultItem = primaryOutput.getItem();
        int resultCount = primaryOutput.getCount();

        RecipeNode.Builder builder = new RecipeNode.Builder(resultItem)
                .resultCount(resultCount)
                .category(RecipeCategory.JEI_IMPORTED);

        builder.priority(900);

        List<List<ItemStack>> inputs = AdaptiveRecipeConverter.extractInputs(recipe);
        for (List<ItemStack> inputVariants : inputs) {
            if (inputVariants.isEmpty()) continue;

            List<Item> items = inputVariants.stream()
                    .map(ItemStack::getItem)
                    .distinct()
                    .toList();

            int count = inputVariants.getFirst().getCount();
            builder.addIngredient(items, count);
        }

        RecipeNode node = builder.build();

        if (node.getIngredients().isEmpty()) {
            return null;
        }

        return node;
    }
}