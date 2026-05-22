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

package org.complexityanalyzer.data;

import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.graph.RecipeNode;
import org.jetbrains.annotations.Nullable;

public class FluidComplexity {
    private final Fluid fluid;
    private final double complexity;
    private final int depth;
    private final int totalIngredients;
    private final ComplexityCategory category;
    private final boolean hasCycle;
    private final boolean hasRecipe;
    private final String errorMessage;
    private final RecipeNode optimalRecipe;

    private FluidComplexity(Builder builder) {
        this.fluid = builder.fluid;
        this.complexity = builder.complexity;
        this.depth = builder.depth;
        this.totalIngredients = builder.totalIngredients;
        this.category = builder.category != null
                ? builder.category : ComplexityCategory.fromComplexity(builder.complexity);
        this.hasCycle = builder.hasCycle;
        this.hasRecipe = builder.hasRecipe;
        this.errorMessage = builder.errorMessage;
        this.optimalRecipe = builder.optimalRecipe;
    }

    public Fluid getFluid() {
        return fluid;
    }

    public double getComplexity() {
        return complexity;
    }

    public int getDepth() {
        return depth;
    }

    public int getTotalIngredients() {
        return totalIngredients;
    }

    public ComplexityCategory getCategory() {
        return category;
    }

    public boolean hasCycle() {
        return hasCycle;
    }

    public boolean hasRecipe() {
        return hasRecipe;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Nullable
    public RecipeNode getOptimalRecipe() {
        return optimalRecipe;
    }

    public boolean isValid() {
        return errorMessage == null && !hasCycle && complexity >= 0;
    }

    public static class Builder {
        private final Fluid fluid;
        private double complexity = 0;
        private int depth = 0;
        private int totalIngredients = 0;
        private ComplexityCategory category;
        private boolean hasCycle = false;
        private boolean hasRecipe = true;
        private String errorMessage;
        private RecipeNode optimalRecipe;

        public Builder(Fluid fluid) {
            this.fluid = fluid;
        }

        public Builder complexity(double complexity) {
            this.complexity = complexity;
            return this;
        }

        public Builder depth(int depth) {
            this.depth = depth;
            return this;
        }

        public Builder totalIngredients(int totalIngredients) {
            this.totalIngredients = totalIngredients;
            return this;
        }

        public Builder category(ComplexityCategory category) {
            this.category = category;
            return this;
        }

        public Builder hasCycle(boolean hasCycle) {
            this.hasCycle = hasCycle;
            return this;
        }

        public Builder hasRecipe(boolean hasRecipe) {
            this.hasRecipe = hasRecipe;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder optimalRecipe(RecipeNode recipe) {
            this.optimalRecipe = recipe;
            return this;
        }

        public FluidComplexity build() {
            return new FluidComplexity(this);
        }
    }

    public static FluidComplexity error(Fluid fluid, String error) {
        return new Builder(fluid)
                .complexity(-1)
                .category(ComplexityCategory.UNCALCULABLE)
                .errorMessage(error)
                .build();
    }

    public static FluidComplexity cycle(Fluid fluid) {
        return new Builder(fluid)
                .complexity(-1)
                .category(ComplexityCategory.UNCALCULABLE)
                .hasCycle(true)
                .errorMessage("Cyclic dependency detected")
                .build();
    }

    public static FluidComplexity noRecipe(Fluid fluid) {
        return new Builder(fluid)
                .complexity(1.0)
                .category(ComplexityCategory.TRIVIAL)
                .hasRecipe(false)
                .build();
    }

    public static FluidComplexity infinite(Fluid fluid) {
        return new Builder(fluid)
                .complexity(Double.POSITIVE_INFINITY)
                .category(ComplexityCategory.UNOBTAINABLE)
                .build();
    }

    @Override
    public String toString() {
        return String.format("FluidComplexity{fluid=%s, complexity=%.2f, depth=%d, category=%s}",
                fluid, complexity, depth, category);
    }
}
