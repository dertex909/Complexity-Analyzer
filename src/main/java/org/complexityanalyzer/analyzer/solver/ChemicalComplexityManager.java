/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.analyzer.solver;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

public class ChemicalComplexityManager {

    private final Object2DoubleMap<ResourceLocation> complexities = Object2DoubleMaps.synchronize(new Object2DoubleOpenHashMap<>());
    private final Object2ObjectMap<ResourceLocation, ObjectList<ChemicalRecipe>> producingRecipes = Object2ObjectMaps.synchronize(new Object2ObjectOpenHashMap<>());

    public void registerChemical(ResourceLocation chemicalId, double initialComplexity) {
        complexities.putIfAbsent(chemicalId, initialComplexity);
    }

    public void addProducingRecipe(ResourceLocation outputChemical, ChemicalRecipe recipe) {
        producingRecipes.computeIfAbsent(outputChemical, k -> new ObjectArrayList<>()).add(recipe);
    }

    public double getComplexity(ResourceLocation chemicalId) {
        return complexities.getOrDefault(chemicalId, Double.POSITIVE_INFINITY);
    }

    public void setComplexity(ResourceLocation chemicalId, double complexity) {
        complexities.put(chemicalId, complexity);
    }

    public ObjectSet<ResourceLocation> getAllChemicals() {
        return new ObjectOpenHashSet<>(complexities.keySet());
    }

    public ObjectList<ChemicalRecipe> getProducingRecipes(ResourceLocation chemicalId) {
        return producingRecipes.getOrDefault(chemicalId, ObjectLists.emptyList());
    }

    public double calculateComplexity(ResourceLocation chemicalId, Reference2DoubleMap<Item> itemComplexities,
                                      Reference2DoubleMap<Fluid> fluidComplexities) {
        var recipes = getProducingRecipes(chemicalId);

        if (recipes.isEmpty()) return Double.POSITIVE_INFINITY;

        var minCost = Double.POSITIVE_INFINITY;

        for (var recipe : recipes) {
            var cost = recipe.calculateCost(itemComplexities, fluidComplexities, this);
            if (!Double.isInfinite(cost)) minCost = Math.min(minCost, cost);
        }

        return Double.isInfinite(minCost) ? Double.POSITIVE_INFINITY : minCost;
    }

    public int size() {
        return complexities.size();
    }

    public void clear() {
        complexities.clear();
        producingRecipes.clear();
    }

    public record ChemicalRecipe(
            Reference2DoubleMap<Item> itemInputs,
            Reference2DoubleMap<Fluid> fluidInputs,
            Object2DoubleMap<ResourceLocation> chemicalInputs,
            double outputAmount,
            double machineComplexity,
            double multiplier
    ) {
        public double calculateCost(Reference2DoubleMap<Item> itemComplexities,
                                    Reference2DoubleMap<Fluid> fluidComplexities,
                                    ChemicalComplexityManager chemicalManager) {
            var totalCost = 0.0;

            for (var entry : itemInputs.reference2DoubleEntrySet()) {
                var itemCost = itemComplexities.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
                if (Double.isInfinite(itemCost)) return Double.POSITIVE_INFINITY;
                totalCost += itemCost * entry.getDoubleValue();
            }

            for (var entry : fluidInputs.reference2DoubleEntrySet()) {
                var fluidCost = fluidComplexities.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
                if (Double.isInfinite(fluidCost)) return Double.POSITIVE_INFINITY;
                totalCost += fluidCost * entry.getDoubleValue();
            }

            for (var entry : chemicalInputs.object2DoubleEntrySet()) {
                var chemCost = chemicalManager.getComplexity(entry.getKey());
                if (Double.isInfinite(chemCost)) return Double.POSITIVE_INFINITY;
                totalCost += chemCost * entry.getDoubleValue();
            }

            totalCost += machineComplexity;

            if (outputAmount <= 0) return Double.POSITIVE_INFINITY;
            return (totalCost * multiplier) / outputAmount;
        }
    }
}