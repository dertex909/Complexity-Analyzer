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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class DirectRecipeExtractor {

    private static final Set<String> VANILLA_RECIPE_TYPES = Set.of(
            "minecraft:crafting",
            "minecraft:smelting",
            "minecraft:blasting",
            "minecraft:smoking",
            "minecraft:campfire_cooking",
            "minecraft:stonecutting",
            "minecraft:smithing"
    );

    public static void extractCustomRecipes(RecipeGraph graph, Level level) {
        var recipeManager = level.getRecipeManager();

        ComplexityAnalyzer.LOGGER.info("Extracting custom recipes directly from RecipeManager...");

        Set<RecipeType<?>> recipeTypes = new HashSet<>();

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            recipeTypes.add(holder.value().getType());
        }

        int totalImported = 0;
        int customTypes = 0;
        Map<String, Integer> recipeStats = new LinkedHashMap<>();

        Map<RecipeCategory, Integer> categoryStats = new EnumMap<>(RecipeCategory.class);

        for (RecipeType<?> recipeType : recipeTypes) {
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);
            if (typeId == null) {
                continue;
            }

            String typeIdString = typeId.toString();

            if (VANILLA_RECIPE_TYPES.contains(typeIdString)) {
                continue;
            }

            try {
                List<RecipeNode> converted = extractRecipesOfType(recipeManager, recipeType, level);

                for (RecipeNode node : converted) {
                    graph.addRecipe(node);
                    categoryStats.merge(node.getCategory(), 1, Integer::sum);
                }

                if (!converted.isEmpty()) {
                    recipeStats.put(typeIdString, converted.size());
                    totalImported += converted.size();
                    customTypes++;
                }

            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to process recipe type {}: {}",
                        typeIdString, e.getMessage());
            }
        }

        ComplexityAnalyzer.LOGGER.info("Direct extraction complete: {} custom recipe types, {} recipes total",
                customTypes, totalImported);

        if (!recipeStats.isEmpty()) {
            logRecipeStats("Recipe type breakdown", recipeStats);
        }

        if (!categoryStats.isEmpty()) {
            logRecipeStats("Category classification", categoryStats);
        }
    }

    private static void logRecipeStats(String title, Map<?, Integer> stats) {
        ComplexityAnalyzer.LOGGER.info("{}:", title);
        stats.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(entry ->
                        ComplexityAnalyzer.LOGGER.info("  - {}: {} recipes", entry.getKey(), entry.getValue())
                );
    }


    @SuppressWarnings("unchecked")
    private static List<RecipeNode> extractRecipesOfType(
            net.minecraft.world.item.crafting.RecipeManager recipeManager,
            RecipeType<?> recipeType,
            Level level) {

        List<RecipeNode> result = new ArrayList<>();
        String typeIdString = BuiltInRegistries.RECIPE_TYPE.getKey(recipeType).toString();

        try {
            var method = net.minecraft.world.item.crafting.RecipeManager.class.getMethod(
                    "getAllRecipesFor",
                    RecipeType.class
            );

            @SuppressWarnings("rawtypes")
            var recipes = (Collection<RecipeHolder>) method.invoke(recipeManager, recipeType);

            // ✅ ОТЛАДКА
            if (typeIdString.contains("separat") || typeIdString.contains("electrolytic")) {
                ComplexityAnalyzer.LOGGER.warn("🔍 Found {} recipes of type {}",
                        recipes.size(), typeIdString);
            }

            for (RecipeHolder<?> holder : recipes) {
                try {
                    Recipe<?> recipe = holder.value();
                    RecipeNode node = AdaptiveRecipeConverter.convertRecipe(recipe, level);

                    if (node != null) {
                        result.add(node);
                    } else if (typeIdString.contains("separat") || typeIdString.contains("electrolytic")) {
                        ComplexityAnalyzer.LOGGER.warn("   ❌ Recipe {} converted to NULL", holder.id());
                    }
                } catch (Exception e) {
                    if (typeIdString.contains("separat") || typeIdString.contains("electrolytic")) {
                        ComplexityAnalyzer.LOGGER.warn("   ❌ Recipe conversion failed: {}", e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Error extracting recipes: {}", e.getMessage());
        }

        return result;
    }
}