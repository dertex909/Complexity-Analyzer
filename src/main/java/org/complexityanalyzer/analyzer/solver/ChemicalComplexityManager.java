package org.complexityanalyzer.analyzer.solver;

import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Универсальный менеджер сложности для всех типов ресурсов:
 * - Fluids
 * - Chemicals (Gas, Slurry, Infusion, Pigment)
 * - И любые другие моды
 */
public class ChemicalComplexityManager {

    private final Map<ResourceLocation, Double> complexities = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, List<ChemicalRecipe>> producingRecipes = new ConcurrentHashMap<>();

    /**
     * Регистрирует chemical из рецепта
     */
    public void registerChemical(ResourceLocation chemicalId, double initialComplexity) {
        complexities.putIfAbsent(chemicalId, initialComplexity);
    }

    /**
     * Добавляет рецепт производства chemical
     */
    public void addProducingRecipe(ResourceLocation outputChemical, ChemicalRecipe recipe) {
        producingRecipes.computeIfAbsent(outputChemical, k -> new ArrayList<>()).add(recipe);
    }

    /**
     * Получить сложность chemical
     */
    public double getComplexity(ResourceLocation chemicalId) {
        return complexities.getOrDefault(chemicalId, ComplexityConfig.getFluidBaseComplexity());
    }

    /**
     * Установить сложность
     */
    public void setComplexity(ResourceLocation chemicalId, double complexity) {
        double oldValue = complexities.getOrDefault(chemicalId, Double.POSITIVE_INFINITY);
        complexities.put(chemicalId, complexity);

        if (Math.abs(oldValue - complexity) > 0.01) {
            ComplexityAnalyzer.LOGGER.debug("Chemical {} complexity updated: {} → {}",
                    chemicalId, String.format("%.2f", oldValue), String.format("%.2f", complexity));
        }
    }

    /**
     * Получить все зарегистрированные chemicals
     */
    public Set<ResourceLocation> getAllChemicals() {
        return new HashSet<>(complexities.keySet());
    }

    /**
     * Получить рецепты производства
     */
    public List<ChemicalRecipe> getProducingRecipes(ResourceLocation chemicalId) {
        return producingRecipes.getOrDefault(chemicalId, Collections.emptyList());
    }

    /**
     * Рассчитать сложность chemical на основе рецептов
     */
    public double calculateComplexity(ResourceLocation chemicalId,
                                      Map<net.minecraft.world.item.Item, Double> itemComplexities,
                                      Map<net.minecraft.world.level.material.Fluid, Double> fluidComplexities) {
        List<ChemicalRecipe> recipes = getProducingRecipes(chemicalId);

        if (recipes.isEmpty()) {
            return ComplexityConfig.getFluidBaseComplexity();
        }

        double minCost = Double.POSITIVE_INFINITY;

        for (ChemicalRecipe recipe : recipes) {
            double cost = recipe.calculateCost(itemComplexities, fluidComplexities, this);
            if (!Double.isInfinite(cost)) {
                minCost = Math.min(minCost, cost);
            }
        }

        return Double.isInfinite(minCost) ? ComplexityConfig.getFluidBaseComplexity() : minCost;
    }

    public int size() {
        return complexities.size();
    }

    public void clear() {
        complexities.clear();
        producingRecipes.clear();
    }

    /**
     * Представление рецепта производства chemical
     */
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

            // Item inputs
            for (Map.Entry<net.minecraft.world.item.Item, Double> entry : itemInputs.entrySet()) {
                double itemCost = itemComplexities.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
                if (Double.isInfinite(itemCost)) return Double.POSITIVE_INFINITY;
                totalCost += itemCost * entry.getValue();
            }

            // Fluid inputs
            for (Map.Entry<net.minecraft.world.level.material.Fluid, Double> entry : fluidInputs.entrySet()) {
                double fluidCost = fluidComplexities.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
                if (Double.isInfinite(fluidCost)) return Double.POSITIVE_INFINITY;
                totalCost += fluidCost * entry.getValue();
            }

            // Chemical inputs
            for (Map.Entry<ResourceLocation, Double> entry : chemicalInputs.entrySet()) {
                double chemCost = chemicalManager.getComplexity(entry.getKey());
                if (Double.isInfinite(chemCost)) return Double.POSITIVE_INFINITY;
                totalCost += chemCost * entry.getValue();
            }

            // Machine cost
            totalCost += machineComplexity;

            // Normalize by output amount
            if (outputAmount <= 0) return Double.POSITIVE_INFINITY;
            return (totalCost * multiplier) / outputAmount;
        }
    }
}