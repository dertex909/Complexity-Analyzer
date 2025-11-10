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

package org.complexityanalyzer.analyzer.solver;

import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChemicalComplexityManager {

    private final Map<ResourceLocation, Double> complexities = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, List<ChemicalRecipe>> producingRecipes = new ConcurrentHashMap<>();

    public void registerChemical(ResourceLocation chemicalId, double initialComplexity) {
        complexities.putIfAbsent(chemicalId, initialComplexity);
    }

    public void addProducingRecipe(ResourceLocation outputChemical, ChemicalRecipe recipe) {
        producingRecipes.computeIfAbsent(outputChemical, k -> new ArrayList<>()).add(recipe);
    }

    public double getComplexity(ResourceLocation chemicalId) {
        return complexities.getOrDefault(chemicalId, Double.POSITIVE_INFINITY);
    }

    public void setComplexity(ResourceLocation chemicalId, double complexity) {
        complexities.getOrDefault(chemicalId, Double.POSITIVE_INFINITY);
        complexities.put(chemicalId, complexity);
    }

    public Set<ResourceLocation> getAllChemicals() {
        return new HashSet<>(complexities.keySet());
    }

    public List<ChemicalRecipe> getProducingRecipes(ResourceLocation chemicalId) {
        return producingRecipes.getOrDefault(chemicalId, Collections.emptyList());
    }

    public double calculateComplexity(ResourceLocation chemicalId,
                                      Map<net.minecraft.world.item.Item, Double> itemComplexities,
                                      Map<net.minecraft.world.level.material.Fluid, Double> fluidComplexities) {
        List<ChemicalRecipe> recipes = getProducingRecipes(chemicalId);

        if (recipes.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double minCost = Double.POSITIVE_INFINITY;

        for (ChemicalRecipe recipe : recipes) {
            double cost = recipe.calculateCost(itemComplexities, fluidComplexities, this);
            if (!Double.isInfinite(cost)) {
                minCost = Math.min(minCost, cost);
            }
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
            Map<net.minecraft.world.item.Item, Double> itemInputs,
            Map<net.minecraft.world.level.material.Fluid, Double> fluidInputs,
            Map<ResourceLocation, Double> chemicalInputs,
            double outputAmount,
            double machineComplexity,
            double multiplier
    ) {
        public double calculateCost(Map<net.minecraft.world.item.Item, Double> itemComplexities,
                                    Map<net.minecraft.world.level.material.Fluid, Double> fluidComplexities,
                                    ChemicalComplexityManager chemicalManager) {
            double totalCost = 0.0;

            for (Map.Entry<net.minecraft.world.item.Item, Double> entry : itemInputs.entrySet()) {
                double itemCost = itemComplexities.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
                if (Double.isInfinite(itemCost)) return Double.POSITIVE_INFINITY;
                totalCost += itemCost * entry.getValue();
            }

            for (Map.Entry<net.minecraft.world.level.material.Fluid, Double> entry : fluidInputs.entrySet()) {
                double fluidCost = fluidComplexities.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
                if (Double.isInfinite(fluidCost)) return Double.POSITIVE_INFINITY;
                totalCost += fluidCost * entry.getValue();
            }

            for (Map.Entry<ResourceLocation, Double> entry : chemicalInputs.entrySet()) {
                double chemCost = chemicalManager.getComplexity(entry.getKey());
                if (Double.isInfinite(chemCost)) return Double.POSITIVE_INFINITY;
                totalCost += chemCost * entry.getValue();
            }

            totalCost += machineComplexity;

            if (outputAmount <= 0) return Double.POSITIVE_INFINITY;
            return (totalCost * multiplier) / outputAmount;
        }
    }
}