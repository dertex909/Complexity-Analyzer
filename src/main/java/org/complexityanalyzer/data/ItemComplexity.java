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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.data;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.graph.RecipeNode;
import org.jetbrains.annotations.Nullable;

/**
 * The fully computed complexity result for a single item: its numeric score, the difficulty
 * {@link ComplexityCategory category} it falls into, how deep its crafting tree is, the chosen optimal recipe,
 * and status flags. Returned from the API via {@code items().getDetailed(item)}.
 *
 * <p>A score of {@code -1} together with {@link ComplexityCategory#UNCALCULABLE} indicates the item could not
 * be evaluated (see {@link #getErrorMessage()}). Use {@link #isValid()} to test for a usable result.
 */
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

    private ItemComplexity(Builder builder) {
        this.item = builder.item;
        this.complexity = builder.complexity;
        this.depth = builder.depth;
        this.totalIngredients = builder.totalIngredients;
        this.category = builder.category != null ? builder.category : ComplexityCategory.fromComplexity(builder.complexity);
        this.hasCycle = builder.hasCycle;
        this.hasRecipe = builder.hasRecipe;
        this.errorMessage = builder.errorMessage;
        this.optimalRecipe = builder.optimalRecipe;
    }

    /**
     * Creates an uncalculable result carrying an error message (score {@code -1},
     * {@link ComplexityCategory#UNCALCULABLE}).
     *
     * @param item  the item that failed to evaluate
     * @param error the failure reason
     * @return the error result
     */
    public static ItemComplexity error(Item item, String error) {
        return new Builder(item)
                .complexity(-1)
                .category(ComplexityCategory.UNCALCULABLE)
                .errorMessage(error)
                .build();
    }

    /**
     * @return the item this result describes.
     */
    public Item getItem() {
        return item;
    }

    /**
     * @return the numeric complexity score; higher is harder, {@code -1} if uncalculable, {@code Infinity} if unobtainable.
     */
    public double getComplexity() {
        return complexity;
    }

    /**
     * @return the depth of the optimal crafting tree (0 for raw/base items).
     */
    public int getDepth() {
        return depth;
    }

    /**
     * @return the total number of ingredient units across the resolved crafting tree.
     */
    public int getTotalIngredients() {
        return totalIngredients;
    }

    /**
     * @return the bucketed difficulty tier derived from the score.
     */
    public ComplexityCategory getCategory() {
        return category;
    }

    /**
     * @return {@code true} if a dependency cycle was detected while resolving this item.
     */
    public boolean hasCycle() {
        return hasCycle;
    }

    /**
     * @return {@code true} if the item is produced by at least one recipe (vs. only raw sources).
     */
    public boolean hasRecipe() {
        return hasRecipe;
    }

    /**
     * @return the failure reason if the item could not be evaluated, otherwise {@code null}.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * @return the recipe the solver selected as cheapest, or {@code null} for raw/uncraftable items.
     */
    @Nullable
    public RecipeNode getOptimalRecipe() {
        return optimalRecipe;
    }

    /**
     * @return {@code true} if the result is usable (no error, no cycle, non-negative score).
     */
    public boolean isValid() {
        return errorMessage == null && !hasCycle && complexity >= 0;
    }

    @Override
    public String toString() {
        return String.format("ItemComplexity{item=%s, complexity=%.2f, depth=%d, category=%s}",
                item, complexity, depth, category);
    }

    /**
     * Fluent builder for {@link ItemComplexity} instances.
     */
    public static class Builder {
        private final Item item;
        private final boolean hasCycle = false;
        private double complexity = 0;
        private int depth = 0;
        private int totalIngredients = 0;
        private ComplexityCategory category;
        private boolean hasRecipe = true;
        private String errorMessage;
        private RecipeNode optimalRecipe;

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

        public Builder hasRecipe(boolean hasRecipe) {
            this.hasRecipe = hasRecipe;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public void optimalRecipe(RecipeNode recipe) {
            this.optimalRecipe = recipe;
        }

        public void baseData() {
        }

        public ItemComplexity build() {
            return new ItemComplexity(this);
        }
    }
}
