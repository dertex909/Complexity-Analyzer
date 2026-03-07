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

package org.complexityanalyzer.data;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.Optional;

@SuppressWarnings("unused")
public class ItemComplexity {
    private final Item item;
    private final double complexity;
    private final int depth;
    private final int totalIngredients;
    private final ComplexityCategory category;
    private final boolean hasCycle;
    private final boolean hasRecipe;
    private final String errorMessage;
    private final RecipeNode optimalRecipe;
    private final BaseResourceData baseData;

    private ItemComplexity(Builder builder) {
        this.item = builder.item;
        this.complexity = builder.complexity;
        this.depth = builder.depth;
        this.totalIngredients = builder.totalIngredients;
        this.category = builder.category != null
                ? builder.category
                : ComplexityCategory.fromComplexity(builder.complexity);
        this.hasCycle = builder.hasCycle;
        this.hasRecipe = builder.hasRecipe;
        this.errorMessage = builder.errorMessage;
        this.optimalRecipe = builder.optimalRecipe;
        this.baseData = builder.baseData;
    }

    public Item getItem() {
        return item;
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

    public Optional<RecipeNode> getOptimalRecipe() {
        return Optional.ofNullable(optimalRecipe);
    }

    public Optional<BaseResourceData> getBaseData() {
        return Optional.ofNullable(baseData);
    }

    public boolean isValid() {
        return errorMessage == null && !hasCycle && complexity >= 0;
    }

    public static class Builder {
        private final Item item;
        private double complexity = 0;
        private int depth = 0;
        private int totalIngredients = 0;
        private ComplexityCategory category;
        private boolean hasCycle = false;
        private boolean hasRecipe = true;
        private String errorMessage;
        private RecipeNode optimalRecipe;
        private BaseResourceData baseData;

        public Builder(Item item) {
            this.item = item;
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

        public Builder baseData(BaseResourceData data) {
            this.baseData = data;
            return this;
        }

        public ItemComplexity build() {
            return new ItemComplexity(this);
        }
    }

    public static ItemComplexity error(Item item, String error) {
        return new Builder(item)
                .complexity(-1)
                .category(ComplexityCategory.UNCALCULABLE)
                .errorMessage(error)
                .build();
    }

    public static ItemComplexity cycle(Item item) {
        return new Builder(item)
                .complexity(-1)
                .category(ComplexityCategory.UNCALCULABLE)
                .hasCycle(true)
                .errorMessage("Cyclic dependency detected")
                .build();
    }

    public static ItemComplexity noRecipe(Item item) {
        return new Builder(item)
                .complexity(1.0)
                .category(ComplexityCategory.TRIVIAL)
                .hasRecipe(false)
                .build();
    }

    public static ItemComplexity infinite(Item item) {
        return new Builder(item)
                .complexity(Double.POSITIVE_INFINITY)
                .category(ComplexityCategory.ETERNAL)
                .build();
    }

    @Override
    public String toString() {
        return String.format("ItemComplexity{item=%s, complexity=%.2f, depth=%d, category=%s}",
                item, complexity, depth, category);
    }
}
