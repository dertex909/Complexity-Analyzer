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
import net.minecraft.world.level.Level;
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
        List<ItemStack> outputs = AdaptiveRecipeConverter.extractOutputs(recipe, level);

        if (outputs.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("      No outputs found for {}",
                    recipe.getClass().getSimpleName());
            return null;
        }

        ItemStack primaryOutput = outputs.getFirst();
        Item resultItem = primaryOutput.getItem();
        int resultCount = primaryOutput.getCount();

        RecipeNode.Builder builder = new RecipeNode.Builder(resultItem)
                .resultCount(resultCount)
                .category(RecipeCategory.JEI_IMPORTED);

        builder.priority(900);

        List<List<ItemStack>> inputs = AdaptiveRecipeConverter.extractInputs(recipe, level);

        if (inputs.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("      No inputs found for {} -> {}",
                    recipe.getClass().getSimpleName(), resultItem);
        }

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
            ComplexityAnalyzer.LOGGER.debug("      Recipe has no ingredients after conversion: {}",
                    recipe.getClass().getSimpleName());
            return null;
        }

        return node;
    }
}